package dev.twme.buildgit.server;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.server.track.Tracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 建築世界的登記表（03-worlds §6.1）：{@code buildgit/worlds.yml} 記著有哪些建築世界、誰的、資料放在哪裡。
 * 伺服器世界的版本庫是第一個，行為和以前相同；每個建築世界各有一個 {@link Workspace}。
 *
 * <p>路徑範本在建立時代入 {@code {id}}、{@code {owner}} 後記進登記表；{@code {server}}、{@code {buildgit}}、
 * {@code {world-folder}}、{@code {repo}} 每次執行時才算，伺服器資料夾搬家、Minecraft 升級搬動世界資料夾後照樣找得到。
 */
public final class BuildWorlds {
  public static final String INDEX = "worlds.yml";
  /** 不能當世界資料夾的名稱：伺服器根目錄常見的資料夾（Paper 1.21.11 的世界放在根目錄）與原版維度資料夾。 */
  private static final Set<String> RESERVED = Set.of("plugins", "logs", "config", "mods", "libraries", "cache", "versions", "crash-reports",
      "buildgit", "dimensions", "datapacks", "overworld", "the_nether", "the_end", "region", "entities", "poi", "data", "playerdata");
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * @param repo 版本庫位置的範本（已代入 {@code {id}}、{@code {owner}}），{@code objects}、{@code genesis} 同
   * @param folder 世界資料夾名稱，同時是世界的名稱
   */
  public record Entry(String id, String name, UUID owner, String ownerName, String folder, String repo, String objects, String genesis,
      long created) {
    /** 給玩家看的「擁有者/名稱」。 */
    public String label() {
      return ownerName + "/" + name;
    }
  }

  private final Platform platform;
  private final Tracker tracker;
  private final Supplier<Settings> settings;
  private final Workspace server;
  /** id → 建築世界的版本庫。 */
  private final Map<String, Workspace> worlds = new LinkedHashMap<>();

  BuildWorlds(Platform platform, Tracker tracker, Supplier<Settings> settings) {
    this.platform = platform;
    this.tracker = tracker;
    this.settings = settings;
    this.server = new Workspace(platform, tracker, this);
  }

  private Path index() {
    return platform.repoDir().resolve(INDEX);
  }

  /** 伺服器世界的版本庫。 */
  public Workspace server() {
    return server;
  }

  /** 伺服器世界在最前面。 */
  public List<Workspace> all() {
    var result = new ArrayList<Workspace>(worlds.size() + 1);
    result.add(server);
    result.addAll(worlds.values());
    return result;
  }

  public Collection<Workspace> buildWorlds() {
    return Collections.unmodifiableCollection(worlds.values());
  }

  /** 世界屬於哪個版本庫；不是建築世界的（含不受版本控制的世界）一律是伺服器世界的版本庫。 */
  public Workspace of(ServerLevel level) {
    for (var ws : worlds.values()) if (ws.owns(level)) return ws;
    return server;
  }

  /** 伺服器世界在指令裡的名稱。 */
  String serverWorldName() {
    var name = settings.get().playerWorlds().serverWorldName();
    return name.isEmpty() ? platform.server().getWorldData().getLevelName() : name;
  }

  /** 建築世界的草稿世界（可以從版本重建，世界資料夾的備份不必帶）。 */
  boolean isDraftFolder(String levelName) {
    for (var ws : worlds.values()) if (levelName.startsWith(ws.buildWorld().folder() + "-")) return true;
    return false;
  }

  /** 這個世界名稱是建築世界或它的草稿（伺服器世界的版本庫不能把它當成自己的）。 */
  boolean claims(String levelName) {
    for (var ws : worlds.values()) {
      var folder = ws.buildWorld().folder();
      if (levelName.equals(folder) || levelName.startsWith(folder + "-")) return true;
    }
    return false;
  }

  /**
   * 找世界：伺服器世界的名稱、{@code 擁有者/名稱}，或只寫名稱（先找 {@code asker} 自己的，再找唯一同名的）。找不到時為 null。
   */
  Workspace find(String spec, UUID asker) {
    if (spec.equalsIgnoreCase(serverWorldName())) return server;
    int slash = spec.indexOf('/');
    Workspace only = null;
    int matches = 0;
    for (var ws : worlds.values()) {
      var e = ws.buildWorld();
      if (slash >= 0) {
        if (e.ownerName().equalsIgnoreCase(spec.substring(0, slash)) && e.name().equals(spec.substring(slash + 1))) return ws;
        continue;
      }
      if (!e.name().equals(spec)) continue;
      if (e.owner().equals(asker)) return ws;
      only = ws;
      matches++;
    }
    return matches == 1 ? only : null;
  }

  // ---- 開啟 ----

  /** 開啟所有建築世界的版本庫（主執行緒）。世界本身等有人進去才載入。 */
  void open() {
    var yaml = Yaml.load(index());
    for (var id : yaml.keys()) {
      Entry e;
      try {
        e = new Entry(id, yaml.getString(id + ".name"), UUID.fromString(yaml.getString(id + ".owner")), yaml.getString(id + ".owner-name"),
            yaml.getString(id + ".folder"), yaml.getString(id + ".paths.repo"), yaml.getString(id + ".paths.objects"),
            yaml.getString(id + ".paths.genesis"), yaml.getLong(id + ".created", 0));
        if (e.name() == null || e.folder() == null || e.repo() == null || e.objects() == null || e.genesis() == null)
          throw new IllegalArgumentException("缺少欄位");
      } catch (RuntimeException ex) {
        platform.log().warning(INDEX + " 裡的建築世界 " + id + " 格式不對，略過：" + ex.getMessage());
        continue;
      }
      var ws = workspace(e);
      try {
        ws.open();
        if (!ws.isOpen()) platform.log().warning("找不到建築世界 " + e.label() + " 的版本庫：" + path(e.repo(), e, null));
      } catch (IOException | RuntimeException ex) {
        platform.log().log(Level.SEVERE, "無法開啟建築世界 " + e.label(), ex);
      }
      worlds.put(id, ws);
    }
    if (!worlds.isEmpty()) platform.log().info("建築世界：" + worlds.size() + " 個");
  }

  void close() {
    for (var ws : worlds.values()) ws.close();
    worlds.clear();
  }

  private Workspace workspace(Entry e) {
    var repo = path(e.repo(), e, null);
    return new Workspace(platform, tracker, this, e, repo, path(e.objects(), e, repo), path(e.genesis(), e, repo));
  }

  /** 把執行時才算的佔位符代進去。 */
  private Path path(String template, Entry e, Path repo) {
    var s = template.replace("{server}", Path.of("").toAbsolutePath().toString())
        .replace("{buildgit}", platform.repoDir().toAbsolutePath().toString())
        .replace("{world-folder}", platform.levelFolder(e.folder()).toAbsolutePath().toString());
    if (repo != null) s = s.replace("{repo}", repo.toString());
    return Path.of(s).toAbsolutePath().normalize();
  }

  // ---- 建立 ----

  /**
   * 建立建築世界（主執行緒）：登記、建立版本庫、{@code repo.yml} 與空的起點世界。世界本身由呼叫的人 {@link Workspace#openMain} 載入。
   * 沒給名稱時自動編號（{@code world-1}、{@code world-2}…）。沒開放、超過上限（管理員除外），或名稱、算出來的資料夾、
   * 路徑有衝突時以 {@link Workspace.Refused} 拒絕。
   */
  Workspace create(String name, ServerPlayer owner, WorldConfig config, boolean admin) throws IOException {
    var pw = settings.get().playerWorlds();
    if (!admin) {
      if (!pw.enabled()) throw new Workspace.Refused("這台伺服器沒有開放玩家建立建築世界");
      long owned = worlds.values().stream().filter(ws -> ws.buildWorld().owner().equals(owner.getUUID())).count();
      if (owned >= pw.maxPerPlayer()) throw new Workspace.Refused("每人最多 " + pw.maxPerPlayer() + " 個建築世界");
    }
    if (name == null)
      for (int i = 1; name == null; i++) {
        var candidate = "world-" + i;
        if (worlds.values().stream().noneMatch(ws -> ws.buildWorld().owner().equals(owner.getUUID()) && ws.buildWorld().name().equals(candidate)))
          name = candidate;
      }
    if (!Workspace.BRANCH_NAME.matcher(name).matches()) throw new Workspace.Refused("名稱只能用小寫英文、數字、底線、連字號（1～32 字）：" + name);
    if (name.equalsIgnoreCase(serverWorldName())) throw new Workspace.Refused("這是伺服器世界的名稱");
    var ownerName = owner.getScoreboardName().toLowerCase(Locale.ROOT);
    for (var ws : worlds.values())
      if (ws.buildWorld().owner().equals(owner.getUUID()) && ws.buildWorld().name().equals(name))
        throw new Workspace.Refused("你已經有叫 " + name + " 的建築世界");
    if (!pw.worldName().contains("{name}")) throw misconfigured("player-worlds.world-name 必須包含 {name}");
    if (!pw.repo().contains("{id}") && !pw.repo().contains("{world-folder}"))
      throw misconfigured("player-worlds.paths.repo 必須包含 {id} 或 {world-folder}，否則每個建築世界會用到同一個版本庫");
    if (!pw.genesis().contains("{id}") && !pw.genesis().contains("{world-folder}") && !pw.genesis().contains("{repo}"))
      throw misconfigured("player-worlds.paths.genesis 必須包含 {id}、{world-folder} 或 {repo}");
    String id;
    var bytes = new byte[4];
    do {
      RANDOM.nextBytes(bytes);
      id = HexFormat.of().formatHex(bytes);
    } while (worlds.containsKey(id));
    var uuid = owner.getUUID().toString();
    var folder = pw.worldName().replace("{name}", name).replace("{owner-name}", ownerName).replace("{owner}", uuid).replace("{id}", id);
    checkFolder(folder);
    var e = new Entry(id, name, owner.getUUID(), ownerName, folder, pw.repo().replace("{id}", id).replace("{owner}", uuid),
        pw.objects().replace("{id}", id).replace("{owner}", uuid), pw.genesis().replace("{id}", id).replace("{owner}", uuid),
        System.currentTimeMillis());
    var ws = workspace(e);
    if (Repository.exists(ws.dir()) || Files.exists(ws.genesisRoot()))
      throw new Workspace.Refused("這個建築世界的資料夾已經存在（" + ws.dir() + "）");
    ws.create(tracker.author(owner), config);
    worlds.put(id, ws);
    save();
    platform.log().info(owner.getScoreboardName() + " 建立了建築世界 " + e.label() + "（" + folder + "）");
    return ws;
  }

  private Workspace.Refused misconfigured(String problem) {
    platform.log().warning("config.yml 的建築世界設定有誤：" + problem);
    return new Workspace.Refused("伺服器的建築世界設定有誤，請管理員看主控台");
  }

  /** 世界資料夾名稱不能和任何現有的世界、草稿、資料夾或 BuildGit 用的開頭撞在一起。 */
  private void checkFolder(String folder) throws IOException {
    if (!folder.matches("[a-z0-9][a-z0-9_.-]{0,79}")) throw new Workspace.Refused("世界資料夾名稱不合法：" + folder);
    var level = platform.server().getWorldData().getLevelName();
    if (RESERVED.contains(folder) || folder.equals(level) || folder.equals(level + "_nether") || folder.equals(level + "_the_end")
        || folder.startsWith("bg_") || folder.startsWith(Workspace.VIEW_PREFIX))
      throw new Workspace.Refused("不能用 " + folder + " 當世界資料夾的名稱");
    for (var ws : worlds.values()) {
      var other = ws.buildWorld().folder();
      if (folder.equals(other) || folder.startsWith(other + "-") || other.startsWith(folder + "-"))
        throw new Workspace.Refused("世界資料夾 " + folder + " 和建築世界 " + ws.label() + " 的世界或草稿撞名");
    }
    if (platform.findLevel(folder) != null || Files.exists(platform.levelFolder(folder)) || Files.exists(Path.of(folder)))
      throw new Workspace.Refused("已經有叫 " + folder + " 的世界或資料夾");
  }

  private void save() throws IOException {
    var yaml = new Yaml();
    for (var ws : worlds.values()) {
      var e = ws.buildWorld();
      yaml.set(e.id() + ".name", e.name());
      yaml.set(e.id() + ".owner", e.owner().toString());
      yaml.set(e.id() + ".owner-name", e.ownerName());
      yaml.set(e.id() + ".folder", e.folder());
      yaml.set(e.id() + ".paths.repo", e.repo());
      yaml.set(e.id() + ".paths.objects", e.objects());
      yaml.set(e.id() + ".paths.genesis", e.genesis());
      yaml.set(e.id() + ".created", e.created());
    }
    Files.createDirectories(index().getParent());
    yaml.save(index());
  }
}
