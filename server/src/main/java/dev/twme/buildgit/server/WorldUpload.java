package dev.twme.buildgit.server;

import dev.twme.buildgit.core.anvil.RegionFile;
import dev.twme.buildgit.core.anvil.RegionUpgrade;
import dev.twme.buildgit.core.anvil.RegionWriter;
import dev.twme.buildgit.core.backup.S3;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.sync.Frame;
import dev.twme.buildgit.core.sync.SyncClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.logging.Level;
import java.util.zip.ZipInputStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.GameMasterBlock;

/**
 * 把單人世界上傳成新的建築世界（03-worlds §7）：伺服器請用戶端打包存檔的主世界，發一個 15 分鐘有效、綁定大小的
 * 預簽 PUT 網址，用戶端直接傳到 S3；伺服器再下載（有上限）、核對大小與雜湊、刪掉，逐個 chunk 檢查後當成起點世界。
 * 不帶歷史。上傳的內容不可信任：讀不懂、位置不對、比伺服器新的 chunk 不收；不能用管理方塊的人上傳的指令方塊等換成空氣、
 * 指令方塊礦車拿掉、告示牌等的點擊指令清掉。
 */
final class WorldUpload {
  /** 預簽網址的有效時間（秒）。 */
  private static final int URL_SECONDS = 15 * 60;
  /** 要求打包或給了網址後多久沒有下文就放棄（遊戲刻）：網址過期再多五分鐘。 */
  private static final long TIMEOUT = 20 * 60 * 20;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final BuildGit bg;
  private final Map<UUID, Pending> pending = new HashMap<>();

  /** 一位玩家進行中的上傳（主執行緒）。{@code key} 在給了網址後才有；{@code processing} 時已收完，在背景檢查。 */
  private static final class Pending {
    final String save, name;
    final WorldConfig config;
    final boolean admin, gameMaster;
    final S3 s3;
    final String endpoint;
    final long max, since;
    String key, sha;
    long size;
    boolean processing;

    Pending(String save, String name, WorldConfig config, boolean admin, boolean gameMaster, S3 s3, String endpoint, long max, long since) {
      this.save = save;
      this.name = name;
      this.config = config;
      this.admin = admin;
      this.gameMaster = gameMaster;
      this.s3 = s3;
      this.endpoint = endpoint;
      this.max = max;
      this.since = since;
    }
  }

  WorldUpload(BuildGit bg) {
    this.bg = bg;
  }

  private Messages msg() {
    return bg.messages();
  }

  /** {@code /bg new <名稱> from <存檔>}（主執行緒）：先檢查能不能建立，再請用戶端打包。 */
  void start(ServerPlayer p, String name, WorldConfig config, boolean admin, String save) throws IOException {
    var pw = bg.settings().playerWorlds();
    if (pw.uploadConnection().isEmpty()) throw new Workspace.Refused("這台伺服器沒有開放上傳單人世界");
    var c = Backup.connection(bg.platform().configDir(), pw.uploadConnection());
    if (c == null) {
      bg.platform().log().warning("player-worlds.upload.connection 指定的 S3 連線 " + pw.uploadConnection() + " 不在 s3.yml 或沒有填完");
      throw new Workspace.Refused("伺服器的上傳設定有誤，請管理員看主控台");
    }
    if (pending.containsKey(p.getUUID())) throw new Workspace.Refused("你已經有一個上傳在進行，等它完成再試");
    if (save.isEmpty() || save.length() > 128 || save.contains("\n")) throw new Workspace.Refused("存檔名稱不對：寫 saves 裡的資料夾名稱");
    bg.worlds().checkCreate(name, p, admin);
    var endpoint = pw.uploadEndpoint().isEmpty() ? c.endpoint() : pw.uploadEndpoint();
    pending.put(p.getUUID(), new Pending(save, name, config, admin, p.canUseGameMasterBlocks(), new S3(c), endpoint, pw.uploadMaxBytes(),
        bg.platform().tick()));
    bg.platform().sendSync(p, new Frame(Frame.IMPORT, SyncClient.IMPORT, 0, 0, (save + "\n" + pw.uploadMaxBytes()).getBytes(StandardCharsets.UTF_8)).encode());
    msg().send(bg.platform().sender(p), "world-upload-packing", "save", save);
  }

  /** 用戶端的回覆（主執行緒）。 */
  void receive(ServerPlayer p, Frame f) {
    var up = pending.get(p.getUUID());
    if (up == null || up.processing) return;
    var s = bg.platform().sender(p);
    switch (f.type()) {
      case Frame.IMPORT_ERROR -> {
        drop(p.getUUID());
        var code = new String(f.data(), StandardCharsets.UTF_8);
        String reason = switch (code) {
          case "no-save" -> "你的 saves 資料夾裡沒有 " + up.save + "（或它沒有主世界的 region）";
          case "too-big" -> "存檔超過 " + up.max / 1024 / 1024 + " MB";
          case "pack-failed" -> "用戶端無法打包存檔";
          default -> "用戶端上傳失敗" + (code.startsWith("upload-failed ") ? "（" + code.substring(14).replaceAll("[^0-9]", "") + "）" : "");
        };
        msg().send(s, "world-upload-failed", "save", up.save, "error", reason);
      }
      case Frame.IMPORT_READY -> {
        if (up.key != null) return;
        var a = new String(f.data(), StandardCharsets.UTF_8).split("\n");
        if (a.length != 2 || !a[0].matches("\\d{1,15}") || !a[1].matches("[0-9a-f]{64}")) {
          drop(p.getUUID());
          return;
        }
        up.size = Long.parseLong(a[0]);
        up.sha = a[1];
        if (up.size > up.max) {
          drop(p.getUUID());
          msg().send(s, "world-upload-failed", "save", up.save, "error", "存檔超過 " + up.max / 1024 / 1024 + " MB");
          return;
        }
        var id = new byte[16];
        RANDOM.nextBytes(id);
        up.key = "uploads/" + p.getUUID() + "/" + HexFormat.of().formatHex(id);
        var url = up.s3.presignPut(up.endpoint, up.key, up.size, URL_SECONDS);
        bg.platform().sendSync(p, new Frame(Frame.IMPORT_URL, SyncClient.IMPORT, 0, 0, url.getBytes(StandardCharsets.UTF_8)).encode());
        msg().send(s, "world-upload-uploading", "save", up.save, "mb", mb(up.size));
      }
      case Frame.IMPORT_DONE -> {
        if (up.key == null) return;
        up.processing = true;
        msg().send(s, "world-upload-checking", "save", up.save);
        var uuid = p.getUUID();
        Thread.ofVirtual().name("BuildGit upload " + uuid).start(() -> process(p, up));
      }
      default -> {}
    }
  }

  /** 背景：下載、核對、檢查，回主執行緒建立世界。 */
  private void process(ServerPlayer p, Pending up) {
    var uuid = p.getUUID();
    var tmp = bg.platform().repoDir().resolve("uploads").resolve(uuid.toString());
    try {
      deleteTree(tmp);
      Files.createDirectories(tmp);
      var zip = tmp.resolve("upload.zip");
      boolean found;
      try {
        found = up.s3.getFile(up.key, zip, up.size);
      } finally {
        purge(up);
      }
      if (!found) throw new Workspace.Refused("S3 上找不到上傳的檔案");
      if (Files.size(zip) != up.size || !S3.hex(S3.sha256(zip)).equals(up.sha)) throw new Workspace.Refused("上傳的檔案和用戶端回報的不同");
      var staged = tmp.resolve("staged");
      var result = unpack(zip, tmp.resolve("raw"), staged, up.max, up.gameMaster);
      Files.delete(zip);
      bg.platform().sync(() -> {
        pending.remove(uuid);
        var online = bg.platform().server().getPlayerList().getPlayer(uuid);
        try {
          var ws = bg.worlds().importWorld(up.name, p, up.config, up.admin, staged, "上傳單人世界 " + up.save);
          if (online != null) {
            msg().send(bg.platform().sender(online), "world-uploaded", "save", up.save, "world", ws.label(), "chunks", result.chunks(),
                "dropped", result.dropped());
            bg.realms().enter(online, ws);
          }
        } catch (IOException | RuntimeException e) {
          failed(uuid, up, e);
        } finally {
          deleteTree(tmp);
        }
      });
    } catch (IOException | RuntimeException e) {
      deleteTree(tmp);
      bg.platform().sync(() -> {
        pending.remove(uuid);
        failed(uuid, up, e);
      });
    }
  }

  /**
   * {@code /bg new <名稱> import <世界資料夾> [owner <玩家>]}（主執行緒，03-worlds §7）：把伺服器上已有的世界（相對於伺服器目錄的資料夾）
   * 建成 {@code owner} 的建築世界。只取主世界那一層的 region 與實體檔（26.x 的存檔在 dimensions/minecraft/overworld），
   * 複製出來後和上傳一樣逐個 chunk 檢查、升級；原本的世界不動。世界還載入著時先存檔。管理員才能用，管理方塊照樣保留。
   */
  void importFolder(Sender sender, ServerPlayer owner, String name, WorldConfig config, String folder) throws IOException {
    var base = bg.platform().server().getServerDirectory().toAbsolutePath().normalize();
    var dir = base.resolve(folder).normalize();
    if (!dir.startsWith(base) || !Files.isDirectory(dir)) throw new Workspace.Refused("伺服器目錄裡沒有資料夾 " + folder);
    if (!Files.isDirectory(dir.resolve("region")) && Files.isDirectory(dir.resolve("dimensions/minecraft/overworld/region")))
      dir = dir.resolve("dimensions/minecraft/overworld");
    if (!Files.isDirectory(dir.resolve("region"))) throw new Workspace.Refused(folder + " 裡沒有 region 資料夾，不是世界資料夾");
    bg.worlds().checkCreate(name, owner, true);
    Runnable saved = () -> {};
    for (var level : bg.platform().server().getAllLevels())
      if (bg.platform().levelDirectory(level).toAbsolutePath().normalize().equals(dir)) saved = bg.platform().save(level);
    var source = dir;
    var wait = saved;
    var tmp = bg.platform().repoDir().resolve("uploads").resolve("import-" + UUID.randomUUID());
    msg().send(sender, "world-import-copying", "folder", folder);
    Thread.ofVirtual().name("BuildGit import " + folder).start(() -> {
      try {
        wait.run();
        var raw = tmp.resolve("raw");
        for (var sub : List.of("region", "entities"))
          if (Files.isDirectory(source.resolve(sub))) Workspace.copyTree(source.resolve(sub), raw.resolve(sub), false);
        var staged = tmp.resolve("staged");
        var result = check(raw, staged, true);
        bg.platform().sync(() -> {
          try {
            var ws = bg.worlds().importWorld(name, owner, config, true, staged, "匯入伺服器上的世界 " + folder);
            msg().send(sender, "world-imported", "folder", folder, "world", ws.label(), "chunks", result.chunks(), "dropped", result.dropped());
            if (sender.player() instanceof ServerPlayer p && !p.isRemoved()) bg.realms().enter(p, ws);
          } catch (IOException | RuntimeException e) {
            importFailed(sender, folder, e);
          } finally {
            deleteTree(tmp);
          }
        });
      } catch (IOException | RuntimeException e) {
        deleteTree(tmp);
        bg.platform().sync(() -> importFailed(sender, folder, e));
      }
    });
  }

  private void importFailed(Sender sender, String folder, Throwable e) {
    if (!(e instanceof Workspace.Refused)) bg.platform().log().log(Level.WARNING, "匯入世界 " + folder + " 失敗", e);
    msg().send(sender, "world-import-failed", "folder", folder, "error", String.valueOf(e.getMessage()));
  }

  private void failed(UUID uuid, Pending up, Throwable e) {
    if (!(e instanceof Workspace.Refused)) bg.platform().log().log(Level.WARNING, "上傳單人世界失敗", e);
    var online = bg.platform().server().getPlayerList().getPlayer(uuid);
    if (online != null) msg().send(bg.platform().sender(online), "world-upload-failed", "save", up.save, "error", String.valueOf(e.getMessage()));
  }

  record Result(int chunks, int dropped) {}

  /**
   * 解開上傳的檔案（只取主世界的 region 與實體檔，總量有上限），逐個 chunk 讀、升級到目前的版本、檢查位置，清掉不允許的東西後
   * 寫進 {@code staged}。
   */
  private Result unpack(Path zip, Path raw, Path staged, long max, boolean gameMaster) throws IOException {
    extract(zip, raw, max);
    return check(raw, staged, gameMaster);
  }

  private static void extract(Path zip, Path raw, long max) throws IOException {
    long total = 0;
    try (var in = new ZipInputStream(Files.newInputStream(zip))) {
      var buf = new byte[1 << 16];
      for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
        if (!SyncClient.PACKED.matcher(e.getName()).matches()) continue;
        var to = raw.resolve(e.getName());
        Files.createDirectories(to.getParent());
        try (var out = Files.newOutputStream(to)) {
          for (int n; (n = in.read(buf)) > 0; ) {
            if ((total += n) > max) throw new Workspace.Refused("解開後超過上限");
            out.write(buf, 0, n);
          }
        }
      }
    }
  }

  /** 逐個 chunk 讀 {@code raw} 的 region 與實體檔、升級到目前的版本、檢查位置，清掉不允許的東西後寫進 {@code staged}。 */
  private Result check(Path raw, Path staged, boolean gameMaster) throws IOException {
    var upgrader = bg.workspace().upgrader();
    int current = upgrader.current();
    var masterBlocks = new HashSet<String>();
    for (var b : BuiltInRegistries.BLOCK) if (b instanceof GameMasterBlock) masterBlocks.add(BuiltInRegistries.BLOCK.getKey(b).toString());
    int chunks = 0, dropped = 0;
    for (var sub : List.of("region", "entities")) {
      var dir = raw.resolve(sub);
      if (!Files.isDirectory(dir)) continue;
      boolean blocks = sub.equals("region");
      List<Path> files;
      try (var s = Files.list(dir)) {
        files = s.filter(f -> f.getFileName().toString().endsWith(".mca")).sorted().toList();
      }
      Files.createDirectories(staged.resolve(sub));
      for (var file : files) {
        var parts = file.getFileName().toString().split("\\.");
        int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
        try (var in = new RegionFile(file); var out = new RegionWriter(staged.resolve(sub).resolve(file.getFileName().toString()))) {
          for (int i = 0; i < 1024; i++) {
            int x = rx * 32 + (i & 31), z = rz * 32 + (i >> 5);
            Nbt.Compound c;
            try {
              var bytes = in.readBytes(i);
              if (bytes == null) continue;
              c = Nbt.read(bytes);
            } catch (IOException | RuntimeException e) {
              dropped++;
              continue;
            }
            if (!(c.get("DataVersion") instanceof Integer v) || v < RegionUpgrade.OLDEST) {
              dropped++;
              continue;
            }
            if (v > current) throw new Workspace.Refused("存檔是比伺服器新的 Minecraft 版本存的，請用伺服器的版本（或更舊的）開過再上傳");
            try {
              if (blocks) {
                c = upgrader.chunk(Workspace.OVERWORLD, c);
                // 沒生成完的 chunk 交給新世界的產生器
                if (!(c.get("Status") instanceof String st && (st.equals("minecraft:full") || st.equals("full")))) continue;
                if (!(c.get("xPos") instanceof Integer cx && cx == x && c.get("zPos") instanceof Integer cz && cz == z)
                    || !(gameMaster || cleanBlocks(c, masterBlocks, upgrader))) {
                  dropped++;
                  continue;
                }
                chunks++;
              } else {
                c = upgrader.entityChunk(c);
                if (!(c.get("Position") instanceof int[] pos && pos.length == 2 && pos[0] == x && pos[1] == z)) {
                  dropped++;
                  continue;
                }
                cleanEntities(c, gameMaster);
              }
            } catch (IOException | RuntimeException e) {
              dropped++;
              continue;
            }
            out.write(i, Nbt.write(c));
          }
        }
      }
    }
    if (chunks == 0) throw new Workspace.Refused("世界裡沒有可以用的 chunk");
    return new Result(chunks, dropped);
  }

  /**
   * 不能用管理方塊的人上傳的：指令方塊、結構方塊等換成空氣（調色盤裡換成還沒用到的空氣，免得重複），它們的方塊實體拿掉，
   * 其他方塊實體的點擊指令清掉。三種空氣都已經在同一個 section 時放棄整個 chunk。
   *
   * @return false 時不收這個 chunk
   */
  private static boolean cleanBlocks(Nbt.Compound chunk, Set<String> masterBlocks, dev.twme.buildgit.core.model.Upgrader upgrader) throws IOException {
    var sections = new ArrayList<>();
    for (var o : chunk.list("sections").values()) {
      if (o instanceof Nbt.Compound section && section.get("block_states") instanceof Nbt.Compound states) {
        var palette = new ArrayList<>();
        var used = new HashSet<String>();
        for (var e : states.list("palette").values()) {
          var entry = Nbt.unwrap(e);
          palette.add(entry);
          used.add(upgrader.paletteState(entry));
        }
        boolean changed = false;
        for (int i = 0; i < palette.size(); i++) {
          var state = upgrader.paletteState(palette.get(i));
          int bracket = state.indexOf('[');
          if (!masterBlocks.contains(bracket < 0 ? state : state.substring(0, bracket))) continue;
          var air = List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air").stream().filter(a -> !used.contains(a)).findFirst();
          if (air.isEmpty()) return false;
          used.add(air.get());
          palette.set(i, upgrader.paletteEntry(air.get()));
          changed = true;
        }
        if (changed) states.put("palette", Nbt.list(palette));
      }
      sections.add(o);
    }
    var entities = new ArrayList<>();
    for (var o : chunk.list("block_entities").values())
      if (o instanceof Nbt.Compound be && !masterBlocks.contains(be.string("id")) && !be.string("id").equals("minecraft:command_block"))
        entities.add(withoutClicks(be));
    if (chunk.containsKey("block_entities")) chunk.put("block_entities", Nbt.list(entities));
    return true;
  }

  /** 只留建築用的實體（照 {@code ignored-entities}）；不能用管理方塊的人上傳的拿掉指令方塊礦車（含乘客裡的）。 */
  private void cleanEntities(Nbt.Compound chunk, boolean gameMaster) {
    var kept = new ArrayList<>();
    for (var o : chunk.list("Entities").values())
      if (o instanceof Nbt.Compound e && entityAllowed(e, gameMaster, 0)) kept.add(e);
    chunk.put("Entities", Nbt.list(kept));
  }

  private boolean entityAllowed(Nbt.Compound e, boolean gameMaster, int depth) {
    var id = e.string("id");
    if (depth > 16 || !bg.tracker().isBuildEntity(id) || !gameMaster && id.equals("minecraft:command_block_minecart")) return false;
    for (var o : e.list("Passengers").values())
      if (!(o instanceof Nbt.Compound passenger && entityAllowed(passenger, gameMaster, depth + 1))) return false;
    return true;
  }

  /** 拿掉文字裡的點擊事件（告示牌的點擊指令以管理員權限執行）。{@code push} 送回的方塊實體也用。 */
  static Nbt.Compound withoutClicks(Nbt.Compound c) {
    var out = new Nbt.Compound();
    c.forEach((k, v) -> {
      if (!k.equals("click_event") && !k.equals("clickEvent")) out.put(k, withoutClicks(v));
    });
    return out;
  }

  private static Object withoutClicks(Object v) {
    if (v instanceof Nbt.Compound c) return withoutClicks(c);
    if (v instanceof Nbt.ListTag l && l.type() == 10) return new Nbt.ListTag(10, l.values().stream().map(WorldUpload::withoutClicks).toList());
    return v;
  }

  /** 每秒：放棄等太久的上傳。 */
  void tick() {
    long now = bg.platform().tick();
    for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
      var up = it.next().getValue();
      if (up.processing || now - up.since <= TIMEOUT) continue;
      it.remove();
      purgeLater(up);
    }
  }

  void quit(UUID uuid) {
    var up = pending.get(uuid);
    if (up != null && !up.processing) drop(uuid);
  }

  private void drop(UUID uuid) {
    var up = pending.remove(uuid);
    if (up != null) purgeLater(up);
  }

  /** 已經發了網址的：用戶端可能已經傳了一部分，刪掉 S3 上的檔案。 */
  private void purgeLater(Pending up) {
    if (up.key != null) bg.platform().async(() -> purge(up));
  }

  private void purge(Pending up) {
    try {
      up.s3.purge(up.key);
    } catch (IOException e) {
      bg.platform().log().warning("無法刪掉 S3 上的上傳 " + up.key + "：" + e.getMessage());
    }
  }

  private static String mb(long bytes) {
    return String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0);
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root)) return;
    try (var walk = Files.walk(root)) {
      for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    } catch (IOException e) {
      // 下次上傳前再刪
    }
  }
}
