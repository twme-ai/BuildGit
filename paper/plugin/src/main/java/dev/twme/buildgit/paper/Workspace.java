package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.anvil.AnvilSource;
import dev.twme.buildgit.core.anvil.RegionSynthesizer;
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
import dev.twme.buildgit.paper.nms.LiveReader;
import dev.twme.buildgit.paper.nms.LiveWriter;
import dev.twme.buildgit.paper.nms.RegionHook;
import dev.twme.buildgit.paper.track.Tracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 伺服器上的版本庫（設計 §2.1）：{@code <伺服器根目錄>/buildgit/}，以及每個分支的工作區與世界。
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
  private static final String VIEW_PREFIX = "bgview_";
  private static final int MAX_SHAPE_FIX = 1 << 20;

  private final JavaPlugin plugin;
  private final Tracker tracker;
  private final Path dir;
  private Repository repo;
  private AnvilSource genesis;
  private GenesisRecorder genesisRecorder;
  private MergeSessions merges;
  private final Map<String, Worktree> worktrees = new LinkedHashMap<>();
  /** 世界 UUID → 所屬工作區。 */
  private final Map<UUID, Worktree> owners = new HashMap<>();
  /** 正在建立的世界：名稱 → 合成來源。在 WorldInitEvent 掛上合成讀取。 */
  private final Map<String, Pending> creating = new HashMap<>();
  /** BuildGit 建立的世界 → 內容來源（新生成 chunk 補覆寫時用）。 */
  private final Map<UUID, Pending> sources = new ConcurrentHashMap<>();
  /** 檢視世界 → 版本。 */
  private final Map<UUID, View> views = new HashMap<>();
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

  public Workspace(JavaPlugin plugin, Tracker tracker) {
    this.plugin = plugin;
    this.tracker = tracker;
    this.dir = Path.of("").toAbsolutePath().resolve("buildgit");
  }

  Path dir() {
    return dir;
  }

  Logger log() {
    return plugin.getLogger();
  }

  JavaPlugin plugin() {
    return plugin;
  }

  Tracker tracker() {
    return tracker;
  }

  AnvilSource genesis() {
    return genesis;
  }

  MergeSessions merges() {
    return merges;
  }

  void async(Runnable r) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
  }

  void sync(Runnable r) {
    if (Bukkit.isPrimaryThread()) r.run();
    else Bukkit.getScheduler().runTask(plugin, r);
  }

  static Throwable unwrap(Throwable t) {
    return t instanceof CompletionException c && c.getCause() != null ? c.getCause() : t;
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
  public Worktree worktree(World world) {
    return world == null ? null : owners.get(world.getUID());
  }

  public Worktree worktree(String branch) {
    return worktrees.get(branch);
  }

  public Collection<Worktree> worktrees() {
    return List.copyOf(worktrees.values());
  }

  /** 檢視世界的版本；不是檢視世界時為 null。 */
  public View view(World world) {
    return world == null ? null : views.get(world.getUID());
  }

  /** 世界在版本中的維度 id：分支與檢視世界對應回主世界的維度。 */
  public String logicalDimension(World world) {
    var v = views.get(world.getUID());
    if (v != null) return v.dimension();
    var d = tracker.logicalDimension(world);
    return d != null ? d : Tracker.dimensionId(world);
  }

  /** 主分支的世界：伺服器的主世界與同名的地獄、終界。 */
  public List<World> mainWorlds() {
    var worlds = Bukkit.getWorlds();
    if (worlds.isEmpty()) return List.of();
    var main = worlds.getFirst();
    var result = new ArrayList<World>();
    result.add(main);
    for (var suffix : List.of("_nether", "_the_end")) {
      var w = Bukkit.getWorld(main.getName() + suffix);
      if (w != null) result.add(w);
    }
    // 26.x 也可能把三個維度放在同一個世界資料夾；依維度 key 去重。
    var seen = new HashSet<String>();
    result.removeIf(w -> !seen.add(Tracker.dimensionId(w)));
    return result;
  }

  private World mainWorld(String dimension) {
    for (var w : mainWorlds()) if (Tracker.dimensionId(w).equals(dimension)) return w;
    return null;
  }

  Path genesisDir(String dimension) {
    int colon = dimension.indexOf(':');
    return dir.resolve("genesis").resolve(dimension.substring(0, colon)).resolve(dimension.substring(colon + 1));
  }

  // ---- 開啟 ----

  /** 版本庫已存在時開啟並開始追蹤。 */
  public void open() throws IOException {
    if (!Repository.exists(dir)) return;
    repo = Repository.open(dir);
    genesis = new AnvilSource(d -> {
      var p = genesisDir(d);
      return Files.isDirectory(p) ? p : null;
    });
    genesisRecorder = new GenesisRecorder(this::genesisDir, genesis, plugin.getLogger());
    var main = new Worktree(this, Repository.MAIN);
    worktrees.put(Repository.MAIN, main);
    for (var w : mainWorlds()) {
      main.addWorld(Tracker.dimensionId(w), w);
      owners.put(w.getUID(), main);
    }
    deleteStaleViews();
    merges = new MergeSessions(this);
    merges.load();
  }

  public void close() {
    for (var w : List.copyOf(views.keySet())) {
      var world = Bukkit.getWorld(w);
      if (world != null) discardWorld(world, null);
    }
    worktrees.values().forEach(Worktree::close);
    worktrees.clear();
    owners.clear();
    sources.clear();
    tracker.detachAll();
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

  /** 存檔讀取起點世界之前呼叫（背景執行緒）：等新生成 chunk 的寫入完成。 */
  void awaitGenesis() {
    genesisRecorder.awaitIdle();
  }

  // ---- init ----

  /**
   * 開始版本控制：存檔、把主世界各維度的 region 與實體檔複製成凍結的起點世界、建立版本庫。
   * 在主執行緒同步執行，期間伺服器暫停。
   *
   * @return 起點世界大小（位元組）
   */
  public long init(Author author) throws IOException {
    if (Repository.exists(dir)) throw new IOException("已經初始化");
    long size = 0;
    for (var world : mainWorlds()) {
      LiveReader.saveNow(world);
      var source = LiveReader.directory(world);
      var target = genesisDir(Tracker.dimensionId(world));
      for (var sub : List.of("region", "entities")) size += copy(source.resolve(sub), target.resolve(sub));
    }
    Repository.init(dir, author, System.currentTimeMillis());
    open();
    return size;
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
   * 1. 受追蹤的世界：生成結果補進起點世界（設計 §2.3），已有的不覆寫；
   * 2. BuildGit 建立的世界：這個 chunk 若在覆寫表中有內容（起點世界沒有、所以沒被合成），套用上去。
   */
  public void newChunk(Chunk chunk) {
    var world = chunk.getWorld();
    var owner = worktree(world);
    if (owner != null) genesisRecorder.record(tracker.logicalDimension(world), chunk);
    var source = sources.get(world.getUID());
    if (source == null) return;
    int cx = chunk.getX(), cz = chunk.getZ();
    async(() -> {
      try {
        var overlay = new OverlayReader(repo.overlays(), source.tree().tree()).chunk(source.dimension(), cx, cz);
        if (overlay.isEmpty()) return;
        sync(() -> tracker.systemWrite(() -> overlay.forEach((sy, section) -> section.cells().forEach((index, cell) -> {
          int x = cx << 4 | index & 15, y = sy << 4 | index >> 8, z = cz << 4 | index >> 4 & 15;
          try {
            LiveWriter.setCell(world, x, y, z, cell);
          } catch (IOException e) {
            log().log(Level.WARNING, "無法套用覆寫 " + x + " " + y + " " + z, e);
          }
        }))));
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
  private YamlConfiguration branchesFile() {
    return YamlConfiguration.loadConfiguration(branchesPath().toFile());
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

  /** 建立分支，指向來源分支的最新存檔。 */
  public void createBranch(String name, String from) throws IOException {
    createBranch(name, from, repo.head(from), false);
  }

  void createBranch(String name, String from, ObjectId at, boolean scratch) throws IOException {
    if (!BRANCH_NAME.matcher(name).matches() || !RefStore.validName(name)) throw new IOException("名稱只能用小寫英數、底線、連字號：" + name);
    if (isScratch(name) != scratch) throw new IOException("草稿名稱不能以 " + MERGE_PREFIX + " 開頭");
    repo.createBranch(name, at);
    var yaml = branchesFile();
    yaml.set(name + ".source", from);
    yaml.set(name + ".created", System.currentTimeMillis());
    if (scratch) yaml.set(name + ".kind", "merge");
    yaml.save(branchesPath().toFile());
  }

  /**
   * 刪除草稿：卸載並刪除世界、工作區與 ref（主執行緒）。世界裡的玩家傳送回來源世界。
   *
   * @param force 有未存的變更也刪
   */
  public void deleteBranch(String name, boolean force) throws IOException {
    if (name.equals(Repository.MAIN)) throw new IOException("不能刪除主世界");
    repo.head(name);
    var yaml = branchesFile();
    for (var other : yaml.getKeys(false))
      if (!other.equals(name) && name.equals(yaml.getString(other + ".source")) && repo.refs().get(other).isPresent())
        throw new IOException("草稿 " + other + " 以它為來源，請先刪除或併回 " + other);
    var wt = worktrees.get(name);
    if (wt != null && !force && !wt.table().isEmpty()) throw new IOException("有未存的變更（加上 --force 仍要刪除）");
    if (wt != null && wt.isCommitting()) throw new IOException("正在存檔或併回，請稍後再試");
    var fallback = worktrees.get(source(name));
    if (fallback == null) fallback = main();
    if (wt != null) {
      worktrees.remove(name);
      wt.close();
      for (var world : List.copyOf(wt.worlds().values())) discardWorld(world, fallback);
    }
    for (var w : mainWorlds()) {
      var folder = dimensionsDir().resolve(worldName(name, Tracker.dimensionId(w)));
      if (Bukkit.getWorld(worldName(name, Tracker.dimensionId(w))) == null) deleteLater(folder);
    }
    deleteLater(dir.resolve("worktree").resolve(name));
    repo.deleteBranch(name);
    yaml.set(name, null);
    yaml.save(branchesPath().toFile());
  }

  /** 草稿某維度的世界名稱。 */
  static String worldName(String branch, String dimension) {
    return WORLD_PREFIX + branch + suffix(dimension);
  }

  private static String suffix(String dimension) {
    return switch (dimension) {
      case "minecraft:the_nether" -> "_nether";
      case "minecraft:the_end" -> "_the_end";
      default -> "";
    };
  }

  /** 世界資料夾的上層（26.x 的 {@code world/dimensions/minecraft}）。 */
  private Path dimensionsDir() {
    return LiveReader.directory(Bukkit.getWorlds().getFirst()).getParent();
  }

  /**
   * 開啟分支的工作區與世界（主執行緒）。世界不存在時建立：種子與產生器設定照抄主世界，不複製任何 region 檔。
   */
  public Worktree openBranch(String branch) throws IOException {
    if (branch.equals(Repository.MAIN)) return main();
    var existing = worktrees.get(branch);
    if (existing != null) return existing;
    repo.head(branch); // 不存在時丟出例外
    var wt = new Worktree(this, branch);
    worktrees.put(branch, wt);
    try {
      for (var mainWorld : mainWorlds()) {
        String dim = Tracker.dimensionId(mainWorld);
        var world = createWorld(worldName(branch, dim), mainWorld, new Pending(dim, () -> repo.commit(repo.head(branch)).tree()));
        wt.addWorld(dim, world);
        owners.put(world.getUID(), wt);
      }
    } catch (IOException | RuntimeException e) {
      worktrees.remove(branch);
      wt.close();
      throw e;
    }
    return wt;
  }

  private World createWorld(String name, World template, Pending source) throws IOException {
    var world = Bukkit.getWorld(name);
    if (world == null) {
      creating.put(name, source);
      try {
        world = new WorldCreator(name).copy(template).createWorld();
      } finally {
        creating.remove(name);
      }
      if (world == null) throw new IOException("無法建立世界 " + name);
    }
    sources.put(world.getUID(), source);
    return world;
  }

  /** 世界初始化（任何 chunk 讀取之前）：BuildGit 的世界掛上合成讀取。 */
  public void worldInit(World world) {
    var source = creating.get(world.getName());
    if (source == null) return;
    try {
      RegionHook.install(world,
          (target, rx, rz) -> synthesizer(source).blocks(target, rx, rz),
          (target, rx, rz) -> synthesizer(source).entities(target, rx, rz),
          plugin.getLogger());
    } catch (ReflectiveOperationException | RuntimeException e) {
      // 沒有合成讀取的世界會變成全新生成的世界：拒絕使用比給錯內容好。
      throw new IllegalStateException("無法在 " + world.getName() + " 掛上合成讀取", e);
    }
  }

  /** 某個覆寫表、某個維度的合成器。IO 執行緒呼叫。 */
  private RegionSynthesizer synthesizer(Pending source) throws IOException {
    var tree = source.tree().tree();
    String key = tree.hex() + "/" + source.dimension();
    synchronized (synthesizers) {
      var s = synthesizers.get(key);
      if (s != null) return s;
      var g = genesisDir(source.dimension());
      s = new RegionSynthesizer(Files.isDirectory(g) ? g : null, new OverlayReader(repo.overlays(), tree), source.dimension(),
          LiveReader.dataVersion());
      synthesizers.put(key, s);
      return s;
    }
  }

  // ---- 檢視世界（設計 §6.5） ----

  static String viewName(ObjectId commit, String dimension) {
    return VIEW_PREFIX + commit.hex().substring(0, 10) + suffix(dimension);
  }

  /** 開啟某個版本、某個維度的唯讀世界（主執行緒）。 */
  public World openView(ObjectId commit, String dimension) throws IOException {
    var template = mainWorld(dimension);
    if (template == null) throw new IOException("主世界沒有維度 " + dimension);
    var tree = repo.commit(commit).tree();
    var world = createWorld(viewName(commit, dimension), template, new Pending(dimension, () -> tree));
    views.put(world.getUID(), new View(commit, dimension));
    return world;
  }

  /** 沒有玩家的檢視世界卸載並刪除。 */
  public void discardIfEmpty(World world) {
    if (views.containsKey(world.getUID()) && world.getPlayers().isEmpty()) discardWorld(world, null);
  }

  private void deleteStaleViews() {
    try (var s = Files.list(dimensionsDir())) {
      for (var p : s.filter(p -> p.getFileName().toString().startsWith(VIEW_PREFIX)).toList())
        if (Bukkit.getWorld(p.getFileName().toString()) == null) deleteLater(p);
    } catch (IOException e) {
      log().log(Level.WARNING, "無法清理舊的檢視世界", e);
    }
  }

  /**
   * 卸載並刪除 BuildGit 建立的世界（主執行緒）。裡面的玩家傳送到 {@code fallback} 的同一個位置；沒給時回主世界。
   */
  void discardWorld(World world, Worktree fallback) {
    String dim = logicalDimension(world);
    var to = fallback != null ? fallback : main();
    for (var p : world.getPlayers()) {
      var target = to != null ? to.world(dim) : null;
      if (target == null) target = Bukkit.getWorlds().getFirst();
      var loc = p.getLocation().clone();
      loc.setWorld(target);
      p.teleport(loc);
    }
    var folder = LiveReader.directory(world);
    owners.remove(world.getUID());
    sources.remove(world.getUID());
    views.remove(world.getUID());
    if (!Bukkit.unloadWorld(world, false)) {
      log().warning("無法卸載世界 " + world.getName() + "，資料夾保留");
      return;
    }
    deleteLater(folder);
  }

  /** 下一刻在背景刪除資料夾（等伺服器關閉檔案）。 */
  private void deleteLater(Path folder) {
    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
      try {
        deleteRecursively(folder);
      } catch (IOException e) {
        log().log(Level.WARNING, "無法刪除 " + folder, e);
      }
    }, 20);
  }

  static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (var s = Files.walk(path)) {
      for (var p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }

  // ---- 傳送 ----

  /** 傳送到某個世界的同一個座標。 */
  static void teleport(Player player, World world) {
    var loc = player.getLocation().clone();
    loc.setWorld(world);
    player.teleportAsync(loc);
  }

  /** 傳送到某個分支、玩家目前所在維度的世界。 */
  void teleport(Player player, Worktree to) {
    var world = to.world(logicalDimension(player.getWorld()));
    if (world == null) world = to.worlds().values().iterator().next();
    teleport(player, world);
  }

  // ---- 寫入版本並套用到世界 ----

  /** 一次寫入的結果。{@code shapes} 是形狀修補改到的格子數（另存成一個自動存檔）。 */
  public record Applied(ObjectId commit, int cells, int entities, int shapes) {}

  /** 在背景執行緒、持有分支存檔鎖時計算新提交；回傳 empty 表示沒有變化。 */
  interface TreeOp {
    Optional<ObjectId> run(ObjectId head) throws IOException;
  }

  /**
   * 在分支上提交 {@code op} 算出的新版本，並把差異寫進分支的世界，最後做形狀修補（設計 §5.2）。
   * 呼叫前應先存檔，讓世界與分支最新存檔一致。主執行緒呼叫，結果在主執行緒回呼。
   */
  void transact(Worktree wt, TreeOp op, Consumer<Optional<Applied>> done, Consumer<Throwable> failed) {
    if (!wt.lock()) {
      failed.accept(new IOException("分支正在存檔或併回，請稍後再試：" + wt.branch()));
      return;
    }
    CompletableFuture.supplyAsync(() -> {
      try {
        awaitGenesis();
        var head = repo.head(wt.branch());
        var before = repo.commit(head).tree();
        var id = op.run(head);
        if (id.isEmpty()) return null;
        var diff = TreeDiff.collect(repo.overlays(), before, repo.commit(id.get()).tree());
        return new Plan(id.get(), diff.cells().size(), diff.entities().size(), resolve(diff));
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
      apply(wt, plan.writes()).whenComplete((written, applyError) -> sync(() -> {
        int shapes = 0;
        if (applyError != null) log().log(Level.SEVERE, "版本已提交，但寫入世界時失敗；未寫入的部分在下次讀取時會不一致", unwrap(applyError));
        else shapes = fixShapes(wt, written);
        wt.unlock();
        if (shapes > 0)
          wt.save(Author.MERGE, "形狀修補", Commit.AUTO, r -> {}, e -> log().log(Level.WARNING, "形狀修補存檔失敗，下次存檔時再收進去", e));
        done.accept(Optional.of(new Applied(plan.commit(), plan.cells(), plan.entities(), shapes)));
      }));
    }));
  }

  private record Plan(ObjectId commit, int cells, int entities, Writes writes) {}

  /** 要寫進世界的最終內容（已把「回到起點」換成起點世界的實際內容）。 */
  private record Writes(Map<String, Map<Long, List<CellWrite>>> cells, List<EntityWrite> entities) {}

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
    return new Writes(cells, entities);
  }

  private static long chunkKey(int x, int z) {
    return (long) x << 32 | z & 0xFFFFFFFFL;
  }

  /** 逐 chunk 載入並寫入（主執行緒）。實體等該 chunk 的實體載入後才處理。回傳寫入的格子。 */
  private CompletableFuture<Map<String, List<BlockPos>>> apply(Worktree wt, Writes writes) {
    var written = new ConcurrentHashMap<String, List<BlockPos>>();
    var futures = new ArrayList<CompletableFuture<?>>();
    writes.cells().forEach((dim, byChunk) -> {
      var world = wt.world(dim);
      if (world == null) return;
      var list = Collections.synchronizedList(new ArrayList<BlockPos>());
      written.put(dim, list);
      byChunk.forEach((key, cells) -> futures.add(world.getChunkAtAsync((int) (key >> 32), (int) (long) key, true).thenAccept(chunk ->
          tracker.systemWrite(() -> {
            for (var w : cells)
              try {
                LiveWriter.setCell(world, w.pos().x(), w.pos().y(), w.pos().z(), w.cell());
                list.add(w.pos());
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
          }))));
    });
    CompletableFuture<Void> blocks = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    return blocks.thenCompose(ignored -> {
      CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
      for (var e : writes.entities()) {
        var world = wt.world(e.dimension());
        if (world == null) continue;
        chain = chain.thenCompose(x -> world.getChunkAtAsync(e.chunkX(), e.chunkZ(), true)).thenCompose(this::entitiesLoaded).thenAccept(chunk ->
            tracker.systemWrite(() -> {
              try {
                if (e.state().isEmpty()) LiveWriter.removeEntity(world, e.uuid());
                else LiveWriter.spawnEntity(world, e.state().get());
              } catch (IOException ex) {
                throw new UncheckedIOException(ex);
              }
            }));
      }
      return chain;
    }).thenApply(ignored -> written);
  }

  /** Paper 的實體與方塊分開載入：等這個 chunk 的實體載入完成（最多約 5 秒）。 */
  CompletableFuture<Chunk> entitiesLoaded(Chunk chunk) {
    var result = new CompletableFuture<Chunk>();
    var tries = new int[] {0};
    Runnable[] check = new Runnable[1];
    check[0] = () -> {
      if (chunk.isEntitiesLoaded() || ++tries[0] > 100) result.complete(chunk);
      else Bukkit.getScheduler().runTaskLater(plugin, check[0], 1);
    };
    sync(check[0]);
    return result;
  }

  private static final int[][] AROUND = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

  /**
   * 形狀修補（設計 §5.2，主執行緒）：寫入的格子與它們的鄰居依目前鄰居重算形狀，
   * 真的變了的以作者 {@code merge} 標記，之後存成一個自動存檔。
   */
  private int fixShapes(Worktree wt, Map<String, List<BlockPos>> written) {
    int[] fixed = {0};
    tracker.systemWrite(() -> written.forEach((dim, list) -> {
      var world = wt.world(dim);
      if (world == null || list.size() > MAX_SHAPE_FIX) return;
      var d = wt.table().dimension(dim);
      var seen = new HashSet<BlockPos>();
      for (var p : List.copyOf(list))
        for (var o : AROUND) {
          var q = new BlockPos(p.x() + o[0], p.y() + o[1], p.z() + o[2]);
          if (q.y() < world.getMinHeight() || q.y() >= world.getMaxHeight() || !seen.add(q)) continue;
          if (!world.isChunkLoaded(q.x() >> 4, q.z() >> 4)) continue;
          if (LiveWriter.fixShape(world, q.x(), q.y(), q.z())) {
            d.markCell(q, Author.MERGE);
            fixed[0]++;
          }
        }
    }));
    return fixed[0];
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

  /** 以合併結果提交。{@code resolvedBy} 是衝突整合時誰改了哪一格。背景執行緒。 */
  Optional<ObjectId> commitMerge(String target, String source, Scope scope, ObjectId head, ObjectId theirs, ObjectId base,
      ObjectId tree, Map<Repository.CellKey, Author> resolvedBy, Author committer) throws IOException {
    var trailers = new TreeMap<String, String>();
    var authors = new HashMap<Repository.CellKey, Author>();
    List<ObjectId> parents;
    String message;
    if (scope == null) {
      parents = List.of(head, theirs);
      trailers.put("Merged-From", source);
      message = "併回 " + source;
    } else {
      // 部分併回沒有第二個父提交，blame 追不到原作者：把草稿裡的作者直接記進來。
      parents = List.of(head);
      authors.putAll(repo.authorsSince(theirs, base));
      trailers.put("Partial-From", source);
      message = "部分併回 " + source;
    }
    if (resolvedBy != null) authors.putAll(resolvedBy);
    return repo.commitTree(target, parents, tree, authors.isEmpty() ? null : Repository.Resolution.authors(authors), genesis,
        Author.MERGE, committer, System.currentTimeMillis(), message, 0, trailers);
  }

  /** 併回前的預覽：會變動的格子、實體與作者。 */
  public record Preview(int cells, int entities, Map<String, Integer> authors, List<Changed> changed, ThreeWayMerge.Result result,
      ObjectId ours, ObjectId theirs) {
    public boolean upToDate() {
      return cells == 0 && entities == 0 && result.clean();
    }
  }

  /** 一格的變化，給外框顯示用。 */
  public record Changed(String dimension, BlockPos pos, Kind kind) {}

  public enum Kind { ADDED, REMOVED, MODIFIED, CONFLICT }

  /** 試算合併，不改變任何東西。背景執行。 */
  public CompletableFuture<Preview> preview(String target, String source, Scope scope) {
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
        for (var c : diff.cells()) {
          var a = by.get(new Repository.CellKey(c.dimension(), c.pos()));
          authors.merge(a != null ? a.name() : "?", 1, Integer::sum);
          changed.add(new Changed(c.dimension(), c.pos(), kind(c.before(), c.after())));
        }
        for (var c : result.cellConflicts()) changed.add(new Changed(c.dimension(), c.pos(), Kind.CONFLICT));
        return new Preview(diff.cells().size(), diff.entities().size(), authors, changed, result, ours, theirs);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, this::async);
  }

  /** 變化的種類（null 表示與起點相同，視為修改）。 */
  static Kind kind(Cell before, Cell after) {
    if (after != null && after.isAir()) return Kind.REMOVED;
    if (before != null && before.isAir()) return Kind.ADDED;
    return Kind.MODIFIED;
  }

  // ---- 還原（設計 §5.4） ----

  /** 撤銷某一次存檔：以三方合併套用它的反向變更。有衝突時以 {@link ConflictException} 失敗。 */
  public void revert(Worktree wt, ObjectId bad, Author author, Consumer<Optional<Applied>> done, Consumer<Throwable> failed) {
    transact(wt, head -> {
      var commit = repo.commit(bad);
      if (commit.parents().isEmpty()) throw new IOException("不能撤銷初始化的存檔");
      if (!repo.isAncestor(bad, head)) throw new IOException("這個存檔不在 " + wt.branch() + " 的歷史裡");
      var parent = commit.parents().getFirst();
      var result = repo.mergeTrees(bad, head, parent, null);
      if (!result.clean()) throw new ConflictException(result, bad, head, parent);
      return repo.commitTree(wt.branch(), List.of(head), result.tree(), null, genesis, author, author, System.currentTimeMillis(),
          "撤銷 " + bad.abbrev(), 0, new TreeMap<>(Map.of("Reverts", bad.hex())));
    }, done, failed);
  }

  /** 讓分支（或範圍內）回到某個版本的內容，產生新存檔。 */
  public void restore(Worktree wt, ObjectId target, Scope scope, Author author, Consumer<Optional<Applied>> done,
      Consumer<Throwable> failed) {
    transact(wt, head -> {
      var tree = scope == null ? repo.restoreTree(head, target, null) : repo.restoreTree(head, target, scope.dimension(), scope.box());
      return repo.commitTree(wt.branch(), List.of(head), tree, null, genesis, author, author, System.currentTimeMillis(),
          "還原到 " + target.abbrev() + (scope == null ? "" : "（範圍）"), 0, new TreeMap<>(Map.of("Restored-From", target.hex())));
    }, done, failed);
  }

  /** 玩家剛離開或傳送出某個世界：空了的檢視世界收掉。 */
  void left(World world) {
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      if (world.getPlayers().isEmpty() && Bukkit.getWorld(world.getUID()) != null) discardIfEmpty(world);
    }, 20);
  }

  /** 某個世界玩家目前位置的方塊座標。 */
  static BlockPos pos(Location loc) {
    return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
  }
}
