package dev.twme.buildgit.server;

import dev.twme.buildgit.core.model.*;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.sync.Bundle;
import dev.twme.buildgit.core.sync.Frame;
import dev.twme.buildgit.server.nms.LiveReader;
import dev.twme.buildgit.server.nms.LiveWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * 與單人世界同步，伺服器這一邊（設計 §6.7）：{@code pull} 把範圍送到玩家的用戶端，
 * {@code push} 收回改過的範圍，和當初取出的存檔比較後存成草稿，之後照一般流程併回。
 * 用戶端連上時會列出改過、還沒送回的範圍，這裡附上送回的按鈕。
 *
 * <p>建築世界也一樣（03-worlds §2.1）：取出紀錄記著是哪個世界，送回時存成那個世界的草稿，
 * 權限照那個世界的角色（建造者以上），不看玩家現在在哪裡。
 *
 * <p>上傳的內容不可信任，資源用量都有上限：只收伺服器要求過的上傳、壓縮後與解壓後的大小、
 * 實體數、等待時間，而且每位玩家同時只能有一個 pull 或 push 在進行。
 */
final class Sync {
  /** 上傳的上限（壓縮後）。 */
  private static final long MAX_UPLOAD = 16L * 1024 * 1024;
  /** 送回的範圍最多幾個實體。 */
  private static final int MAX_ENTITIES = 10_000;
  /** 要求上傳後多久沒收齊就放棄（遊戲刻）。 */
  private static final long UPLOAD_TIMEOUT = 2 * 60 * 20;
  /** 每刻送出的段數（每段 512 KB）。 */
  private static final int PARTS_PER_TICK = 1;

  private final BuildGit bg;
  /** 等待用戶端上傳的玩家。 */
  private final Map<UUID, Upload> uploads = new HashMap<>();
  /** 正在 pull 或 push 的玩家（主執行緒）。 */
  private final Set<UUID> busy = new HashSet<>();

  private record Upload(Workspace ws, String name, String draft, Frame.Assembler parts, long since) {}

  Sync(BuildGit bg) {
    this.bg = bg;
  }

  /** 取出紀錄放在伺服器世界的版本庫裡（範圍名稱是每位玩家一份，不分世界）。 */
  private Path syncDir() {
    return bg.workspace().dir().resolve("sync");
  }

  private Messages msg() {
    return bg.messages();
  }

  private Sender s(ServerPlayer p) {
    return bg.platform().sender(p);
  }

  private ServerPlayer online(UUID uuid) {
    return bg.platform().server().getPlayerList().getPlayer(uuid);
  }

  /** 取出紀錄：分支、存檔、維度與範圍。push 只接受同一個範圍。 */
  private Path record(UUID player, String name) {
    return syncDir().resolve(player.toString()).resolve(name + ".yml");
  }

  /** 玩家取出過的範圍名稱。 */
  List<String> names(ServerPlayer p) {
    var dir = syncDir().resolve(p.getUUID().toString());
    var result = new ArrayList<String>();
    try (var files = Files.list(dir)) {
      files.forEach(f -> {
        var n = f.getFileName().toString();
        if (n.endsWith(".yml")) result.add(n.substring(0, n.length() - 4));
      });
    } catch (IOException e) {
      // 還沒取出過
    }
    Collections.sort(result);
    return result;
  }

  /** 沒用過的範圍名稱：{@code base}，用過就加上 -2、-3…（用過的名稱可能還有改到一半的範圍）。 */
  String freeName(ServerPlayer p, String base) {
    if (base.length() > 28) base = base.substring(0, 28);
    var used = new HashSet<>(names(p));
    if (!used.contains(base)) return base;
    for (int i = 2; ; i++) if (!used.contains(base + "-" + i)) return base + "-" + i;
  }

  // ---- pull ----

  /** 把分支最新存檔在範圍內的內容送給玩家（呼叫前已存檔）。 */
  void pull(ServerPlayer p, Workspace ws, String name, String branch, String dimension, Box box) {
    var uuid = p.getUUID();
    if (!begin(p)) return;
    msg().send(s(p), "sync-pulling", "name", name);
    ws.async(() -> {
      try {
        ws.awaitGenesis();
        var base = ws.repo().head(branch);
        var data = capture(ws, name, dimension, box, base).encode();
        var y = new Yaml();
        if (ws.buildWorld() != null) y.set("world", ws.buildWorld().id());
        y.set("branch", branch);
        y.set("base", base.hex());
        y.set("dimension", dimension);
        y.set("box", List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        y.set("pulled", System.currentTimeMillis());
        var file = record(uuid, name);
        Files.createDirectories(file.getParent());
        y.save(file);
        ws.sync(() -> send(uuid, Frame.split(Frame.BUNDLE, name, data, Frame.SERVER_PART), () -> {
          busy.remove(uuid);
          var player = online(uuid);
          if (player == null) return;
          msg().send(s(player), "sync-pulled", "name", name, "kb", (data.length + 1023) / 1024);
          // 用戶端的 /bgc 指令：離開伺服器、在單人世界打開
          s(player).send(msg().get("sync-open-button").clickEvent(ClickEvent.runCommand("/bgc open " + name)));
        }));
      } catch (IOException | RuntimeException e) {
        ws.sync(() -> failed(uuid, e));
      }
    });
  }

  private Bundle capture(Workspace ws, String name, String dimension, Box box, ObjectId commit) throws IOException {
    var tracker = bg.tracker();
    return Bundle.capture(name, dimension, box, LiveReader.dataVersion(), commit.hex(), ws.repo().reader(commit),
        ws.genesis(), ws.genesis().entities(dimension), e -> tracker.isBuildEntity(e.type()));
  }

  /** 分批送出，避免一次塞滿連線。 */
  private void send(UUID uuid, List<Frame> frames, Runnable done) {
    var p = online(uuid);
    if (p == null) {
      busy.remove(uuid);
      return;
    }
    int n = Math.min(PARTS_PER_TICK, frames.size());
    for (int i = 0; i < n; i++) bg.platform().sendSync(p, frames.get(i).encode());
    if (n == frames.size()) done.run();
    else bg.platform().later(1, () -> send(uuid, frames.subList(n, frames.size()), done));
  }

  // ---- push ----

  /**
   * 請用戶端送回改過的範圍，收齊後存成取出的那個世界的草稿 {@code draft}；null 時用範圍名稱，
   * 已經有這個草稿就加上編號（同一個範圍可能已經送回過）。
   */
  void push(ServerPlayer p, String name, String draft) throws IOException {
    if (busy.contains(p.getUUID())) {
      msg().send(s(p), "sync-busy");
      return;
    }
    if (!Files.exists(record(p.getUUID(), name))) {
      msg().send(s(p), "sync-unknown", "name", name);
      return;
    }
    var ws = target(p, Yaml.load(record(p.getUUID(), name)));
    if (draft == null) {
      draft = name;
      for (int i = 2; ws.branches().containsKey(draft); i++) draft = name + "-" + i;
    }
    if (draft.equals(Repository.MAIN) || Workspace.isScratch(draft) || !Workspace.BRANCH_NAME.matcher(draft).matches()) {
      msg().send(s(p), "branch-bad-name");
      return;
    }
    if (ws.branches().containsKey(draft)) {
      msg().send(s(p), "sync-draft-exists", "name", draft);
      return;
    }
    uploads.put(p.getUUID(), new Upload(ws, name, draft, new Frame.Assembler(name, MAX_UPLOAD), bg.platform().tick()));
    bg.platform().sendSync(p, new Frame(Frame.PUSH, name, 0, 0, new byte[0]).encode());
    msg().send(s(p), "sync-push-waiting", "name", name);
  }

  /** 用戶端送來的封包（主執行緒）。 */
  void receive(ServerPlayer p, byte[] bytes) {
    Frame f;
    try {
      f = Frame.decode(bytes);
    } catch (IOException e) {
      return;
    }
    if (f.type() == Frame.EDITED) {
      edited(p, new String(f.data(), StandardCharsets.UTF_8));
      return;
    }
    if (f.type() >= Frame.IMPORT_READY && f.type() <= Frame.IMPORT_ERROR) {
      bg.worldUpload().receive(p, f);
      return;
    }
    var up = uploads.get(p.getUUID());
    if (up == null || !up.name().equals(f.name())) return;
    if (f.type() == Frame.ERROR) {
      uploads.remove(p.getUUID());
      boolean missing = new String(f.data(), StandardCharsets.UTF_8).equals("missing");
      msg().send(s(p), missing ? "sync-push-missing" : "sync-push-client-failed", "name", f.name());
      return;
    }
    if (f.type() != Frame.UPLOAD) return;
    byte[] data;
    try {
      data = up.parts().add(f);
    } catch (IOException e) {
      // 用戶端送來的資料有問題：只告訴玩家，不寫堆疊（避免被拿來洗記錄）
      uploads.remove(p.getUUID());
      failed(p.getUUID(), new Workspace.Refused(e.getMessage()));
      return;
    }
    if (data == null) return;
    uploads.remove(p.getUUID());
    if (begin(p)) accept(p, up, data);
  }

  /**
   * 送回的目標：取出紀錄記的世界。建築世界要是那裡的建造者以上（或管理員），不看現在在哪裡；
   * 伺服器世界要在伺服器世界裡、有 {@code buildgit.push}（在建築世界裡權限是那個世界的角色）。
   */
  private Workspace target(ServerPlayer p, Yaml record) throws Workspace.Refused {
    var id = record.getString("world");
    if (id == null) {
      if (!bg.realms().inServerWorld(p.level())) throw new Workspace.Refused("這個範圍是從伺服器世界取出的：請先回伺服器世界（/bg home）再送回");
      if (!bg.platform().hasPermission(p, "buildgit.push")) throw new Workspace.Refused("你沒有送回伺服器世界的權限");
      return bg.workspace();
    }
    var ws = bg.worlds().byId(id);
    if (ws == null || !ws.isOpen()) throw new Workspace.Refused("取出這個範圍的建築世界已經不在了（被刪除，或正在改名、還原）");
    if (bg.worlds().busy(ws)) throw new Workspace.Refused("建築世界 " + ws.label() + " 正在處理其他事，請稍後再試");
    var role = ws.role(p.getUUID());
    if (!bg.realms().admin(p) && (role == null || role == WorldConfig.Role.VISITOR))
      throw new Workspace.Refused("你在建築世界 " + ws.label() + " 不是建造者以上，不能送回（可以用 /bg fork 複製一份自己的）");
    return ws;
  }

  /** 用戶端連上時列出的改過範圍：這台伺服器取出過、現在能送回的，附上送回的按鈕。 */
  private void edited(ServerPlayer p, String list) {
    var known = new HashSet<>(names(p));
    var found = Arrays.stream(list.split("\n")).limit(64).filter(known::contains).filter(n -> {
      try {
        target(p, Yaml.load(record(p.getUUID(), n)));
        return true;
      } catch (IOException e) {
        return false;
      }
    }).toList();
    if (found.isEmpty()) return;
    msg().send(s(p), "sync-edited", "count", found.size());
    Component line = Component.empty();
    for (var n : found)
      line = line.append(msg().get("sync-push-button", "name", n).clickEvent(ClickEvent.runCommand("/bg push " + n))).append(Component.text(" "));
    s(p).send(line);
  }

  /** 每秒：放棄等太久的上傳，釋放收到一半的資料。 */
  void tick() {
    long now = bg.platform().tick();
    uploads.values().removeIf(u -> now - u.since() > UPLOAD_TIMEOUT);
  }

  void quit(UUID uuid) {
    uploads.remove(uuid);
  }

  private boolean begin(ServerPlayer p) {
    if (busy.add(p.getUUID())) return true;
    msg().send(s(p), "sync-busy");
    return false;
  }

  private void accept(ServerPlayer p, Upload up, byte[] data) {
    var uuid = p.getUUID();
    var author = bg.tracker().author(p);
    boolean gameMaster = p.canUseGameMasterBlocks();
    var registries = p.level().registryAccess();
    var record = Yaml.load(record(uuid, up.name()));
    var ws = up.ws();
    msg().send(s(p), "sync-push-received", "name", up.name());
    ws.async(() -> {
      try {
        String branch = record.getString("branch"), dimension = record.getString("dimension");
        var base = ObjectId.parse(record.getString("base"));
        var b = record.getIntegerList("box");
        var box = new Box(b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5));
        Bundle edited;
        try {
          edited = Bundle.decode(data, ws.upgrader(), Bundle.maxRaw(box));
        } catch (IOException | RuntimeException e) {
          throw new Workspace.Refused("送回的資料無效：" + e.getMessage());
        }
        if (!edited.dimension().equals(dimension) || !edited.box().equals(box))
          throw new Workspace.Refused("送回的範圍與取出時不同，請重新 /bg pull");
        if (edited.entities().size() > MAX_ENTITIES) throw new Workspace.Refused("實體太多（最多 " + MAX_ENTITIES + " 個）");
        ws.awaitGenesis();
        var original = capture(ws, up.name(), dimension, box, base);

        // 不接受的格子與實體維持取出時的樣子
        int dropped = 0;
        var cells = edited.cells().clone();
        var verdict = new HashMap<Cell, Boolean>();
        for (int i = 0; i < cells.length; i++)
          if (!verdict.computeIfAbsent(cells[i], c -> LiveWriter.allowed(registries, c, gameMaster))) {
            cells[i] = original.cells()[i];
            dropped++;
          }
        // 不能用管理方塊的人：方塊實體的點擊事件清掉（告示牌的點擊指令以管理員權限執行）
        if (!gameMaster)
          for (int i = 0; i < cells.length; i++) {
            var be = cells[i].blockEntity();
            // 取出時就有的（例如管理員做的告示牌）不動
            if (be == null || cells[i].state().equals(original.cells()[i].state()) && Arrays.equals(be, original.cells()[i].blockEntity())) continue;
            var nbt = Nbt.read(be);
            var cleaned = WorldUpload.withoutClicks(nbt);
            if (!cleaned.equals(nbt)) cells[i] = new Cell(cells[i].state(), Nbt.write(cleaned));
          }
        var ours = original.entityMap().keySet();
        var overlay = ws.repo().reader(base).entities(dimension);
        var entities = new ArrayList<EntityState>();
        for (var e : edited.entities()) {
          var type = e.type();
          boolean ok = bg.tracker().isBuildEntity(type) && Bundle.inside(box, e)
              && (gameMaster || !type.equals("minecraft:command_block_minecart"))
              // 範圍外已有同一個 UUID 的實體：不能借此改到範圍外
              && (ours.contains(e.uuid()) || !exists(ws, overlay, dimension, e.uuid()));
          if (ok) entities.add(e);
          else dropped++;
        }
        var cleaned = new Bundle(up.name(), dimension, box, edited.dataVersion(), base.hex(), cells, entities);
        var marks = cleaned.marks(author, ours);
        int droppedCount = dropped;
        ws.sync(() -> commit(uuid, up, branch, base, cleaned, marks, author, droppedCount));
      } catch (IOException | RuntimeException e) {
        ws.sync(() -> failed(uuid, e));
      }
    });
  }

  private static boolean exists(Workspace ws, Map<UUID, Optional<EntityState>> overlay, String dimension, UUID uuid) throws IOException {
    if (overlay.containsKey(uuid)) return overlay.get(uuid).isPresent();
    return ws.genesis().readEntity(dimension, uuid).isPresent();
  }

  private void commit(UUID uuid, Upload up, String from, ObjectId base, Bundle edited, dev.twme.buildgit.core.track.Marks marks,
      Author author, int dropped) {
    var ws = up.ws();
    try {
      ws.createBranch(up.draft(), from, base, false, edited.dimension());
    } catch (IOException e) {
      failed(uuid, e);
      return;
    }
    ws.async(() -> {
      try {
        var repo = ws.repo();
        var id = repo.commit(new Repository.CommitRequest(up.draft(), base, marks, edited.source(), ws.genesis(), author,
            System.currentTimeMillis(), "單人世界：" + up.name(), 0, new TreeMap<>()));
        var changes = id.isEmpty() ? null : repo.changeset(repo.commit(id.get()).changeset());
        ws.sync(() -> {
          busy.remove(uuid);
          var p = online(uuid);
          // 用戶端刪掉改過的範圍檔，下次連上就不再提示
          if (p != null) bg.platform().sendSync(p, new Frame(Frame.DONE, up.name(), 0, 0, new byte[0]).encode());
          if (changes == null) {
            deleteDraft(ws, up.draft());
            if (p != null) msg().send(s(p), "sync-push-nochange", "name", up.name());
            return;
          }
          if (p == null) return;
          msg().send(s(p), ws.buildWorld() == null ? "sync-pushed" : "sync-pushed-world", "name", up.name(), "draft", up.draft(), "world", ws.label(),
              "cells", changes.cells().size(), "entities", changes.entities().size());
          if (dropped > 0) msg().send(s(p), "sync-push-dropped", "count", dropped);
          // 在別的世界時要先進去才能 /bg go
          if (ws == bg.worlds().of(p.level()))
            s(p).send(msg().get("sync-go-button", "draft", up.draft()).clickEvent(ClickEvent.runCommand("/bg go " + up.draft())));
          else s(p).send(msg().get("sync-open-world-button", "world", ws.label()).clickEvent(ClickEvent.runCommand("/bg open " + ws.label())));
        });
      } catch (IOException | RuntimeException e) {
        ws.sync(() -> {
          deleteDraft(ws, up.draft());
          failed(uuid, e);
        });
      }
    });
  }

  private void deleteDraft(Workspace ws, String name) {
    try {
      ws.deleteBranch(name, true);
    } catch (IOException e) {
      bg.platform().log().log(Level.WARNING, "無法刪除草稿 " + name, e);
    }
  }

  private void failed(UUID uuid, Throwable e) {
    busy.remove(uuid);
    if (e instanceof java.util.concurrent.CompletionException c && c.getCause() != null) e = c.getCause();
    if (!(e instanceof Workspace.Refused)) bg.platform().log().log(Level.WARNING, "同步失敗", e);
    var p = online(uuid);
    if (p != null) msg().send(s(p), "sync-failed", "error", String.valueOf(e.getMessage()));
  }
}
