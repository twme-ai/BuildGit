package dev.twme.buildgit.server;

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

  private final Platform platform;
  private final Tracker tracker;
  /** 舊版 Minecraft 寫下的歷史與起點世界在讀取時升級（設計 §4.6）。 */
  private final DataUpgrader upgrader = new DataUpgrader();
  private final Path dir;
  private Repository repo;
  private AnvilSource genesis;
  private GenesisRecorder genesisRecorder;
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

  public Workspace(Platform platform, Tracker tracker) {
    this.platform = platform;
    this.tracker = tracker;
    this.dir = platform.repoDir();
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
    platform.sync(r);
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
    var result = new ArrayList<ServerLevel>();
    var seen = new HashSet<String>();
    for (var w : platform.server().getAllLevels())
      if (tracked(w) && seen.add(Tracker.dimensionId(w))) result.add(w);
    return result;
  }

  /** BuildGit 建立的世界（分支、檢視）。 */
  public boolean ownWorld(ServerLevel world) {
    String name = platform.levelName(world);
    return sources.containsKey(world) || name.startsWith(WORLD_PREFIX) || name.startsWith(VIEW_PREFIX);
  }

  /** 受版本控制：不是 BuildGit 建立的世界，而且這個維度已經初始化過（有起點世界）。 */
  public boolean tracked(ServerLevel world) {
    return repo != null && !ownWorld(world) && Files.isDirectory(genesisDir(Tracker.dimensionId(world)));
  }

  /**
   * 世界載入了（多世界插件在 BuildGit 之後載入、或之後才 init 的世界；主執行緒）：受版本控制時納入主分支，
   * 已開啟的草稿也補上這個維度的世界。
   */
  public void worldLoaded(ServerLevel world) {
    if (owners.containsKey(world) || !tracked(world)) return;
    String dim = Tracker.dimensionId(world);
    if (main().world(dim) != null) return;
    main().addWorld(dim, world);
    owners.put(world, main());
    resumeCopy(world);
    if (!copying(dim)) openBranchWorlds(world);
  }

  /** 上次沒複製完的起點世界（伺服器在複製途中關閉）：補完，已有的 chunk 跳過。 */
  private void resumeCopy(ServerLevel world) {
    String dim = Tracker.dimensionId(world);
    if (copying(dim) || !GenesisCopy.unfinished(genesisDir(dim))) return;
    try {
      var copy = new GenesisCopy(dim, platform.levelDirectory(world), genesisDir(dim), log());
      copies.put(dim, copy);
      log().warning("上次沒有複製完 " + platform.levelName(world) + " 的起點世界，在背景補完；之後改過又存回的 chunk 會以目前的樣子當作起點");
      startCopy(world, copy);
    } catch (IOException e) {
      log().log(Level.SEVERE, "無法補完起點世界 " + platform.levelName(world), e);
    }
  }

  /** 已開啟的草稿補上這個主世界維度的世界。 */
  private void openBranchWorlds(ServerLevel world) {
    String dim = Tracker.dimensionId(world);
    try {
      for (var wt : worktrees())
        if (wt != main() && wt.world(dim) == null) {
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

  /** 主分支的世界被卸載了（無法擋下的平台）：不再追蹤，未存的標記留著，等它再次載入。 */
  public void worldUnloaded(ServerLevel world) {
    if (!isMainWorld(world)) return;
    owners.remove(world);
    main().removeWorld(world);
    log().warning("受版本控制的世界 " + platform.levelName(world) + " 被卸載；再次載入前，併回與還原不會寫進這個世界");
  }

  private ServerLevel mainWorld(String dimension) {
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
    repo = Repository.open(dir, upgrader);
    genesis = new AnvilSource(d -> {
      var p = genesisDir(d);
      return Files.isDirectory(p) ? p : null;
    }, upgrader);
    genesisRecorder = new GenesisRecorder(this::genesisDir, genesis, log());
    var main = new Worktree(this, Repository.MAIN);
    worktrees.put(Repository.MAIN, main);
    for (var w : mainWorlds()) {
      main.addWorld(Tracker.dimensionId(w), w);
      owners.put(w, main);
      resumeCopy(w);
    }
    deleteStaleViews();
    merges = new MergeSessions(this);
    merges.load();
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
      if (wt.branch().equals(Repository.MAIN)) continue;
      boolean occupied = wt.worlds().values().stream().anyMatch(w -> !w.players().isEmpty());
      if (occupied || wt.isCommitting()) {
        idleSince.remove(wt.branch());
        continue;
      }
      long since = idleSince.computeIfAbsent(wt.branch(), k -> now);
      if (now - since >= idleTicks) unload(wt);
    }
  }

  /** 卸載分支的世界並關閉工作區（主執行緒）。正在存檔或併回時不動。 */
  boolean unload(Worktree wt) {
    if (wt.branch().equals(Repository.MAIN) || !wt.lock()) return false;
    try {
      for (var world : List.copyOf(wt.worlds().values())) {
        if (!platform.unloadLevel(world, true)) {
          log().warning("無法卸載世界 " + platform.levelName(world) + "，" + wt.branch() + " 保持載入");
          return false;
        }
        owners.remove(world);
        sources.remove(world);
        wt.removeWorld(world);
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

  // ---- init ----

  /**
   * 讓一個世界開始受版本控制：存檔、把它的 region 與實體檔複製成凍結的起點世界；第一個世界同時建立版本庫。
   * 只處理這一個世界（地獄、終界與其他世界各自 init）。主執行緒只存這個世界一次，複製在背景進行（{@link GenesisCopy}）。
   *
   * @return 背景複製；完成時的值是起點世界大小（位元組）
   */
  public GenesisCopy init(Author author, ServerLevel world) throws IOException {
    if (ownWorld(world)) throw new Refused("這是 BuildGit 建立的世界；請在要版本控制的世界裡執行");
    if (tracked(world)) throw new Refused("這個世界已經受版本控制");
    if (repo == null && Repository.exists(dir)) throw new Refused("版本庫存在但無法開啟");
    // 只存這個世界；之後磁碟上的 chunk 在被伺服器再寫一次之前都是 init 當下的樣子，可以在背景複製
    LiveReader.saveNow(world);
    String dim = Tracker.dimensionId(world);
    var copy = new GenesisCopy(dim, platform.levelDirectory(world), genesisDir(dim), log());
    copies.put(dim, copy);
    if (repo == null) {
      Repository.init(dir, author, System.currentTimeMillis(), upgrader);
      open();
    } else {
      worldLoaded(world);
    }
    startCopy(world, copy);
    return copy;
  }

  /** 起點世界還在背景複製的維度：存檔先不收這個維度的標記，也不開它的草稿與檢視世界。 */
  public boolean copying(String dimension) {
    return copies.containsKey(dimension);
  }

  private void startCopy(ServerLevel world, GenesisCopy copy) {
    String dim = Tracker.dimensionId(world);
    copy.start(platform.loadedChunks(world));
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

  /** 新生成的 chunk 以外的 chunk 載入了（主執行緒）：起點世界還在複製時，趁它被改之前先複製。 */
  public void chunkLoaded(ServerLevel world, int chunkX, int chunkZ) {
    if (copies.isEmpty() || owners.get(world) != main()) return;
    var copy = copies.get(Tracker.dimensionId(world));
    if (copy != null) copy.chunkLoaded(chunkX, chunkZ);
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
  private Yaml branchesFile() {
    return Yaml.load(branchesPath());
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
    if (wt != null && wt.isCommitting()) throw new Refused("正在存檔或併回，請稍後再試");
    var fallback = worktrees.get(source(name));
    if (fallback == null) fallback = main();
    if (wt != null) {
      worktrees.remove(name);
      wt.close();
      for (var world : List.copyOf(wt.worlds().values())) discardWorld(world, fallback);
    }
    for (var w : mainWorlds()) {
      var world = worldName(name, Tracker.dimensionId(w));
      if (platform.findLevel(world) == null) deleteLater(platform.levelFolder(world));
    }
    deleteLater(dir.resolve("worktree").resolve(name));
    repo.deleteBranch(name);
    yaml.set(name, null);
    yaml.save(branchesPath());
  }

  /** 草稿某維度的世界名稱。 */
  static String worldName(String branch, String dimension) {
    return WORLD_PREFIX + branch + suffix(dimension);
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
    if (branch.equals(Repository.MAIN)) return main();
    idleSince.remove(branch); // 剛被用到（例如傳送還在進行），重新計算閒置時間
    var existing = worktrees.get(branch);
    if (existing != null) return existing;
    repo.head(branch); // 不存在時丟出例外
    var wt = new Worktree(this, branch);
    worktrees.put(branch, wt);
    try {
      for (var mainWorld : mainWorlds()) {
        String dim = Tracker.dimensionId(mainWorld);
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
      s = new RegionSynthesizer(Files.isDirectory(g) ? g : null, new OverlayReader(repo.overlays(), tree), source.dimension(),
          upgrader);
      synthesizers.put(key, s);
      return s;
    }
  }

  // ---- 檢視世界（設計 §6.5） ----

  static String viewName(ObjectId commit, String dimension) {
    return VIEW_PREFIX + commit.hex().substring(0, 10) + suffix(dimension);
  }

  /** 開啟某個版本、某個維度的唯讀世界（主執行緒）。 */
  public ServerLevel openView(ObjectId commit, String dimension) throws IOException {
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
      byChunk.forEach((key, cells) -> futures.add(platform.loadChunk(world, (int) (key >> 32), (int) (long) key).thenAccept(chunk ->
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
        chain = chain.thenCompose(x -> platform.loadChunk(world, e.chunkX(), e.chunkZ())).thenCompose(c -> entitiesLoaded(world, c)).thenAccept(chunk ->
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
          if (q.y() < world.getMinY() || q.y() > world.getMaxY() || !seen.add(q)) continue;
          if (!world.hasChunk(q.x() >> 4, q.z() >> 4)) continue;
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
        var cache = new HashMap<SectionKey, Cell[]>();
        for (var c : diff.cells()) {
          var a = by.get(new Repository.CellKey(c.dimension(), c.pos()));
          authors.merge(a != null ? a.name() : "?", 1, Integer::sum);
          changed.add(new Changed(c.dimension(), c.pos(), kind(c.dimension(), c.pos(), c.before(), c.after(), cache)));
        }
        for (var c : result.cellConflicts()) changed.add(new Changed(c.dimension(), c.pos(), Kind.CONFLICT));
        return new Preview(diff.cells().size(), diff.entities().size(), authors, changed, result, ours, theirs);
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
