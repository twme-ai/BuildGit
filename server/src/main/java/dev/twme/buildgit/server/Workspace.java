package dev.twme.buildgit.server;

import dev.twme.buildgit.core.anvil.AnvilSource;
import dev.twme.buildgit.core.anvil.RegionSynthesizer;
import dev.twme.buildgit.core.anvil.RegionUpgrade;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.overlay.OverlayReader;
import dev.twme.buildgit.core.overlay.ThreeWayMerge;
import dev.twme.buildgit.core.overlay.TreeDiff;
import dev.twme.buildgit.core.repo.Commit;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.RefStore;
import dev.twme.buildgit.server.nms.DataUpgrader;
import dev.twme.buildgit.server.nms.LiveReader;
import dev.twme.buildgit.server.nms.LiveWriter;
import dev.twme.buildgit.server.track.Tracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 一個版本庫（設計 §2.1）與每個分支的工作區與世界。伺服器世界的版本庫是 {@code <伺服器根目錄>/buildgit/}；
 * 每個建築世界（03-worlds）也各有一個，由 {@link BuildWorlds} 建立。
 *
 * <p>建築世界只有一個維度，在版本裡固定叫 {@code minecraft:overworld}；它的主世界也由 BuildGit 建立，
 * 世界資料夾不見時照樣以起點世界＋最新存檔合成。草稿是 {@code <世界資料夾>-<分支>}，檢視是 {@code bgview_<世界資料夾>_<版本>}。
 *
 * <p>BuildGit 建立的世界都以「起點世界＋某個覆寫表」在第一次開啟 region 檔時合成（§4.2）：
 * <ul>
 *   <li>分支世界 {@code bg_<分支>}：分支最新存檔，可編輯、受追蹤；
 *   <li>檢視世界 {@code bgview_<版本>}：某個版本，唯讀，最後一位玩家離開後刪除（§6.5）；
 *   <li>衝突整合用的暫時分支 {@code merge-<id>} 也是分支世界（§5.3）。
 * </ul>
 */
public final class Workspace {
  /** 草稿名稱同時用在世界名稱，所以只允許小寫英數、底線與連字號。 */
  public static final Pattern BRANCH_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
  /** 衝突整合的暫時分支；玩家不能用這個開頭命名草稿。 */
  public static final String MERGE_PREFIX = "merge-";
  private static final String WORLD_PREFIX = "bg_";
  static final String VIEW_PREFIX = "bgview_";

  private final Platform platform;
  private final Tracker tracker;
  /** 大量系統寫入分刻進行。 */
  final TickWrites ticked;
  /** 舊版 Minecraft 寫下的歷史與起點世界在讀取時升級（設計 §4.6）。 */
  private final DataUpgrader upgrader;
  private final Path dir;
  private final Path objectsDir;
  private final Path genesisRoot;
  /** 建築世界；伺服器世界的版本庫為 null。 */
  private final BuildWorlds.Entry world;
  private final BuildWorlds registry;
  /** 建築世界在版本中唯一的維度。 */
  static final String OVERWORLD = "minecraft:overworld";
  private static final String GENESIS_POINTER = "genesis-dir";
  private Repository repo;
  private AnvilSource genesis;
  private GenesisRecorder genesisRecorder;
  /** 進行中的起點世界升級（{@code /bg genesis upgrade}）；關閉時清掉，排著的工作就不再做。 */
  private volatile GenesisUpgrade upgrade;
  /** 維度 → 背景複製中的起點世界（{@code /bg init}）。 */
  private final Map<String, GenesisCopy> copies = new ConcurrentHashMap<>();
  private MergeSessions merges;
  private final Map<String, Worktree> worktrees = new LinkedHashMap<>();
  /** 世界 → 所屬工作區。 */
  private final Map<ServerLevel, Worktree> owners = new HashMap<>();
  /** BuildGit 建立的世界 → 內容來源（新生成 chunk 補覆寫時用）。 */
  private final Map<ServerLevel, Pending> sources = new ConcurrentHashMap<>();
  /** 檢視世界 → 版本。 */
  private final Map<ServerLevel, View> views = new HashMap<>();
  private final Map<String, RegionSynthesizer> synthesizers = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, RegionSynthesizer> eldest) {
      return size() > 24;
    }
  };

  /** 合成時的覆寫表根節點；分支世界每次讀到最新存檔。 */
  interface TreeSource {
    ObjectId tree() throws IOException;
  }

  private record Pending(String dimension, TreeSource tree) {}

  public record View(ObjectId commit, String dimension) {}

  /** 伺服器世界的版本庫。 */
  Workspace(Platform platform, Tracker tracker, BuildWorlds registry) {
    this.platform = platform;
    this.tracker = tracker;
    this.registry = registry;
    this.world = null;
    this.upgrader = new DataUpgrader(platform::server, platform::dataFixDimension);
    this.ticked = new TickWrites(platform, tracker);
    this.dir = platform.repoDir();
    this.objectsDir = dir.resolve("objects");
    this.genesisRoot = locateGenesis();
  }

  /** 建築世界的版本庫；路徑由登記表算好。 */
  Workspace(Platform platform, Tracker tracker, BuildWorlds registry, BuildWorlds.Entry world, Path dir, Path objectsDir, Path genesisRoot) {
    this.platform = platform;
    this.tracker = tracker;
    this.registry = registry;
    this.world = world;
    this.upgrader = new DataUpgrader(platform::server, platform::dataFixDimension);
    this.ticked = new TickWrites(platform, tracker);
    this.dir = dir;
    this.objectsDir = objectsDir;
    this.genesisRoot = genesisRoot;
  }

  /** 建築世界；伺服器世界的版本庫為 null。 */
  public BuildWorlds.Entry buildWorld() {
    return world;
  }

  /** 給玩家看的名稱：建築世界是「擁有者/名稱」，伺服器世界是它的名稱。 */
  public String label() {
    return world != null ? world.label() : registry.serverWorldName();
  }

  /** 主分支的世界在版本中的維度：建築世界固定是主世界，伺服器的世界是它本身的維度。 */
  private String mainDimension(ServerLevel level) {
    return world != null ? OVERWORLD : Tracker.dimensionId(level);
  }

  /**
   * 起點世界放在哪裡：設定的 {@code init.genesis-dir}，空白為 {@code buildgit/genesis}。實際用的位置記在
   * {@code buildgit/genesis-dir}，設定改了但資料還沒搬過去時繼續用原本的位置，免得受版本控制的世界看起來都沒 init。
   */
  private Path locateGenesis() {
    var fallback = dir.resolve("genesis");
    var setting = tracker.settings().genesisDir();
    var wanted = setting.isBlank() ? fallback : Path.of(setting);
    var pointer = dir.resolve(GENESIS_POINTER);
    try {
      var previous = Files.exists(pointer) ? Path.of(Files.readString(pointer).strip()) : fallback;
      if (!same(wanted, previous) && Files.exists(previous) && !Files.exists(wanted)) {
        log().warning("起點世界還在 " + previous.toAbsolutePath() + "，設定的 init.genesis-dir 是 " + wanted.toAbsolutePath()
            + "：關服後把那個資料夾搬過去才會改用新位置，這次繼續用原本的位置");
        return previous;
      }
      if (same(wanted, fallback)) Files.deleteIfExists(pointer);
      else if (!Files.exists(pointer) || !same(wanted, previous)) {
        Files.createDirectories(dir);
        Files.writeString(pointer, setting.strip());
      }
    } catch (IOException e) {
      log().log(Level.SEVERE, "無法讀寫 " + pointer, e);
    }
    return wanted;
  }

  private static boolean same(Path a, Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  Path genesisRoot() {
    return genesisRoot;
  }

  Path objectsDir() {
    return objectsDir;
  }

  /** 這個版本庫的檢視世界名稱的開頭。 */
  String viewPrefix() {
    return world != null ? VIEW_PREFIX + world.folder() + "_" : VIEW_PREFIX;
  }

  Path dir() {
    return dir;
  }

  Logger log() {
    return platform.log();
  }

  Platform platform() {
    return platform;
  }

  Tracker tracker() {
    return tracker;
  }

  AnvilSource genesis() {
    return genesis;
  }

  DataUpgrader upgrader() {
    return upgrader;
  }

  public MergeSessions merges() {
    return merges;
  }

  void async(Runnable r) {
    platform.async(r);
  }

  void sync(Runnable r) {
    // 多半從 CompletableFuture 的回呼呼叫：例外丟出去會被吞掉，這裡先記下
    platform.sync(() -> {
      try {
        r.run();
      } catch (RuntimeException e) {
        log().log(Level.SEVERE, "BuildGit 主執行緒工作失敗", e);
      }
    });
  }

  /** 世界仍然載入中（沒有被卸載）。 */
  boolean loaded(ServerLevel level) {
    return platform.server().getLevel(level.dimension()) == level;
  }

  /** 依 UUID 找已載入的實體；不在任何世界時為 null。 */
  Entity entity(UUID uuid) {
    for (var level : platform.server().getAllLevels()) {
      var e = level.getEntity(uuid);
      if (e != null) return e;
    }
    return null;
  }

  static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof UncheckedIOException) && t.getCause() != null) t = t.getCause();
    return t;
  }

  public boolean isOpen() {
    return repo != null;
  }

  public Repository repo() {
    return repo;
  }

  public Worktree main() {
    return worktrees.get(Repository.MAIN);
  }

  /** 世界所屬的工作區；不受版本控制（含檢視世界）時為 null。 */
  public Worktree worktree(ServerLevel world) {
    return world == null ? null : owners.get(world);
  }

  public Worktree worktree(String branch) {
    return worktrees.get(branch);
  }

  public Collection<Worktree> worktrees() {
    return List.copyOf(worktrees.values());
  }

  /** 檢視世界的版本；不是檢視世界時為 null。 */
  public View view(ServerLevel world) {
    return world == null ? null : views.get(world);
  }

  /** 世界在版本中的維度 id：分支與檢視世界對應回主世界的維度。 */
  public String logicalDimension(ServerLevel world) {
    var v = views.get(world);
    if (v != null) return v.dimension();
    var d = tracker.logicalDimension(world);
    return d != null ? d : Tracker.dimensionId(world);
  }

  /**
   * 主分支的世界：在裡面執行過 {@code /bg init} 的世界（有起點世界的維度），可以是多世界插件建立的世界
   * （設計 §2.4）。預設世界在最前面。
   */
  public List<ServerLevel> mainWorlds() {
    if (world != null) {
      var level = main() == null ? null : main().world(OVERWORLD);
      return level == null ? List.of() : List.of(level);
    }
    var result = new ArrayList<ServerLevel>();
    var seen = new HashSet<String>();
    for (var w : platform.server().getAllLevels())
      if (tracked(w) && seen.add(Tracker.dimensionId(w))) result.add(w);
    return result;
  }

  /** 名稱是 BuildGit 建立的世界（分支、檢視）。 */
  static boolean ownWorldName(String name) {
    return name.startsWith(WORLD_PREFIX) || name.startsWith(VIEW_PREFIX);
  }

  /** BuildGit 建立的世界（分支、檢視）。 */
  public boolean ownWorld(ServerLevel world) {
    String name = platform.levelName(world);
    return sources.containsKey(world) || name.startsWith(WORLD_PREFIX) || name.startsWith(VIEW_PREFIX) || registry.claims(name);
  }

  /**
   * 受版本控制：伺服器世界的版本庫是不是 BuildGit 建立、而且這個維度已經初始化過（有起點世界）的世界；
   * 建築世界是它的世界資料夾。
   */
  public boolean tracked(ServerLevel level) {
    if (repo == null) return false;
    if (world != null) return platform.levelName(level).equals(world.folder());
    return !ownWorld(level) && Files.isDirectory(genesisDir(Tracker.dimensionId(level)));
  }

  /** 這個版本庫的世界：主世界、草稿、整合、檢視世界。 */
  boolean owns(ServerLevel level) {
    return owners.containsKey(level) || views.containsKey(level);
  }

  /**
   * 世界載入了（多世界插件在 BuildGit 之後載入、或之後才 init 的世界；主執行緒）：受版本控制時納入主分支，
   * 已開啟的草稿也補上這個維度的世界。
   */
  public void worldLoaded(ServerLevel world) {
    if (owners.containsKey(world) || !tracked(world)) return;
    String dim = mainDimension(world);
    if (main().world(dim) != null) return;
    main().addWorld(dim, world);
    owners.put(world, main());
    if (this.world == null) {
      resumeCopy(world);
      copyLoaded(world);
    }
    if (!copying(dim)) openBranchWorlds(world);
  }

  // ---- 延遲複製（設計 §4.3） ----

  /** genesis/<維度>/ 裡的這個檔案表示延遲複製，內容是主世界存檔目錄。 */
  private static final String LAZY = "lazy";

  private final Map<String, Optional<Path>> lazy = new java.util.concurrent.ConcurrentHashMap<>();

  /** 延遲複製的維度：起點世界缺的 chunk 從這個目錄（主世界的存檔）讀；完整複製的維度為 null。任何執行緒。 */
  Path lazySource(String dimension) {
    return lazy.computeIfAbsent(dimension, d -> {
      var f = genesisDir(d).resolve(LAZY);
      try {
        return Files.exists(f) ? Optional.of(Path.of(Files.readString(f).strip())) : Optional.empty();
      } catch (IOException e) {
        log().log(Level.SEVERE, "無法讀取 " + f, e);
        return Optional.empty();
      }
    }).orElse(null);
  }

  /** 延遲複製：已經載入的 chunk（啟動時的出生點、多世界插件剛載入的世界）也要在被改之前複製。 */
  private void copyLoaded(ServerLevel world) {
    String dim = Tracker.dimensionId(world);
    var source = lazySource(dim);
    if (source != null) genesisRecorder.copyLoaded(dim, source, platform.loadedChunks(world), () -> {});
  }

  /** init 時是否改用延遲複製：設定的門檻以世界存檔（region 與 entities）的大小比較。 */
  private boolean lazyCopy(Path levelDir) throws IOException {
    long threshold = tracker.settings().lazyCopyAboveMb();
    if (threshold < 0) return false;
    long size = 0;
    for (var sub : List.of("region", "entities")) {
      if (!Files.isDirectory(levelDir.resolve(sub))) continue;
      try (Stream<Path> s = Files.list(levelDir.resolve(sub))) {
        size += s.mapToLong(p -> p.toFile().length()).sum();
      }
    }
    return size >= threshold * 1024 * 1024;
  }

  /** 上次沒複製完的起點世界（伺服器在複製途中關閉）：補完，已有的 chunk 跳過。 */
  private void resumeCopy(ServerLevel world) {
    String dim = Tracker.dimensionId(world);
    if (copying(dim) || !GenesisCopy.unfinished(genesisDir(dim))) return;
    try {
      var copy = new GenesisCopy(dim, platform.levelDirectory(world), genesisDir(dim), log());
      copies.put(dim, copy);
      log().warning("上次沒有複製完 " + platform.levelName(world) + " 的起點世界，在背景補完；之後改過又存回的 chunk 會以目前的樣子當作起點");
      startCopy(world, copy, () -> {});
    } catch (IOException e) {
      log().log(Level.SEVERE, "無法補完起點世界 " + platform.levelName(world), e);
    }
  }

  /** 已開啟的草稿補上這個主世界維度的世界。 */
  private void openBranchWorlds(ServerLevel world) {
    String dim = mainDimension(world);
    try {
      for (var wt : worktrees())
        if (wt != main() && wt.world(dim) == null && covers(wt.branch(), dim)) {
          var branch = wt.branch();
          var w = createWorld(worldName(branch, dim), world, new Pending(dim, () -> repo.commit(repo.head(branch)).tree()));
          wt.addWorld(dim, w);
          owners.put(w, wt);
        }
    } catch (IOException | RuntimeException e) {
      log().log(Level.WARNING, "無法為草稿開啟世界 " + platform.levelName(world), e);
    }
  }

  /** 是否是主分支追蹤中的世界（多世界插件要卸載時用來擋下）。 */
  public boolean isMainWorld(ServerLevel world) {
    return repo != null && owners.get(world) == main();
  }

  /**
   * 別的插件要卸載世界時是否放行。主世界只在這個維度的主世界與各分支世界都沒有玩家、沒有在存檔或併回、
   * 起點世界也不在複製時放行；BuildGit 自己的世界一律放行。
   */
  public boolean mayUnload(ServerLevel world) {
    if (!isMainWorld(world)) return true;
    String dim = mainDimension(world);
    if (copying(dim) || main().isCommitting()) return false;
    for (var wt : worktrees()) {
      var w = wt.world(dim);
      if (w != null && !w.players().isEmpty()) return false;
    }
    return true;
  }

  /** 主分支的世界被卸載了：不再追蹤，未存的標記留著，等它再次載入。 */
  public void worldUnloaded(ServerLevel world) {
    if (!isMainWorld(world)) return;
    owners.remove(world);
    sources.remove(world);
    main().removeWorld(world);
    if (this.world == null) log().info("受版本控制的世界 " + platform.levelName(world) + " 已卸載；再次載入前，不能併回、撤銷或還原到主世界");
  }

  /** 已初始化（有起點世界）的維度。 */
  List<String> trackedDimensions() throws IOException {
    var result = new ArrayList<String>();
    var root = genesisRoot;
    if (!Files.isDirectory(root)) return result;
    try (var namespaces = Files.list(root)) {
      for (var ns : namespaces.filter(Files::isDirectory).toList())
        try (var paths = Files.list(ns)) {
          paths.filter(Files::isDirectory).forEach(p -> result.add(ns.getFileName() + ":" + p.getFileName()));
        }
    }
    return result;
  }

  private ServerLevel mainWorld(String dimension) {
    for (var w : mainWorlds()) if (mainDimension(w).equals(dimension)) return w;
    return null;
  }

  Path genesisDir(String dimension) {
    int colon = dimension.indexOf(':');
    return genesisRoot.resolve(dimension.substring(0, colon)).resolve(dimension.substring(colon + 1));
  }

  // ---- 開啟 ----

  /** 版本庫已存在時開啟並開始追蹤。 */
  public void open() throws IOException {
    if (!Repository.exists(dir)) return;
    repo = Repository.open(dir, objectsDir, upgrader);
    genesis = new AnvilSource(d -> {
      var p = genesisDir(d);
      return Files.isDirectory(p) ? p : null;
    }, this::lazySource, upgrader);
    genesisRecorder = new GenesisRecorder(this::genesisDir, genesis, log());
    var main = new Worktree(this, Repository.MAIN);
    worktrees.put(Repository.MAIN, main);
    for (var w : mainWorlds()) {
      main.addWorld(mainDimension(w), w);
      owners.put(w, main);
      resumeCopy(w);
      copyLoaded(w);
    }
    if (world != null) {
      // 主世界不在 server 的世界清單裡（BuildGit 建立的）；上次沒卸載就關服時，伺服器不會自動載入
      var level = platform.findLevel(world.folder());
      if (level != null) attachMain(level);
    }
    deleteStaleViews();
    merges = new MergeSessions(this);
    merges.load();
  }

  /**
   * 建立建築世界的版本庫（主執行緒）：空的起點世界與第一個存檔，再開啟。起點世界在 chunk 第一次生成時補上（設計 §2.3），
   * 所以建立是立刻的。
   */
  void create(Author author) throws IOException {
    Files.createDirectories(genesisDir(OVERWORLD).resolve("region"));
    Files.createDirectories(genesisDir(OVERWORLD).resolve("entities"));
    Repository.init(dir, objectsDir, author, System.currentTimeMillis(), upgrader);
    open();
  }

  /**
   * 建築世界：載入它的主世界（主執行緒）。產生器、種子、維度類型照伺服器的主世界；世界資料夾不在時以起點世界＋最新存檔合成，
   * 和草稿一樣。剛建立的世界把已經生成的 chunk 補進起點世界：它們在掛上追蹤之前生成，沒有經過 {@link #newChunk}。
   */
  ServerLevel openMain() throws IOException {
    var level = main().world(OVERWORLD);
    if (level != null) return level;
    // 還沒有 region 就是剛建立（版本庫放在世界資料夾裡時，資料夾在建立前就有了）
    boolean fresh = !Files.isDirectory(platform.levelFolder(world.folder()).resolve("region"));
    level = createWorld(world.folder(), platform.server().overworld(), new Pending(OVERWORLD, () -> repo.commit(repo.head(Repository.MAIN)).tree()));
    attachMain(level);
    if (fresh)
      for (long key : platform.loadedChunks(level)) {
        var chunk = level.getChunkSource().getChunkNow((int) (key >> 32), (int) key);
        if (chunk != null) genesisRecorder.record(OVERWORLD, level, chunk);
      }
    return level;
  }

  private void attachMain(ServerLevel level) {
    sources.putIfAbsent(level, new Pending(OVERWORLD, () -> repo.commit(repo.head(Repository.MAIN)).tree()));
    worldLoaded(level);
  }

  public void close() {
    for (var world : List.copyOf(views.keySet())) if (loaded(world)) discardWorld(world, null);
    views.clear();
    worktrees.values().forEach(Worktree::close);
    worktrees.clear();
    owners.clear();
    sources.clear();
    tracker.detachAll();
    copies.clear(); // 複製執行緒會繼續寫完；沒寫完的下次啟動補完
    upgrade = null; // 沒升級完的檔案維持原樣，讀取時照舊升級
    if (genesisRecorder != null) genesisRecorder.close();
    if (genesis != null) genesis.close();
    repo = null;
  }

  /** 定期把標記寫到磁碟（非同步執行緒）。 */
  public void flushJournal() {
    for (var w : worktrees()) w.flushJournal();
  }

  public void autosaveTick(Consumer<Worktree.SaveResult> done) {
    if (repo == null) return;
    for (var w : worktrees()) w.autosaveTick(done);
  }

  /** 分支 → 世界裡開始沒有玩家的遊戲刻。 */
  private final Map<String, Long> idleSince = new HashMap<>();

  /**
   * 每秒（主執行緒）：草稿與整合分支的世界沒有玩家超過 {@code idleTicks} 後，存到磁碟並卸載，工作區也一起關閉
   * （未存的標記寫進日誌）。之後 {@link #openBranch} 會重新載入：region 檔還在，不會重新合成。
   *
   * @param idleTicks 小於 0 表示不卸載
   */
  public void unloadIdleTick(long idleTicks) {
    if (repo == null || idleTicks < 0) return;
    long now = platform.tick();
    idleSince.keySet().retainAll(worktrees.keySet());
    for (var wt : worktrees()) {
      if (wt.branch().equals(Repository.MAIN)
          && (world == null || worktrees.size() > 1 || !views.isEmpty() || wt.worlds().isEmpty() || !wt.table().isEmpty())) continue;
      boolean occupied = wt.worlds().values().stream().anyMatch(w -> !w.players().isEmpty());
      if (occupied || wt.isCommitting()) {
        idleSince.remove(wt.branch());
        continue;
      }
      long since = idleSince.computeIfAbsent(wt.branch(), k -> now);
      if (now - since >= idleTicks) unload(wt);
    }
  }

  /**
   * 卸載分支的世界並關閉工作區（主執行緒）。正在存檔或併回時不動。建築世界的主世界也可以卸載：工作區留著（沒有世界），
   * 之後 {@link #openBranch} 再載入。
   */
  boolean unload(Worktree wt) {
    boolean main = wt.branch().equals(Repository.MAIN);
    if (main && world == null || !wt.lock()) return false;
    try {
      for (var world : List.copyOf(wt.worlds().values())) {
        // 先拿掉：卸載事件會問 mayUnload，主世界在存檔鎖之下會被擋下
        String dim = logicalDimension(world);
        owners.remove(world);
        var source = sources.remove(world);
        wt.removeWorld(world);
        if (!platform.unloadLevel(world, true)) {
          log().warning("無法卸載世界 " + platform.levelName(world) + "，" + wt.branch() + " 保持載入");
          wt.addWorld(dim, world);
          owners.put(world, wt);
          if (source != null) sources.put(world, source);
          return false;
        }
      }
      if (main) {
        idleSince.remove(wt.branch());
        log().info("已卸載沒有玩家的建築世界：" + label());
        return true;
      }
      worktrees.remove(wt.branch());
      idleSince.remove(wt.branch());
      wt.close();
      log().info("已卸載沒有玩家的分支世界：" + wt.branch());
      return true;
    } finally {
      wt.unlock();
    }
  }

  /** 存檔讀取起點世界之前呼叫（背景執行緒）：等新生成 chunk 的寫入完成。 */
  void awaitGenesis() {
    genesisRecorder.awaitIdle();
  }

  /** 以 region 檔計的進度；完成時的值是改寫的檔案數、升級的 chunk 數、失敗的檔案數。 */
  record GenesisUpgrade(Progress progress, CompletableFuture<long[]> done) {}

  /**
   * 把起點世界裡較舊版本存的 chunk 改寫成目前的格式（設計 §4.5）。內容不變（讀取時本來就會升級），省下之後每次讀取的升級。
   * 在寫入起點世界的執行緒上逐個 region 檔進行，和補進起點世界的寫入依序進行，不必暫停伺服器。
   */
  GenesisUpgrade upgradeGenesis() throws IOException {
    if (!copies.isEmpty()) throw new Refused("起點世界還在複製，複製完再升級");
    if (upgrade != null) throw new Refused("起點世界已經在升級了");
    var files = new ArrayList<Map.Entry<String, Path>>();
    for (var dim : trackedDimensions())
      for (var sub : List.of("region", "entities")) {
        var d = genesisDir(dim).resolve(sub);
        if (!Files.isDirectory(d)) continue;
        try (var s = Files.list(d)) {
          s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().forEach(p -> files.add(Map.entry(dim, p)));
        }
      }
    var task = new GenesisUpgrade(new Progress(), new CompletableFuture<>());
    task.progress().reset(files.size());
    upgrade = task;
    var count = new long[3]; // 只在寫入執行緒使用
    for (var f : files)
      genesisRecorder.execute(() -> {
        task.progress().step();
        if (upgrade != task) return;
        var file = f.getValue();
        boolean blocks = file.getParent().getFileName().toString().equals("region");
        try {
          int n = RegionUpgrade.rewrite(file, upgrader.current(), c -> blocks ? upgrader.chunk(f.getKey(), c) : upgrader.entityChunk(c),
              () -> genesisRecorder.forget(file));
          if (n == 0) return;
          count[0]++;
          count[1] += n;
          genesis.invalidate();
        } catch (IOException | RuntimeException e) {
          count[2]++;
          log().log(Level.WARNING, "無法升級起點世界的 " + file + "；這個檔案維持原樣，讀取時照舊升級", e);
        }
      });
    genesisRecorder.execute(() -> {
      if (upgrade == task) upgrade = null;
      task.done().complete(count);
    });
    return task;
  }

  // ---- init ----

  /**
   * 讓一個世界開始受版本控制：存檔、把它的 region 與實體檔複製成凍結的起點世界；第一個世界同時建立版本庫。
   * 只處理這一個世界（地獄、終界與其他世界各自 init）。主執行緒只存這個世界一次，複製在背景進行（{@link GenesisCopy}）。
   *
   * @return 背景複製；完成時的值是起點世界大小（位元組）。延遲複製時為 null：不必等待
   */
  public GenesisCopy init(Author author, ServerLevel world) throws IOException {
    if (this.world != null) throw new Refused("建築世界建立時就受版本控制了");
    if (ownWorld(world)) throw new Refused("這是 BuildGit 建立的世界；請在要版本控制的世界裡執行");
    if (tracked(world)) throw new Refused("這個世界已經受版本控制");
    if (repo == null && Repository.exists(dir)) throw new Refused("版本庫存在但無法開啟");
    // 只存這個世界；之後磁碟上的 chunk 在被伺服器再寫一次之前都是 init 當下的樣子，可以在背景複製。
    // 主執行緒只把 chunk 交給 IO 執行緒，等它寫完在複製執行緒上進行。
    var flush = platform.save(world);
    String dim = Tracker.dimensionId(world);
    var levelDir = platform.levelDirectory(world);
    if (lazyCopy(levelDir)) {
      // 延遲複製：只複製已載入的 chunk（存檔寫完之後），其餘在第一次載入時複製
      var g = genesisDir(dim);
      Files.createDirectories(g.resolve("region"));
      Files.createDirectories(g.resolve("entities"));
      String source;
      try {
        source = Path.of("").toAbsolutePath().relativize(levelDir.toAbsolutePath()).toString();
      } catch (IllegalArgumentException e) {
        source = levelDir.toAbsolutePath().toString(); // 不同磁碟機
      }
      lazy.put(dim, Optional.empty()); // 開啟時先不要複製已載入的 chunk：要等存檔寫完
      Files.writeString(g.resolve(LAZY), source);
      if (repo == null) {
        Repository.init(dir, author, System.currentTimeMillis(), upgrader);
        open();
      } else {
        worldLoaded(world);
      }
      lazy.put(dim, Optional.of(Path.of(source)));
      genesisRecorder.copyLoaded(dim, Path.of(source), platform.loadedChunks(world), flush);
      return null;
    }
    var copy = new GenesisCopy(dim, levelDir, genesisDir(dim), log());
    copies.put(dim, copy);
    if (repo == null) {
      Repository.init(dir, author, System.currentTimeMillis(), upgrader);
      open();
    } else {
      worldLoaded(world);
    }
    startCopy(world, copy, flush);
    return copy;
  }

  /** 在寫入起點世界的執行緒上執行：備份（設計 §2.5）在這裡複製起點世界的檔案，才不會拿到寫了一半的內容。 */
  <T> CompletableFuture<T> onGenesisThread(java.util.concurrent.Callable<T> task) {
    var result = new CompletableFuture<T>();
    genesisRecorder.execute(() -> {
      try {
        result.complete(task.call());
      } catch (Exception e) {
        result.completeExceptionally(e);
      }
    });
    return result;
  }

  /** 主世界這個維度的種子；世界沒載入時為 null。 */
  Long mainSeed(String dimension) {
    var w = mainWorld(dimension);
    return w == null ? null : w.getSeed();
  }

  /** 主世界裡這個維度的存檔目錄；世界沒載入時為 null。 */
  Path mainLevelDirectory(String dimension) {
    var w = mainWorld(dimension);
    return w == null ? null : platform.levelDirectory(w);
  }

  /** 起點世界還在背景複製的維度：存檔先不收這個維度的標記，也不開它的草稿與檢視世界。 */
  public boolean copying(String dimension) {
    return copies.containsKey(dimension);
  }

  private void startCopy(ServerLevel world, GenesisCopy copy, Runnable flush) {
    String dim = Tracker.dimensionId(world);
    copy.start(platform.loadedChunks(world), flush);
    copy.whenDone(size -> sync(() -> {
      copies.remove(dim);
      if (repo == null) return;
      genesis.invalidate();
      log().info("起點世界複製完成：" + platform.levelName(world) + "（" + size / (1024 * 1024) + " MB）");
      openBranchWorlds(world);
    }), error -> sync(() -> {
      copies.remove(dim);
      log().log(Level.SEVERE, "起點世界複製失敗：" + platform.levelName(world) + "；重新啟動後會補完", error);
    }));
  }

  /** 新生成的 chunk 以外的 chunk 載入了（主執行緒）：起點世界還在複製時，或延遲複製時，趁它被改之前先複製。 */
  public void chunkLoaded(ServerLevel world, int chunkX, int chunkZ) {
    if (this.world != null || owners.get(world) != main()) return;
    String dim = Tracker.dimensionId(world);
    var copy = copies.get(dim);
    if (copy != null) copy.chunkLoaded(chunkX, chunkZ);
    var source = lazySource(dim);
    if (source != null) genesisRecorder.copy(dim, source, chunkX, chunkZ);
  }


  private static long copy(Path from, Path to) throws IOException {
    Files.createDirectories(to);
    if (!Files.isDirectory(from)) return 0;
    long size = 0;
    try (Stream<Path> s = Files.list(from)) {
      for (var f : s.filter(p -> p.getFileName().toString().endsWith(".mca") || p.getFileName().toString().endsWith(".mcc")).toList()) {
        Files.copy(f, to.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        size += Files.size(f);
      }
    }
    return size;
  }

  // ---- 新生成的 chunk ----

  /**
   * 剛生成一個 chunk（主執行緒）：
   * 1. 受追蹤的世界：生成結果補進起點世界（設計 §2.3），已有的不覆寫。草稿也要補：存檔以起點世界為比較基準，
   *    沒有的 chunk 會被當成空氣（挖掉的方塊存不進去）。所以草稿的產生器必須和主世界相同；
   * 2. BuildGit 建立的世界：這個 chunk 若在覆寫表中有內容（起點世界沒有、所以沒被合成），套用上去。
   */
  public void newChunk(ServerLevel world, LevelChunk chunk) {
    if (repo == null) return;
    var owner = worktree(world);
    if (owner != null) {
      String dim = tracker.logicalDimension(world);
      var copy = copies.get(dim);
      var pos = chunk.getPos();
      if (copy == null || !copy.generated(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4, LiveReader.snapshot(world, chunk)))
        genesisRecorder.record(dim, world, chunk);
    }
    var source = sources.get(world);
    if (source == null) return;
    int cx = chunk.getPos().getMinBlockX() >> 4, cz = chunk.getPos().getMinBlockZ() >> 4;
    async(() -> {
      try {
        var overlay = new OverlayReader(repo.overlays(), source.tree().tree()).chunk(source.dimension(), cx, cz);
        if (overlay.isEmpty()) return;
        sync(() -> tracker.systemWrite(() -> {
          boolean biomes = false;
          for (var e : overlay.entrySet()) {
            int sy = e.getKey();
            e.getValue().cells().forEach((index, cell) -> {
              int x = cx << 4 | index & 15, y = sy << 4 | index >> 8, z = cz << 4 | index >> 4 & 15;
              try {
                LiveWriter.setCell(world, x, y, z, cell);
              } catch (IOException ex) {
                log().log(Level.WARNING, "無法套用覆寫 " + x + " " + y + " " + z, ex);
              }
            });
            if (!e.getValue().biomes().isEmpty()) biomes |= LiveWriter.setBiomes(world, chunk, sy, e.getValue().biomes());
          }
          if (biomes) LiveWriter.resendBiomes(world, List.of(chunk));
        }));
      } catch (IOException e) {
        log().log(Level.WARNING, "無法讀取 chunk " + cx + "," + cz + " 的覆寫", e);
      }
    });
  }

  // ---- 分支 ----

  private Path branchesPath() {
    return dir.resolve("branches.yml");
  }

  /** branches.yml：分支 → 來源分支、建立時間、種類。 */
  private Yaml branchesFile() {
    return Yaml.load(branchesPath());
  }

  /**
   * 草稿所屬的世界（維度 id，設計 §4.4）：草稿只開這個世界，撤銷、還原也只動它。
   * null 表示主世界，或是這項設定之前建立的草稿（涵蓋所有世界）。
   */
  public String dimension(String branch) {
    if (branch.equals(Repository.MAIN)) return null;
    return branchesFile().getString(branch + ".dimension", null);
  }

  /** 分支是否包含這個世界。 */
  public boolean covers(String branch, String dimension) {
    var own = dimension(branch);
    return own == null || own.equals(dimension);
  }

  /** 草稿的來源分支（併回的目標）；不知道時為 main。 */
  public String source(String branch) {
    return branchesFile().getString(branch + ".source", Repository.MAIN);
  }

  /** 所有分支，含衝突整合用的暫時分支。 */
  public SortedMap<String, ObjectId> branches() throws IOException {
    return repo.refs().list();
  }

  /** 玩家看得到的分支：主世界與草稿。 */
  public SortedMap<String, ObjectId> drafts() throws IOException {
    var all = new TreeMap<>(branches());
    all.keySet().removeIf(Workspace::isScratch);
    return all;
  }

  static boolean isScratch(String branch) {
    return branch.startsWith(MERGE_PREFIX);
  }

  /** 建立分支，指向來源分支的最新存檔。{@code dimension} 是草稿所屬的世界，null 表示所有世界。 */
  public void createBranch(String name, String from, String dimension) throws IOException {
    createBranch(name, from, repo.head(from), false, dimension);
  }

  void createBranch(String name, String from, ObjectId at, boolean scratch, String dimension) throws IOException {
    if (!BRANCH_NAME.matcher(name).matches() || !RefStore.validName(name)) throw new IOException("名稱只能用小寫英數、底線、連字號：" + name);
    if (isScratch(name) != scratch) throw new IOException("草稿名稱不能以 " + MERGE_PREFIX + " 開頭");
    if (dimension != null && !covers(from, dimension)) throw new Refused("來源 " + from + " 不包含這個世界");
    repo.createBranch(name, at);
    var yaml = branchesFile();
    yaml.set(name + ".source", from);
    yaml.set(name + ".created", System.currentTimeMillis());
    if (dimension != null) yaml.set(name + ".dimension", dimension);
    if (scratch) yaml.set(name + ".kind", "merge");
    yaml.save(branchesPath());
  }

  /**
   * 刪除草稿：卸載並刪除世界、工作區與 ref（主執行緒）。世界裡的玩家傳送回來源世界。
   *
   * @param force 有未存的變更也刪
   */
  /** 預期中的拒絕（例如還有未存的變更）：只告訴玩家原因，不寫錯誤記錄。 */
  public static final class Refused extends IOException {
    public Refused(String message) {
      super(message);
    }
  }

  private static boolean hasJournal(Path worktreeDir) throws IOException {
    if (!Files.isDirectory(worktreeDir)) return false;
    try (var s = Files.list(worktreeDir)) {
      return s.anyMatch(p -> {
        try {
          return p.getFileName().toString().endsWith(".journal") && Files.size(p) > 0;
        } catch (IOException e) {
          return true;
        }
      });
    }
  }

  public void deleteBranch(String name, boolean force) throws IOException {
    if (name.equals(Repository.MAIN)) throw new Refused("不能刪除主世界");
    repo.head(name);
    var yaml = branchesFile();
    for (var other : yaml.keys())
      if (!other.equals(name) && name.equals(yaml.getString(other + ".source")) && repo.refs().get(other).isPresent())
        throw new Refused("草稿 " + other + " 以它為來源，請先刪除或併回 " + other);
    var wt = worktrees.get(name);
    // 已卸載的分支沒有標記表，未存的變更只在標記日誌裡（存檔成功後舊日誌會刪掉，所以有內容就是還沒存）
    boolean unsaved = wt != null ? !wt.table().isEmpty() : hasJournal(dir.resolve("worktree").resolve(name));
    if (!force && unsaved) throw new Refused("有未存的變更（加上 --force 仍要刪除）");
    // 存過、但還沒整份併回的存檔，刪掉草稿後就找不到了
    var sourceHead = repo.refs().get(source(name));
    if (!force && sourceHead.isPresent() && !repo.isAncestor(repo.head(name), sourceHead.get()))
      throw new Refused("有還沒併回的存檔（加上 --force 仍要刪除）");
    if (wt != null && wt.isCommitting()) throw new Refused("正在存檔或併回，請稍後再試");
    var fallback = worktrees.get(source(name));
    if (fallback == null) fallback = main();
    if (wt != null) {
      worktrees.remove(name);
      wt.close();
      for (var world : List.copyOf(wt.worlds().values())) discardWorld(world, fallback);
    }
    for (var dim : trackedDimensions()) {
      var world = worldName(name, dim);
      if (platform.findLevel(world) == null) deleteLater(platform.levelFolder(world));
    }
    deleteLater(dir.resolve("worktree").resolve(name));
    repo.deleteBranch(name);
    yaml.set(name, null);
    yaml.save(branchesPath());
  }

  /** 草稿某維度的世界名稱。 */
  String worldName(String branch, String dimension) {
    return world != null ? world.folder() + "-" + branch : WORLD_PREFIX + branch + suffix(dimension);
  }

  /** 主世界沒有後綴；地獄、終界沿用原版的後綴；其他世界（多世界插件）加上維度 id。 */
  private static String suffix(String dimension) {
    return switch (dimension) {
      case "minecraft:overworld" -> "";
      case "minecraft:the_nether" -> "_nether";
      case "minecraft:the_end" -> "_the_end";
      default -> "__" + (dimension.startsWith("minecraft:") ? dimension.substring(10) : dimension.replace(':', '_'))
          .replaceAll("[^a-z0-9_-]", "_");
    };
  }

  /**
   * 開啟分支的工作區與世界（主執行緒）。世界不存在時建立：種子與產生器設定照抄主世界，不複製任何 region 檔。
   */
  public Worktree openBranch(String branch) throws IOException {
    idleSince.remove(branch); // 剛被用到（例如傳送還在進行），重新計算閒置時間
    if (world != null) openMain();
    if (branch.equals(Repository.MAIN)) return main();
    var existing = worktrees.get(branch);
    if (existing != null) return existing;
    repo.head(branch); // 不存在時丟出例外
    var wt = new Worktree(this, branch);
    worktrees.put(branch, wt);
    try {
      for (var mainWorld : mainWorlds()) {
        String dim = mainDimension(mainWorld);
        if (!covers(branch, dim)) continue;
        if (copying(dim)) continue; // 複製完成後由 openBranchWorlds 補上
        var world = createWorld(worldName(branch, dim), mainWorld, new Pending(dim, () -> repo.commit(repo.head(branch)).tree()));
        wt.addWorld(dim, world);
        owners.put(world, wt);
      }
    } catch (IOException | RuntimeException e) {
      worktrees.remove(branch);
      wt.close();
      throw e;
    }
    return wt;
  }

  private ServerLevel createWorld(String name, ServerLevel template, Pending source) throws IOException {
    var world = platform.findLevel(name);
    if (world == null) {
      world = platform.openLevel(name, template,
          (target, rx, rz) -> synthesizer(source).blocks(target, rx, rz),
          (target, rx, rz) -> synthesizer(source).entities(target, rx, rz));
      if (world == null) throw new IOException("無法建立世界 " + name);
      tracker.precise(world, dev.twme.buildgit.server.nms.CascadeUpdater.install(platform, world, tracker));
    }
    sources.put(world, source);
    return world;
  }

  /** 某個覆寫表、某個維度的合成器。IO 執行緒呼叫。 */
  private RegionSynthesizer synthesizer(Pending source) throws IOException {
    var tree = source.tree().tree();
    String key = tree.hex() + "/" + source.dimension();
    synchronized (synthesizers) {
      var s = synthesizers.get(key);
      if (s != null) return s;
      var g = genesisDir(source.dimension());
      s = new RegionSynthesizer(Files.isDirectory(g) ? g : null, lazySource(source.dimension()), new OverlayReader(repo.overlays(), tree),
          source.dimension(), upgrader);
      synthesizers.put(key, s);
      return s;
    }
  }

  // ---- 檢視世界（設計 §6.5） ----

  String viewName(ObjectId commit, String dimension) {
    return viewPrefix() + commit.hex().substring(0, 10) + (world != null ? "" : suffix(dimension));
  }

  /** 開啟某個版本、某個維度的唯讀世界（主執行緒）。 */
  public ServerLevel openView(ObjectId commit, String dimension) throws IOException {
    if (world != null) openMain();
    var template = mainWorld(dimension);
    if (template == null) throw new IOException("主世界沒有維度 " + dimension);
    if (copying(dimension)) throw new Refused("這個世界的起點世界還在背景複製，請稍後再試");
    var tree = repo.commit(commit).tree();
    var world = createWorld(viewName(commit, dimension), template, new Pending(dimension, () -> tree));
    views.put(world, new View(commit, dimension));
    return world;
  }

  /** 沒有玩家的檢視世界卸載並刪除。 */
  public void discardIfEmpty(ServerLevel world) {
    if (views.containsKey(world) && world.players().isEmpty()) discardWorld(world, null);
  }

  private void deleteStaleViews() {
    try {
      for (var name : platform.levelFolders())
        if (name.startsWith(VIEW_PREFIX) && platform.findLevel(name) == null) deleteLater(platform.levelFolder(name));
    } catch (IOException e) {
      log().log(Level.WARNING, "無法清理舊的檢視世界", e);
    }
  }

  /**
   * 卸載並刪除 BuildGit 建立的世界（主執行緒）。裡面的玩家傳送到 {@code fallback} 的同一個位置；沒給時回主世界。
   */
  void discardWorld(ServerLevel world, Worktree fallback) {
    String dim = logicalDimension(world);
    var to = fallback != null ? fallback : main();
    for (var p : List.copyOf(world.players())) {
      var target = to != null ? to.world(dim) : null;
      if (target == null) target = platform.server().overworld();
      teleportNow(p, target, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
    }
    var folder = platform.levelDirectory(world);
    owners.remove(world);
    sources.remove(world);
    views.remove(world);
    if (!platform.unloadLevel(world, false)) {
      log().warning("無法卸載世界 " + platform.levelName(world) + "，資料夾保留");
      return;
    }
    deleteLater(folder);
  }

  /** 立刻傳送（主執行緒）；收掉世界之前必須先把人移走。 */
  static void teleportNow(ServerPlayer p, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
    p.teleport(new net.minecraft.world.level.portal.TeleportTransition(level, new net.minecraft.world.phys.Vec3(x, y, z),
        net.minecraft.world.phys.Vec3.ZERO, yaw, pitch, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
  }

  /** 稍後在背景刪除資料夾（等伺服器關閉檔案）。 */
  private void deleteLater(Path folder) {
    platform.asyncLater(20, () -> {
      try {
        deleteRecursively(folder);
      } catch (IOException e) {
        log().log(Level.WARNING, "無法刪除 " + folder, e);
      }
    });
  }

  /** 遞迴刪除；同一個資料夾可能同時被收掉世界與刪除分支各刪一次，已經不在的項目略過。 */
  static synchronized void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) return;
    Files.walkFileTree(path, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.deleteIfExists(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
        if (e instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
        throw e;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
        if (e != null && !(e instanceof NoSuchFileException)) throw e;
        Files.deleteIfExists(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  // ---- 傳送 ----

  /** 傳送到某個世界的同一個座標。 */
  CompletableFuture<Boolean> teleport(ServerPlayer player, ServerLevel world) {
    return platform.teleport(player, world, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
  }

  /** 傳送到某個分支、玩家目前所在維度的世界。 */
  CompletableFuture<Boolean> teleport(ServerPlayer player, Worktree to) {
    var world = to.world(logicalDimension(player.level()));
    if (world == null) world = to.worlds().values().iterator().next();
    return teleport(player, world);
  }

  // ---- 離線時所在的草稿（設計 §4.4） ----

  private Path playersPath() {
    return dir.resolve("players.yml");
  }

  /**
   * 玩家離線或伺服器關閉時（主執行緒）：在草稿或整合世界裡就記下位置，下次上線送回；否則清掉紀錄。
   * 草稿世界之後可能閒置卸載或隨伺服器關閉，玩家上線時伺服器找不到那個世界，所以由 BuildGit 送回。
   */
  public void playerLeft(ServerPlayer p) {
    if (repo == null) return;
    var wt = worktree(p.level());
    String key = p.getUUID().toString();
    var yaml = Yaml.load(playersPath());
    if (wt == null || wt == main() && world == null) {
      if (!yaml.contains(key)) return;
      yaml.set(key, null);
    } else {
      yaml.set(key + ".branch", wt.branch());
      yaml.set(key + ".dimension", logicalDimension(p.level()));
      yaml.set(key + ".pos", List.of(p.getX(), p.getY(), p.getZ(), (double) p.getYRot(), (double) p.getXRot()));
    }
    try {
      yaml.save(playersPath());
    } catch (IOException e) {
      log().log(Level.WARNING, "無法記錄 " + p.getScoreboardName() + " 離線時的位置", e);
    }
  }

  /** 玩家上線（主執行緒）：上次在草稿裡離線的，送回那個草稿的同一個位置。草稿已刪除時留在原地。 */
  public void playerJoined(ServerPlayer p) {
    if (repo == null) return;
    String key = p.getUUID().toString();
    var yaml = Yaml.load(playersPath());
    if (!yaml.contains(key)) return;
    String branch = yaml.getString(key + ".branch");
    String dimension = yaml.getString(key + ".dimension");
    var pos = yaml.get(key + ".pos") instanceof List<?> l ? l : List.of();
    yaml.set(key, null);
    try {
      yaml.save(playersPath());
      if (branch == null || pos.size() != 5 || !branches().containsKey(branch)) return;
      var world = openBranch(branch).world(dimension);
      if (world == null || p.level() == world) return;
      double[] v = new double[5];
      for (int i = 0; i < 5; i++) v[i] = pos.get(i) instanceof Number n ? n.doubleValue() : 0;
      platform.teleport(p, world, v[0], v[1], v[2], (float) v[3], (float) v[4]);
    } catch (IOException | RuntimeException e) {
      log().log(Level.WARNING, "無法把 " + p.getScoreboardName() + " 送回草稿 " + branch, e);
    }
  }

  // ---- 寫入版本並套用到世界 ----

  /** 一次寫入的結果。 */
  public record Applied(ObjectId commit, int cells, int entities, int biomes) {}

  /** 在背景執行緒、持有分支存檔鎖時計算新提交；回傳 empty 表示沒有變化。 */
  interface TreeOp {
    Optional<ObjectId> run(ObjectId head) throws IOException;
  }

  /**
   * 在分支上提交 {@code op} 算出的新版本，並把差異原樣寫進分支的世界（設計 §5.2）。
   * 呼叫前應先存檔，讓世界與分支最新存檔一致。主執行緒呼叫，結果在主執行緒回呼。
   */
  void transact(Worktree wt, TreeOp op, Consumer<Optional<Applied>> done, Consumer<Throwable> failed) {
    // 先提交才寫世界：有維度的世界沒載入時，那部分會只進版本、不進世界，所以整個擋下
    try {
      var missing = trackedDimensions().stream().filter(d -> wt.world(d) == null && covers(wt.branch(), d)).toList();
      if (!missing.isEmpty()) {
        failed.accept(new Refused("這些世界沒有載入，載入後才能寫入 " + wt.branch() + "：" + String.join("、", missing)));
        return;
      }
    } catch (IOException e) {
      failed.accept(e);
      return;
    }
    if (!wt.lock()) {
      failed.accept(new IOException("分支正在存檔或併回，請稍後再試：" + wt.branch()));
      return;
    }
    wt.progress().reset(0);
    CompletableFuture.supplyAsync(() -> {
      try {
        awaitGenesis();
        var head = repo.head(wt.branch());
        var before = repo.commit(head).tree();
        var id = op.run(head);
        if (id.isEmpty()) return null;
        var diff = TreeDiff.collect(repo.overlays(), before, repo.commit(id.get()).tree());
        return new Plan(id.get(), diff.cells().size(), diff.entities().size(), diff.biomes().size(), resolve(diff));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, this::async).whenComplete((plan, error) -> sync(() -> {
      if (error != null) {
        wt.unlock();
        failed.accept(unwrap(error));
        return;
      }
      if (plan == null) {
        wt.unlock();
        done.accept(Optional.empty());
        return;
      }
      apply(wt, plan.writes()).whenComplete((ignored, applyError) -> sync(() -> {
        if (applyError != null) log().log(Level.SEVERE, "版本已提交，但寫入世界時失敗；未寫入的部分在下次讀取時會不一致", unwrap(applyError));
        wt.unlock();
        done.accept(Optional.of(new Applied(plan.commit(), plan.cells(), plan.entities(), plan.biomes())));
      }));
    }));
  }

  private record Plan(ObjectId commit, int cells, int entities, int biomes, Writes writes) {}

  /**
   * 要寫進世界的最終內容（已把「回到起點」換成起點世界的實際內容）。
   *
   * @param biomes 維度 → chunk → section Y → 生態域格 → 生態域
   */
  private record Writes(Map<String, Map<Long, List<CellWrite>>> cells, List<EntityWrite> entities,
      Map<String, Map<Long, Map<Integer, Map<Integer, String>>>> biomes) {}

  private record CellWrite(BlockPos pos, Cell cell) {}

  /** state 為 empty 表示移除；chunkX/Z 是要載入的 chunk（實體目前或將要所在的位置）。 */
  private record EntityWrite(String dimension, UUID uuid, Optional<EntityState> state, int chunkX, int chunkZ) {}

  /** 起點世界某一格的內容。背景執行緒。 */
  Cell genesisCell(String dimension, BlockPos pos) throws IOException {
    var wanted = new BitSet();
    wanted.set(pos.sectionIndex());
    var cell = genesis.readSection(SectionKey.of(dimension, pos), wanted)[pos.sectionIndex()];
    return cell == null ? Cell.AIR : cell;
  }

  private Writes resolve(TreeDiff.Result diff) throws IOException {
    var cells = new HashMap<String, Map<Long, List<CellWrite>>>();
    for (var c : diff.cells()) {
      var cell = c.after() != null ? c.after() : genesisCell(c.dimension(), c.pos());
      long key = chunkKey(c.pos().x() >> 4, c.pos().z() >> 4);
      cells.computeIfAbsent(c.dimension(), k -> new HashMap<>()).computeIfAbsent(key, k -> new ArrayList<>()).add(new CellWrite(c.pos(), cell));
    }
    var biomes = new HashMap<String, Map<Long, Map<Integer, Map<Integer, String>>>>();
    for (var b : diff.biomes()) {
      var key = b.key();
      var biome = b.after();
      if (biome == null) {
        var origin = genesis.readBiomes(key);
        if (origin == null) continue; // 起點世界沒有這個 section：不知道原本是什麼，保留世界現狀
        biome = origin[b.index()];
      }
      biomes.computeIfAbsent(key.dimension(), k -> new HashMap<>()).computeIfAbsent(chunkKey(key.chunkX(), key.chunkZ()), k -> new HashMap<>())
          .computeIfAbsent(key.sectionY(), k -> new HashMap<>()).put(b.index(), biome);
    }
    var entities = new ArrayList<EntityWrite>();
    for (var e : diff.entities()) {
      var after = e.after() != null ? e.after() : genesis.readEntity(e.dimension(), e.uuid());
      var before = e.before() != null ? e.before() : genesis.readEntity(e.dimension(), e.uuid());
      // 先在舊位置移除，再在新位置放置。
      if (before.isPresent() && before.get().position() != null) {
        var p = before.get().position();
        entities.add(new EntityWrite(e.dimension(), e.uuid(), Optional.empty(), (int) Math.floor(p[0]) >> 4, (int) Math.floor(p[2]) >> 4));
      }
      if (after.isPresent() && after.get().position() != null) {
        var p = after.get().position();
        entities.add(new EntityWrite(e.dimension(), e.uuid(), after, (int) Math.floor(p[0]) >> 4, (int) Math.floor(p[2]) >> 4));
      }
    }
    return new Writes(cells, entities, biomes);
  }

  private static long chunkKey(int x, int z) {
    return (long) x << 32 | z & 0xFFFFFFFFL;
  }

  /** 逐 chunk 載入並分刻寫入（主執行緒）。實體等該 chunk 的實體載入後才處理。進度以 chunk 與實體計。 */
  private CompletableFuture<Void> apply(Worktree wt, Writes writes) {
    var progress = wt.progress();
    progress.reset(writes.cells().values().stream().mapToLong(Map::size).sum() + writes.biomes().values().stream().mapToLong(Map::size).sum()
        + writes.entities().size());
    var futures = new ArrayList<CompletableFuture<?>>();
    var parsed = new HashMap<String, net.minecraft.world.level.block.state.BlockState>();
    writes.cells().forEach((dim, byChunk) -> {
      var world = wt.world(dim);
      if (world == null) return;
      byChunk.forEach((key, cells) -> {
        int cx = (int) (key >> 32), cz = (int) (long) key;
        futures.add(platform.loadChunk(world, cx, cz).thenCompose(chunk -> ticked.submit(world, cx, cz, cells.size(), i -> {
          var w = cells.get(i);
          try {
            LiveWriter.setCell(world, w.pos().x(), w.pos().y(), w.pos().z(), w.cell(), parsed);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        })).thenRun(progress::step));
      });
    });
    writes.biomes().forEach((dim, byChunk) -> {
      var world = wt.world(dim);
      if (world == null) return;
      byChunk.forEach((key, sections) -> futures.add(platform.loadChunk(world, (int) (key >> 32), (int) (long) key).thenAccept(chunk -> {
        boolean changed = false;
        for (var e : sections.entrySet()) changed |= LiveWriter.setBiomes(world, chunk, e.getKey(), e.getValue());
        if (changed) LiveWriter.resendBiomes(world, List.of(chunk));
        progress.step();
      })));
    });
    CompletableFuture<Void> blocks = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    return blocks.thenCompose(ignored -> {
      CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
      for (var e : writes.entities()) {
        var world = wt.world(e.dimension());
        if (world == null) continue;
        chain = chain.thenCompose(x -> platform.loadChunk(world, e.chunkX(), e.chunkZ())).thenCompose(c -> entitiesLoaded(world, c)).thenAccept(chunk ->
            tracker.systemWrite(() -> {
              try {
                if (e.state().isEmpty()) LiveWriter.removeEntity(world, e.uuid());
                else LiveWriter.spawnEntity(world, e.state().get());
              } catch (IOException ex) {
                throw new UncheckedIOException(ex);
              }
            })).thenRun(progress::step);
      }
      return chain;
    });
  }

  /** 實體與方塊分開載入：等這個 chunk 的實體載入完成（最多約 5 秒）。 */
  CompletableFuture<LevelChunk> entitiesLoaded(ServerLevel world, LevelChunk chunk) {
    var result = new CompletableFuture<LevelChunk>();
    var tries = new int[] {0};
    Runnable[] check = new Runnable[1];
    var pos = chunk.getPos();
    check[0] = () -> {
      if (platform.entitiesLoaded(world, pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4) || ++tries[0] > 100) result.complete(chunk);
      else platform.later(1, check[0]);
    };
    sync(check[0]);
    return result;
  }

  // ---- 合併（設計 §5.2、§6.2） ----

  /** 合併有衝突：交給衝突整合流程（§5.3）。 */
  public static final class ConflictException extends IOException {
    final ThreeWayMerge.Result result;
    final ObjectId base, ours, theirs;

    ConflictException(ThreeWayMerge.Result result, ObjectId base, ObjectId ours, ObjectId theirs) {
      super("有 " + result.cellConflicts().size() + " 格、" + result.entityConflicts().size() + " 個實體衝突");
      this.result = result;
      this.base = base;
      this.ours = ours;
      this.theirs = theirs;
    }
  }

  /** 合併的範圍：只合併 {@code dimension} 裡 {@code box} 內的變更（部分併回）。 */
  public record Scope(String dimension, Box box) {}

  /**
   * 把 {@code source} 合併進 {@code target} 的世界。兩邊都必須沒有未存的變更（呼叫前先存檔）。
   * 有衝突時以 {@link ConflictException} 失敗；沒有新變更時回呼 empty。
   *
   * @param scope null 表示整份併回（合併提交）；否則是部分併回（單親提交）
   */
  public void merge(Worktree target, String source, Scope scope, Author committer, Consumer<Optional<Applied>> done,
      Consumer<Throwable> failed) {
    var refused = crossWorld(target.branch(), source);
    if (refused != null) {
      failed.accept(refused);
      return;
    }
    transact(target, head -> {
      var theirs = repo.head(source);
      if (repo.isAncestor(theirs, head)) return Optional.empty();
      var base = repo.mergeBase(head, theirs);
      var result = scope == null ? repo.mergeTrees(base, head, theirs, null)
          : repo.mergeTrees(base, head, theirs, scope.dimension(), scope.box());
      if (!result.clean()) throw new ConflictException(result, base, head, theirs);
      return commitMerge(target.branch(), source, scope, head, theirs, base, result.tree(), null, committer);
    }, done, failed);
  }

  /** 草稿從主世界分出來後在這個維度改過的方塊所佔的範圍；主世界或沒改過時為 null。背景執行。 */
  Box changedArea(String branch, String dimension) throws IOException {
    if (branch.equals(Repository.MAIN)) return null;
    var head = repo.head(branch);
    var base = repo.mergeBase(repo.head(Repository.MAIN), head);
    if (base == null || base.equals(head)) return null;
    Box area = null;
    for (var c : TreeDiff.collect(repo.overlays(), repo.commit(base).tree(), repo.commit(head).tree()).cells()) {
      if (!c.dimension().equals(dimension)) continue;
      var p = c.pos();
      area = area == null ? new Box(p.x(), p.y(), p.z(), p.x(), p.y(), p.z())
          : new Box(Math.min(area.minX(), p.x()), Math.min(area.minY(), p.y()), Math.min(area.minZ(), p.z()),
              Math.max(area.maxX(), p.x()), Math.max(area.maxY(), p.y()), Math.max(area.maxZ(), p.z()));
    }
    return area;
  }

  /** 不同世界的草稿不能互相合併（主世界包含所有世界）；可以時回傳 null。 */
  Refused crossWorld(String target, String source) {
    var a = dimension(target);
    var b = dimension(source);
    if (a == null || b == null || a.equals(b)) return null;
    return new Refused(source + " 和 " + target + " 屬於不同的世界，不能合併");
  }

  /** 只屬於一個世界的草稿：撤銷、還原只動那個世界。 */
  private Scope ownWorld(Worktree wt, Scope scope) {
    var dim = dimension(wt.branch());
    return scope != null || dim == null ? scope : new Scope(dim, Box.ALL);
  }

  /** 以合併結果提交。{@code resolvedBy} 是衝突整合時誰改了哪一格。背景執行緒。 */
  Optional<ObjectId> commitMerge(String target, String source, Scope scope, ObjectId head, ObjectId theirs, ObjectId base,
      ObjectId tree, Map<Repository.CellKey, Author> resolvedBy, Author committer) throws IOException {
    var trailers = new TreeMap<String, String>();
    var authors = new HashMap<Repository.CellKey, Author>();
    List<ObjectId> parents;
    String message;
    // 合併提交裡每一格記上草稿裡真正改它的人（部分併回沒有第二個父提交，blame 只能靠這個）。
    authors.putAll(repo.authorsSince(theirs, base));
    if (scope == null) {
      parents = List.of(head, theirs);
      trailers.put("Merged-From", source);
      message = "併回 " + source;
    } else {
      parents = List.of(head);
      trailers.put("Partial-From", source);
      message = "部分併回 " + source;
    }
    if (resolvedBy != null) authors.putAll(resolvedBy);
    return repo.commitTree(target, parents, tree, authors.isEmpty() ? null : Repository.Resolution.authors(authors), genesis,
        Author.MERGE, committer, System.currentTimeMillis(), message, 0, trailers);
  }

  /** 併回前的預覽：會變動的格子、實體與作者。 */
  public record Preview(int cells, int entities, int biomes, Map<String, Integer> authors, List<Changed> changed, ThreeWayMerge.Result result,
      ObjectId ours, ObjectId theirs) {
    public boolean upToDate() {
      return cells == 0 && entities == 0 && biomes == 0 && result.clean();
    }
  }

  /** 一格的變化，給外框顯示用。生態域的 {@code pos} 是生態域格（4×4×4）的最小角。 */
  public record Changed(String dimension, BlockPos pos, Kind kind) {}

  /** {@code BIOME}：生態域改了；{@code BIOME_CONFLICT}：兩邊都改了生態域，保留目標的。 */
  public enum Kind { ADDED, REMOVED, MODIFIED, CONFLICT, BIOME, BIOME_CONFLICT }

  /** 試算合併，不改變任何東西。背景執行。 */
  public CompletableFuture<Preview> preview(String target, String source, Scope scope) {
    var refused = crossWorld(target, source);
    if (refused != null) return CompletableFuture.failedFuture(refused);
    return CompletableFuture.supplyAsync(() -> {
      try {
        var ours = repo.head(target);
        var theirs = repo.head(source);
        var base = repo.mergeBase(ours, theirs);
        var result = scope == null ? repo.mergeTrees(base, ours, theirs, null) : repo.mergeTrees(base, ours, theirs, scope.dimension(), scope.box());
        var diff = TreeDiff.collect(repo.overlays(), repo.commit(ours).tree(), result.tree());
        var by = repo.authorsSince(theirs, base);
        var authors = new TreeMap<String, Integer>();
        var changed = new ArrayList<Changed>();
        var cache = new HashMap<SectionKey, Cell[]>();
        for (var c : diff.cells()) {
          var a = by.get(new Repository.CellKey(c.dimension(), c.pos()));
          authors.merge(a != null ? a.name() : "?", 1, Integer::sum);
          changed.add(new Changed(c.dimension(), c.pos(), kind(c.dimension(), c.pos(), c.before(), c.after(), cache)));
        }
        for (var c : result.cellConflicts()) changed.add(new Changed(c.dimension(), c.pos(), Kind.CONFLICT));
        for (var b : diff.biomes()) changed.add(new Changed(b.key().dimension(), b.corner(), Kind.BIOME));
        for (var b : result.biomeConflicts())
          changed.add(new Changed(b.key().dimension(), b.key().pos(dev.twme.buildgit.core.overlay.SectionOverlay.biomeCorner(b.index())), Kind.BIOME_CONFLICT));
        return new Preview(diff.cells().size(), diff.entities().size(), diff.biomes().size(), authors, changed, result, ours, theirs);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, this::async);
  }

  /** 一格變動的種類；null（和起點世界相同）以起點世界的內容判斷，{@code cache} 存讀過的 section。 */
  Kind kind(String dimension, BlockPos pos, Cell before, Cell after, Map<SectionKey, Cell[]> cache) throws IOException {
    if (before == null || after == null) {
      var key = SectionKey.of(dimension, pos);
      var cells = cache.get(key);
      if (cells == null) {
        var all = new BitSet(4096);
        all.set(0, 4096);
        cells = genesis.readSection(key, all);
        cache.put(key, cells);
      }
      var origin = cells[pos.sectionIndex()] == null ? Cell.AIR : cells[pos.sectionIndex()];
      if (before == null) before = origin;
      if (after == null) after = origin;
    }
    if (after.isAir()) return Kind.REMOVED;
    if (before.isAir()) return Kind.ADDED;
    return Kind.MODIFIED;
  }

  // ---- 還原（設計 §5.4） ----

  /** 撤銷某一次存檔：以三方合併套用它的反向變更。有衝突時以 {@link ConflictException} 失敗。 */
  public void revert(Worktree wt, ObjectId bad, Author author, Consumer<Optional<Applied>> done, Consumer<Throwable> failed) {
    var scope = ownWorld(wt, null);
    transact(wt, head -> {
      var commit = repo.commit(bad);
      if (commit.parents().isEmpty()) throw new IOException("不能撤銷初始化的存檔");
      if (!repo.isAncestor(bad, head)) throw new IOException("這個存檔不在 " + wt.branch() + " 的歷史裡");
      var parent = commit.parents().getFirst();
      var result = scope == null ? repo.mergeTrees(bad, head, parent, null) : repo.mergeTrees(bad, head, parent, scope.dimension(), scope.box());
      if (!result.clean()) throw new ConflictException(result, bad, head, parent);
      return repo.commitTree(wt.branch(), List.of(head), result.tree(), null, genesis, author, author, System.currentTimeMillis(),
          "撤銷 " + bad.abbrev(), 0, new TreeMap<>(Map.of("Reverts", bad.hex())));
    }, done, failed);
  }

  /** 讓分支（或範圍內）回到某個版本的內容，產生新存檔。 */
  public void restore(Worktree wt, ObjectId target, Scope requested, Author author, Consumer<Optional<Applied>> done,
      Consumer<Throwable> failed) {
    var scope = ownWorld(wt, requested);
    transact(wt, head -> {
      var tree = scope == null ? repo.restoreTree(head, target, null) : repo.restoreTree(head, target, scope.dimension(), scope.box());
      return repo.commitTree(wt.branch(), List.of(head), tree, null, genesis, author, author, System.currentTimeMillis(),
          "還原到 " + target.abbrev() + (requested == null ? "" : "（範圍）"), 0, new TreeMap<>(Map.of("Restored-From", target.hex())));
    }, done, failed);
  }

  /** 玩家剛離開或傳送出某個世界：空了的檢視世界收掉。 */
  public void left(ServerLevel world) {
    platform.later(20, () -> {
      if (world.players().isEmpty() && loaded(world)) discardIfEmpty(world);
    });
  }

  /** 玩家目前位置的方塊座標。 */
  static BlockPos pos(ServerPlayer player) {
    var p = player.blockPosition();
    return new BlockPos(p.getX(), p.getY(), p.getZ());
  }
}
