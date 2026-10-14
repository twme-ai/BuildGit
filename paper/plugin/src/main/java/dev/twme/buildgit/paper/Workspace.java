package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.anvil.AnvilSource;
import dev.twme.buildgit.core.anvil.RegionSynthesizer;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.overlay.ThreeWayMerge;
import dev.twme.buildgit.core.overlay.TreeDiff;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 伺服器上的版本庫（設計 §2.1）：{@code <伺服器根目錄>/buildgit/}，以及每個分支的工作區與世界。
 *
 * <p>主分支的世界是伺服器原本的主世界與地獄、終界。其他分支（草稿）的世界由這裡建立，
 * 內容在第一次讀到時由「起點世界＋分支覆寫表」合成（§4.2）。
 */
public final class Workspace {
  /** 草稿名稱同時用在世界名稱，所以只允許小寫英數、底線與連字號。 */
  public static final Pattern BRANCH_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
  private static final String WORLD_PREFIX = "bg_";

  private final JavaPlugin plugin;
  private final Tracker tracker;
  private final Path dir;
  private Repository repo;
  private AnvilSource genesis;
  private GenesisRecorder genesisRecorder;
  private final Map<String, Worktree> worktrees = new LinkedHashMap<>();
  /** 世界 UUID → 所屬工作區。 */
  private final Map<UUID, Worktree> owners = new HashMap<>();
  /** 正在建立的分支世界：世界名稱 → (分支, 維度)。在 WorldInitEvent 掛上合成讀取。 */
  private final Map<String, BranchWorld> creating = new HashMap<>();
  private final Map<String, RegionSynthesizer> synthesizers = new ConcurrentHashMap<>();

  private record BranchWorld(String branch, String dimension) {}

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

  Tracker tracker() {
    return tracker;
  }

  AnvilSource genesis() {
    return genesis;
  }

  void async(Runnable r) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
  }

  void sync(Runnable r) {
    if (Bukkit.isPrimaryThread()) r.run();
    else Bukkit.getScheduler().runTask(plugin, r);
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

  /** 世界所屬的工作區；不受版本控制時為 null。 */
  public Worktree worktree(World world) {
    return world == null ? null : owners.get(world.getUID());
  }

  public Worktree worktree(String branch) {
    return worktrees.get(branch);
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
  }

  public void close() {
    worktrees.values().forEach(Worktree::close);
    worktrees.clear();
    owners.clear();
    tracker.detachAll();
    if (genesisRecorder != null) genesisRecorder.close();
    if (genesis != null) genesis.close();
    repo = null;
  }

  /** 定期把標記寫到磁碟（非同步執行緒）。 */
  public void flushJournal() {
    for (var w : List.copyOf(worktrees.values())) w.flushJournal();
  }

  public void autosaveTick(Consumer<Worktree.SaveResult> done) {
    if (repo == null) return;
    for (var w : List.copyOf(worktrees.values())) w.autosaveTick(done);
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
   * 受追蹤的世界剛生成一個 chunk（主執行緒）：
   * 1. 生成結果補進起點世界（設計 §2.3），已有的不覆寫；
   * 2. 分支世界裡，這個 chunk 若在覆寫表中有內容（起點世界沒有、所以沒被合成），套用上去。
   */
  public void newChunk(Chunk chunk) {
    var owner = worktree(chunk.getWorld());
    if (owner == null) return;
    String dim = tracker.logicalDimension(chunk.getWorld());
    genesisRecorder.record(dim, chunk);
    if (owner == main()) return;
    var world = chunk.getWorld();
    int cx = chunk.getX(), cz = chunk.getZ();
    async(() -> {
      try {
        var overlay = repo.reader(repo.head(owner.branch())).chunk(dim, cx, cz);
        if (overlay.isEmpty()) return;
        sync(() -> tracker.systemWrite(() -> {
          overlay.forEach((sy, section) -> section.cells().forEach((index, cell) -> {
            int x = cx << 4 | index & 15, y = sy << 4 | index >> 8, z = cz << 4 | index >> 4 & 15;
            try {
              LiveWriter.setCell(world, x, y, z, cell);
            } catch (IOException e) {
              log().log(Level.WARNING, "無法套用覆寫 " + x + " " + y + " " + z, e);
            }
          }));
        }));
      } catch (IOException e) {
        log().log(Level.WARNING, "無法讀取 chunk " + cx + "," + cz + " 的覆寫", e);
      }
    });
  }

  // ---- 分支 ----

  /** branches.yml：分支 → 來源分支。 */
  private YamlConfiguration branchesFile() {
    return YamlConfiguration.loadConfiguration(dir.resolve("branches.yml").toFile());
  }

  /** 草稿的來源分支（併回的目標）；不知道時為 main。 */
  public String source(String branch) {
    return branchesFile().getString(branch + ".source", Repository.MAIN);
  }

  public SortedMap<String, ObjectId> branches() throws IOException {
    return repo.refs().list();
  }

  /** 建立分支，指向來源分支的最新存檔。 */
  public void createBranch(String name, String from) throws IOException {
    if (!BRANCH_NAME.matcher(name).matches() || !RefStore.validName(name)) throw new IOException("名稱只能用小寫英數、底線、連字號：" + name);
    repo.createBranch(name, repo.head(from));
    var yaml = branchesFile();
    yaml.set(name + ".source", from);
    yaml.set(name + ".created", System.currentTimeMillis());
    yaml.save(dir.resolve("branches.yml").toFile());
  }

  /** 草稿某維度的世界名稱。 */
  static String worldName(String branch, String dimension) {
    String base = WORLD_PREFIX + branch;
    return switch (dimension) {
      case "minecraft:the_nether" -> base + "_nether";
      case "minecraft:the_end" -> base + "_the_end";
      default -> base;
    };
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
        String name = worldName(branch, dim);
        var world = Bukkit.getWorld(name);
        if (world == null) {
          creating.put(name, new BranchWorld(branch, dim));
          try {
            world = new WorldCreator(name).copy(mainWorld).createWorld();
          } finally {
            creating.remove(name);
          }
          if (world == null) throw new IOException("無法建立世界 " + name);
        }
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

  /** 世界初始化（任何 chunk 讀取之前）：分支世界掛上合成讀取。 */
  public void worldInit(World world) {
    var bw = creating.get(world.getName());
    if (bw == null) return;
    try {
      RegionHook.install(world,
          (target, rx, rz) -> synthesizer(bw).blocks(target, rx, rz),
          (target, rx, rz) -> synthesizer(bw).entities(target, rx, rz),
          plugin.getLogger());
    } catch (ReflectiveOperationException | RuntimeException e) {
      // 沒有合成讀取的分支世界會變成全新生成的世界：拒絕使用比給錯內容好。
      throw new IllegalStateException("無法在 " + world.getName() + " 掛上合成讀取", e);
    }
  }

  /** 分支目前最新存檔的合成器；分支前進後換新的。IO 執行緒呼叫。 */
  private RegionSynthesizer synthesizer(BranchWorld bw) throws IOException {
    var head = repo.head(bw.branch());
    String key = bw.branch() + "/" + bw.dimension();
    var s = synthesizers.get(key);
    if (s != null && s.overlay().root().equals(repo.commit(head).tree())) return s;
    var g = genesisDir(bw.dimension());
    s = new RegionSynthesizer(Files.isDirectory(g) ? g : null, repo.reader(head), bw.dimension(), LiveReader.dataVersion());
    synthesizers.put(key, s);
    return s;
  }

  // ---- 併回（設計 §5.2、§6.2） ----

  public sealed interface MergeOutcome {
    record UpToDate() implements MergeOutcome {}

    record Merged(ObjectId commit, int cells, int entities) implements MergeOutcome {}

    record Conflicts(List<ThreeWayMerge.CellConflict> cells, List<ThreeWayMerge.EntityConflict> entities) implements MergeOutcome {}
  }

  /**
   * 把 {@code source} 合併進 {@code target}，並寫進目標分支的世界。
   * 兩邊都必須沒有未存的變更（呼叫前先存檔）。主執行緒呼叫，結果在主執行緒回呼。
   */
  public void merge(String target, String source, Author committer, Consumer<MergeOutcome> done, Consumer<Throwable> failed) {
    var wt = worktrees.get(target);
    if (wt == null || !wt.lock()) {
      failed.accept(new IOException("目標分支正在存檔或合併：" + target));
      return;
    }
    CompletableFuture.supplyAsync(() -> {
      try {
        var ours = repo.head(target);
        var theirs = repo.head(source);
        if (repo.isAncestor(theirs, ours)) return new Plan(new MergeOutcome.UpToDate(), null);
        var result = repo.mergeTrees(repo.mergeBase(ours, theirs), ours, theirs, null);
        if (!result.clean()) return new Plan(new MergeOutcome.Conflicts(result.cellConflicts(), result.entityConflicts()), null);
        awaitGenesis();
        var id = repo.commitTree(target, List.of(ours, theirs), result.tree(), null, genesis, Author.MERGE, committer,
            System.currentTimeMillis(), "併回 " + source, 0, new TreeMap<>(Map.of("Merged-From", source))).orElseThrow();
        var diff = TreeDiff.collect(repo.overlays(), repo.commit(ours).tree(), result.tree());
        return new Plan(new MergeOutcome.Merged(id, diff.cells().size(), diff.entities().size()), resolve(diff));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, this::async).whenComplete((plan, error) -> sync(() -> {
      if (error != null) {
        wt.unlock();
        failed.accept(error instanceof java.util.concurrent.CompletionException c && c.getCause() != null ? c.getCause() : error);
        return;
      }
      if (plan.writes() == null) {
        wt.unlock();
        done.accept(plan.outcome());
        return;
      }
      apply(wt, plan.writes()).whenComplete((ignored, applyError) -> {
        wt.unlock();
        if (applyError != null) log().log(Level.SEVERE, "合併已提交，但寫入世界時失敗；未寫入的部分會在下次讀取時不一致", applyError);
        done.accept(plan.outcome());
      });
    }));
  }

  private record Plan(MergeOutcome outcome, Writes writes) {}

  /** 要寫進世界的最終內容（已把「回到起點」換成起點世界的實際內容）。 */
  private record Writes(Map<String, Map<Long, List<CellWrite>>> cells, List<EntityWrite> entities) {}

  private record CellWrite(BlockPos pos, Cell cell) {}

  /** state 為 empty 表示移除；chunkX/Z 是要載入的 chunk（實體目前或將要所在的位置）。 */
  private record EntityWrite(String dimension, UUID uuid, Optional<EntityState> state, int chunkX, int chunkZ) {}

  private Writes resolve(TreeDiff.Result diff) throws IOException {
    var cells = new HashMap<String, Map<Long, List<CellWrite>>>();
    for (var c : diff.cells()) {
      var cell = c.after();
      if (cell == null) {
        var all = new BitSet();
        all.set(c.pos().sectionIndex());
        cell = genesis.readSection(SectionKey.of(c.dimension(), c.pos()), all)[c.pos().sectionIndex()];
        if (cell == null) cell = Cell.AIR;
      }
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

  /** 逐 chunk 載入並寫入（主執行緒）。實體等該 chunk 的實體載入後才處理。 */
  private CompletableFuture<Void> apply(Worktree wt, Writes writes) {
    var futures = new ArrayList<CompletableFuture<?>>();
    writes.cells().forEach((dim, byChunk) -> {
      var world = wt.world(dim);
      if (world == null) return;
      byChunk.forEach((key, list) -> futures.add(world.getChunkAtAsync((int) (key >> 32), (int) (long) key, true).thenAccept(chunk ->
          tracker.systemWrite(() -> {
            for (var w : list)
              try {
                LiveWriter.setCell(world, w.pos().x(), w.pos().y(), w.pos().z(), w.cell());
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
    });
  }

  /** Paper 的實體與方塊分開載入：等這個 chunk 的實體載入完成（最多約 5 秒）。 */
  private CompletableFuture<Chunk> entitiesLoaded(Chunk chunk) {
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
}
