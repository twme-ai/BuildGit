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
  /** 回收區（03-worlds §5.2）：刪除的世界原地改名後記在這裡，過期才真正刪掉。 */
  public static final String TRASH = "trash.yml";
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

  /** 刪除的世界：原本的登記、刪除的時間、改名前後的資料夾。 */
  record Deleted(Entry entry, long time, List<Moved> moved) {}

  record Moved(Path from, Path to) {}

  private final Platform platform;
  private final Tracker tracker;
  private final Supplier<Settings> settings;
  private final Workspace server;
  /** id → 建築世界的版本庫。 */
  private final Map<String, Workspace> worlds = new LinkedHashMap<>();
  /** id → 回收區裡的世界。 */
  private final Map<String, Deleted> trash = new LinkedHashMap<>();
  /** 正在改名、轉讓、刪除（等世界卸載完）的世界 id：不能進去。 */
  private final Set<String> busy = new HashSet<>();

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

  /** 以 id 找建築世界；沒有時是 null。 */
  Workspace byId(String id) {
    return worlds.get(id);
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
        e = entry(yaml, id, id);
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
    var t = Yaml.load(platform.repoDir().resolve(TRASH));
    for (var id : t.keys())
      try {
        var moved = new ArrayList<Moved>();
        for (int i = 0; t.contains(id + ".moved." + i + ".from"); i++)
          moved.add(new Moved(Path.of(t.getString(id + ".moved." + i + ".from")), Path.of(t.getString(id + ".moved." + i + ".to"))));
        trash.put(id, new Deleted(entry(t, id + ".entry", id), t.getLong(id + ".time", 0), moved));
      } catch (RuntimeException ex) {
        platform.log().warning(TRASH + " 裡的 " + id + " 格式不對，略過：" + ex.getMessage());
      }
  }

  private static Entry entry(Yaml yaml, String key, String id) {
    var e = new Entry(id, yaml.getString(key + ".name"), UUID.fromString(yaml.getString(key + ".owner")), yaml.getString(key + ".owner-name"),
        yaml.getString(key + ".folder"), yaml.getString(key + ".paths.repo"), yaml.getString(key + ".paths.objects"),
        yaml.getString(key + ".paths.genesis"), yaml.getLong(key + ".created", 0));
    if (e.name() == null || e.folder() == null || e.repo() == null || e.objects() == null || e.genesis() == null)
      throw new IllegalArgumentException("缺少欄位");
    return e;
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
    var ownerName = owner.getScoreboardName().toLowerCase(Locale.ROOT);
    checkName(name, owner.getUUID(), null);
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
    var folder = folderName(name, ownerName, uuid, id);
    checkFolder(folder, null);
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

  private String folderName(String name, String ownerName, String owner, String id) {
    return settings.get().playerWorlds().worldName().replace("{name}", name).replace("{owner-name}", ownerName).replace("{owner}", owner)
        .replace("{id}", id);
  }

  /** 名稱合法、不是伺服器世界的名稱、擁有者沒有同名的世界（含回收區裡的）。{@code self} 是改名、轉讓中的世界自己。 */
  private void checkName(String name, UUID owner, String self) throws Workspace.Refused {
    if (!Workspace.BRANCH_NAME.matcher(name).matches()) throw new Workspace.Refused("名稱只能用小寫英文、數字、底線、連字號（1～32 字）：" + name);
    if (name.equalsIgnoreCase(serverWorldName())) throw new Workspace.Refused("這是伺服器世界的名稱");
    for (var e : entries())
      if (!e.id().equals(self) && e.owner().equals(owner) && e.name().equals(name))
        throw new Workspace.Refused((trash.containsKey(e.id()) ? "回收區裡已經有叫 " : "已經有叫 ") + name + " 的建築世界");
  }

  /** 所有登記的世界，含回收區裡的。 */
  private List<Entry> entries() {
    var result = new ArrayList<Entry>();
    for (var ws : worlds.values()) result.add(ws.buildWorld());
    for (var d : trash.values()) result.add(d.entry());
    return result;
  }

  /** 世界資料夾名稱不能和任何現有的世界、草稿、資料夾（含回收區裡的）或 BuildGit 用的開頭撞在一起。{@code self} 是改名中的世界自己。 */
  private void checkFolder(String folder, String self) throws IOException {
    if (!folder.matches("[a-z0-9][a-z0-9_.-]{0,79}")) throw new Workspace.Refused("世界資料夾名稱不合法：" + folder);
    var level = platform.server().getWorldData().getLevelName();
    if (RESERVED.contains(folder) || folder.equals(level) || folder.equals(level + "_nether") || folder.equals(level + "_the_end")
        || folder.startsWith("bg_") || folder.startsWith(Workspace.VIEW_PREFIX))
      throw new Workspace.Refused("不能用 " + folder + " 當世界資料夾的名稱");
    for (var e : entries()) {
      if (e.id().equals(self)) continue;
      var other = e.folder();
      if (folder.equals(other) || folder.startsWith(other + "-") || other.startsWith(folder + "-"))
        throw new Workspace.Refused("世界資料夾 " + folder + " 和建築世界 " + e.label() + (trash.containsKey(e.id()) ? "（在回收區）" : "") + " 的世界或草稿撞名");
    }
    if (platform.findLevel(folder) != null || Files.exists(platform.levelFolder(folder)) || Files.exists(Path.of(folder)))
      throw new Workspace.Refused("已經有叫 " + folder + " 的世界或資料夾");
  }

  private void save() throws IOException {
    var yaml = new Yaml();
    for (var ws : worlds.values()) put(yaml, ws.buildWorld().id(), ws.buildWorld());
    Files.createDirectories(index().getParent());
    yaml.save(index());
    var t = new Yaml();
    for (var d : trash.values()) {
      var id = d.entry().id();
      put(t, id + ".entry", d.entry());
      t.set(id + ".time", d.time());
      for (int i = 0; i < d.moved().size(); i++) {
        t.set(id + ".moved." + i + ".from", d.moved().get(i).from().toString());
        t.set(id + ".moved." + i + ".to", d.moved().get(i).to().toString());
      }
    }
    var file = platform.repoDir().resolve(TRASH);
    if (trash.isEmpty()) Files.deleteIfExists(file);
    else t.save(file);
  }

  private static void put(Yaml yaml, String key, Entry e) {
    yaml.set(key + ".name", e.name());
    yaml.set(key + ".owner", e.owner().toString());
    yaml.set(key + ".owner-name", e.ownerName());
    yaml.set(key + ".folder", e.folder());
    yaml.set(key + ".paths.repo", e.repo());
    yaml.set(key + ".paths.objects", e.objects());
    yaml.set(key + ".paths.genesis", e.genesis());
    yaml.set(key + ".created", e.created());
  }

  // ---- 改名、轉讓、刪除（03-worlds §5.2、§6.1） ----

  /** 要再開一個建築世界的世界（主世界、草稿、檢視）前：同時載入的不能超過 {@code max-loaded}。 */
  void checkLoad() throws Workspace.Refused {
    int max = settings.get().playerWorlds().maxLoaded();
    int loaded = worlds.values().stream().mapToInt(ws -> ws.levelNames().size()).sum();
    if (loaded >= max) throw new Workspace.Refused("伺服器上同時開著的建築世界已經滿了（" + max + " 個），請稍後再試");
  }

  /** 正在改名、轉讓或刪除。 */
  boolean busy(Workspace ws) {
    return ws.buildWorld() != null && busy.contains(ws.buildWorld().id());
  }

  /**
   * 卸載這個世界所有的世界、關閉版本庫，等世界真的關好再完成（主執行緒）：Fabric 的 Fantasy 在之後的刻才卸載，資料檔也在背景寫，
   * 太早改資料夾會被寫回原本的位置。
   */
  private java.util.concurrent.CompletableFuture<Void> shutdown(Workspace ws) throws IOException {
    var names = ws.levelNames();
    ws.shutdown();
    busy.add(ws.buildWorld().id());
    var done = new java.util.concurrent.CompletableFuture<Void>();
    int[] tries = {0};
    Runnable[] check = new Runnable[1];
    check[0] = () -> {
      if (names.stream().allMatch(n -> platform.findLevel(n) == null) || ++tries[0] > 200) platform.later(40, () -> done.complete(null));
      else platform.later(5, check[0]);
    };
    check[0].run();
    return done;
  }

  /** 卸載後在主執行緒做 {@code step}；失敗時以原本的登記重新開啟。 */
  private <T> java.util.concurrent.CompletableFuture<T> afterShutdown(Workspace ws, IoStep<T> step) throws IOException {
    var e = ws.buildWorld();
    return shutdown(ws).thenApply(v -> {
      try {
        return step.run();
      } catch (IOException | RuntimeException ex) {
        if (!trash.containsKey(e.id())) {
          var again = workspace(e);
          try {
            again.open();
          } catch (IOException | RuntimeException ignored) {
            // 開不了時照登記，下次啟動再開
          }
          worlds.put(e.id(), again);
        }
        throw new java.util.concurrent.CompletionException(ex);
      } finally {
        busy.remove(e.id());
      }
    });
  }

  private interface IoStep<T> {
    T run() throws IOException;
  }

  /** 改名（主執行緒）：資料夾照新名稱改，草稿資料夾一起改。裡面的人要先送走。 */
  java.util.concurrent.CompletableFuture<Workspace> rename(Workspace ws, String name) throws IOException {
    var e = ws.buildWorld();
    checkName(name, e.owner(), e.id());
    return relocate(ws, new Entry(e.id(), name, e.owner(), e.ownerName(), folderName(name, e.ownerName(), e.owner().toString(), e.id()),
        e.repo(), e.objects(), e.genesis(), e.created()));
  }

  /** 轉讓（主執行緒）：資料夾名稱有擁有者時跟著改。成員名單由呼叫的人改好。路徑裡的 {@code {owner}} 照舊是建立時的擁有者。 */
  java.util.concurrent.CompletableFuture<Workspace> transfer(Workspace ws, UUID owner, String ownerName) throws IOException {
    var e = ws.buildWorld();
    checkName(e.name(), owner, e.id());
    var lower = ownerName.toLowerCase(Locale.ROOT);
    return relocate(ws, new Entry(e.id(), e.name(), owner, lower, folderName(e.name(), lower, owner.toString(), e.id()),
        e.repo(), e.objects(), e.genesis(), e.created()));
  }

  /** 世界資料夾名稱會不會變（呼叫的人要不要先檢查）。 */
  void checkRelocate(Workspace ws, String name, UUID owner, String ownerName) throws IOException {
    var e = ws.buildWorld();
    checkName(name, owner, e.id());
    var folder = folderName(name, ownerName.toLowerCase(Locale.ROOT), owner.toString(), e.id());
    if (!folder.equals(e.folder())) checkFolder(folder, e.id());
  }

  /** 卸載、改資料夾、換成新的 {@link Workspace}。 */
  private java.util.concurrent.CompletableFuture<Workspace> relocate(Workspace ws, Entry next) throws IOException {
    var e = ws.buildWorld();
    boolean moving = !next.folder().equals(e.folder());
    if (moving) checkFolder(next.folder(), e.id());
    return afterShutdown(ws, () -> {
      if (moving) {
        // 草稿是 <世界資料夾>-<分支>
        for (var name : platform.levelFolders())
          if (name.startsWith(e.folder() + "-")) move(platform.levelFolder(name), platform.levelFolder(next.folder() + name.substring(e.folder().length())));
        move(platform.levelFolder(e.folder()), platform.levelFolder(next.folder()));
      }
      var replaced = workspace(next);
      replaced.open();
      worlds.put(e.id(), replaced);
      save();
      platform.log().info("建築世界 " + e.label() + " 改成 " + next.label() + "（" + next.folder() + "）");
      return replaced;
    });
  }

  private static void move(Path from, Path to) throws IOException {
    if (!Files.exists(from)) return;
    Files.createDirectories(to.getParent());
    Files.move(from, to);
  }

  /**
   * 刪除（主執行緒）：卸載，世界資料夾、草稿資料夾（可能有還沒存的變更）、版本庫、起點世界原地改名為
   * {@code <名稱>.deleted-<時間>} 放進回收區。放在共用位置的物件不動，放在版本庫裡的跟著走。裡面的人要先送走。
   */
  java.util.concurrent.CompletableFuture<Void> delete(Workspace ws) throws IOException {
    var e = ws.buildWorld();
    return afterShutdown(ws, () -> {
      remove(ws, e);
      return null;
    });
  }

  private void remove(Workspace ws, Entry e) throws IOException {
    var all = new ArrayList<>(List.of(platform.levelFolder(e.folder()).toAbsolutePath().normalize(), ws.dir(), ws.genesisRoot()));
    for (var name : platform.levelFolders())
      if (name.startsWith(e.folder() + "-")) all.add(platform.levelFolder(name).toAbsolutePath().normalize());
    // 只改最外層的：版本庫可能在世界資料夾裡、起點世界可能在版本庫裡
    all.sort(Comparator.comparingInt(Path::getNameCount));
    var roots = new ArrayList<Path>();
    for (var p : all) if (roots.stream().noneMatch(p::startsWith)) roots.add(p);
    var stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    var moved = new ArrayList<Moved>();
    for (var from : roots) {
      if (!Files.exists(from)) continue;
      var to = from.resolveSibling(from.getFileName() + ".deleted-" + stamp);
      Files.move(from, to);
      moved.add(new Moved(from, to));
    }
    worlds.remove(e.id());
    trash.put(e.id(), new Deleted(e, System.currentTimeMillis(), moved));
    save();
    platform.log().info("建築世界 " + e.label() + " 已刪除，放進回收區（" + settings.get().playerWorlds().trashDays() + " 天後真正刪掉）");
  }

  /** 回收區裡的世界：自己的（寫名稱），或 {@code 擁有者/名稱}。 */
  Deleted findDeleted(String spec, UUID asker) {
    int slash = spec.indexOf('/');
    for (var d : trash.values()) {
      var e = d.entry();
      if (slash >= 0 ? e.ownerName().equalsIgnoreCase(spec.substring(0, slash)) && e.name().equals(spec.substring(slash + 1))
          : e.owner().equals(asker) && e.name().equals(spec)) return d;
    }
    return null;
  }

  Collection<Deleted> trash() {
    return Collections.unmodifiableCollection(trash.values());
  }

  /** 從回收區找回（主執行緒）：資料夾改回原名。原本的位置已經有東西時拒絕。 */
  Workspace undelete(Deleted d) throws IOException {
    var e = d.entry();
    for (var m : d.moved())
      if (Files.exists(m.from())) throw new Workspace.Refused("原本的位置已經有東西了：" + m.from());
    for (var m : d.moved()) Files.move(m.to(), m.from());
    trash.remove(e.id());
    var ws = workspace(e);
    ws.open();
    worlds.put(e.id(), ws);
    save();
    platform.log().info("建築世界 " + e.label() + " 已從回收區找回");
    return ws;
  }

  /** 啟動時與每天一次：回收區裡過期的真正刪掉。 */
  void purge() {
    long cutoff = System.currentTimeMillis() - settings.get().playerWorlds().trashDays() * 86_400_000L;
    var expired = trash.values().stream().filter(d -> d.time() < cutoff).toList();
    if (expired.isEmpty()) return;
    for (var d : expired) trash.remove(d.entry().id());
    try {
      save();
    } catch (IOException e) {
      platform.log().log(Level.WARNING, "無法更新 " + TRASH, e);
      return;
    }
    platform.async(() -> {
      for (var d : expired) {
        for (var m : d.moved()) deleteTree(m.to());
        platform.log().info("回收區的建築世界 " + d.entry().label() + " 已過期，刪掉了");
      }
    });
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root)) return;
    try (var walk = Files.walk(root)) {
      for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    } catch (IOException e) {
      // 下次再刪
    }
  }
}
