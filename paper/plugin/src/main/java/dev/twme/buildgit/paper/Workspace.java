package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.anvil.AnvilSource;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.repo.StateSource;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.track.MarkJournal;
import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.core.track.Marks;
import dev.twme.buildgit.paper.nms.LiveReader;
import dev.twme.buildgit.paper.track.Tracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 伺服器上的版本庫與主世界的工作區（設計 §2.1）：{@code <伺服器根目錄>/buildgit/}。
 *
 * <p>目前只處理主分支：主世界與它的地獄、終界。分支世界之後加入。
 */
public final class Workspace {
  private final JavaPlugin plugin;
  private final Tracker tracker;
  private final Path dir;
  private Repository repo;
  private MarkTable table;
  private MarkJournal journal;
  private AnvilSource genesis;
  private boolean uncleanShutdown;
  private final AtomicBoolean committing = new AtomicBoolean();
  private long lastSaveTick;

  public Workspace(JavaPlugin plugin, Tracker tracker) {
    this.plugin = plugin;
    this.tracker = tracker;
    this.dir = Path.of("").toAbsolutePath().resolve("buildgit");
  }

  public boolean isOpen() {
    return repo != null;
  }

  public Repository repo() {
    return repo;
  }

  public MarkTable table() {
    return table;
  }

  public boolean uncleanShutdown() {
    return uncleanShutdown;
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

  private Path genesisDir(String dimension) {
    int colon = dimension.indexOf(':');
    return dir.resolve("genesis").resolve(dimension.substring(0, colon)).resolve(dimension.substring(colon + 1));
  }

  private Path lockFile() {
    return dir.resolve("worktree/main/running");
  }

  // ---- 開啟 ----

  /** 版本庫已存在時開啟並開始追蹤。 */
  public void open() throws IOException {
    if (!Repository.exists(dir)) return;
    repo = Repository.open(dir);
    table = new MarkTable(() -> Bukkit.getCurrentTick());
    var journalDir = dir.resolve("worktree/main");
    Files.createDirectories(journalDir);
    uncleanShutdown = Files.exists(lockFile());
    // 開新檔之前先列出舊檔，重播後由下次提交一併刪除。
    var old = new MarkJournal(journalDir);
    var files = old.files();
    old.close();
    MarkJournal.replay(files, table);
    journal = new MarkJournal(journalDir);
    // 空的那一個（上面開了又關）也會在下次提交時刪掉。
    Files.writeString(lockFile(), String.valueOf(ProcessHandle.current().pid()));
    genesis = new AnvilSource(d -> {
      var p = genesisDir(d);
      return Files.isDirectory(p) ? p : null;
    });
    tracker.attach(table, mainWorlds());
    lastSaveTick = Bukkit.getCurrentTick();
    if (table.cellCount() > 0) plugin.getLogger().info("從標記日誌恢復 " + table.cellCount() + " 格未存的變更");
  }

  public void close() {
    tracker.detach();
    try {
      if (journal != null) {
        journal.flush(table);
        journal.close();
      }
      Files.deleteIfExists(lockFile());
    } catch (IOException e) {
      plugin.getLogger().log(Level.SEVERE, "無法寫入標記日誌", e);
    }
    if (genesis != null) genesis.close();
    repo = null;
  }

  /** 定期把標記寫到磁碟（非同步執行緒）。 */
  public void flushJournal() {
    var j = journal;
    var t = table;
    if (j == null || t == null) return;
    try {
      j.flush(t);
    } catch (IOException e) {
      plugin.getLogger().log(Level.SEVERE, "無法寫入標記日誌", e);
    }
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

  // ---- 存檔 ----

  public record SaveResult(Optional<ObjectId> commit, int cells, int entities) {}

  /**
   * 存檔（設計 §5.1）。主執行緒上取走標記並讀取被標記的格子（未載入的 chunk 以非同步方式載入），
   * 雜湊與寫入物件在背景執行緒進行。
   *
   * @return false 表示已有存檔在進行
   */
  public boolean save(Author committer, String message, int flags, Consumer<SaveResult> done, Consumer<Throwable> failed) {
    if (!committing.compareAndSet(false, true)) return false;
    MarkJournal.Drained drained;
    try {
      drained = journal.drainForCommit(table);
    } catch (IOException e) {
      committing.set(false);
      failed.accept(e);
      return true;
    }
    var snapshot = new Snapshot();
    Marks marks;
    var futures = new ArrayList<CompletableFuture<?>>();
    try {
      marks = defer(drained.marks());
      readEntities(marks, snapshot);
      for (var entry : chunks(marks).entrySet()) {
        var key = entry.getKey();
        var world = world(key.dimension);
        if (world.isChunkLoaded(key.x, key.z)) read(world.getChunkAt(key.x, key.z), entry.getValue(), marks, snapshot);
        else
          futures.add(world.getChunkAtAsync(key.x, key.z, false).thenAccept(chunk -> {
            if (chunk != null) read(chunk, entry.getValue(), marks, snapshot);
          }));
      }
    } catch (RuntimeException e) {
      committing.set(false);
      try {
        journal.restore(table, drained);
      } catch (IOException io) {
        e.addSuppressed(io);
      }
      failed.accept(e);
      return true;
    }
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .thenApplyAsync(ignored -> {
          try {
            var head = repo.head(Repository.MAIN);
            var id = repo.commit(new Repository.CommitRequest(Repository.MAIN, head, marks, snapshot, genesis, committer,
                System.currentTimeMillis(), message, flags, new TreeMap<>()));
            journal.delete(drained.previousFiles());
            if (id.isEmpty()) return new SaveResult(id, 0, 0);
            // 標記可能比實際變化多（多記無害）；回報實際寫進版本的數量。
            var changes = repo.changeset(repo.commit(id.get()).changeset());
            return new SaveResult(id, changes.cells().size(), changes.entities().size());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }, runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable))
        .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
          committing.set(false);
          if (error == null) {
            lastSaveTick = Bukkit.getCurrentTick();
            done.accept(result);
            return;
          }
          try {
            journal.restore(table, drained);
          } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "存檔失敗後無法寫回標記", e);
          }
          failed.accept(error instanceof java.util.concurrent.CompletionException c && c.getCause() != null ? c.getCause() : error);
        }));
    return true;
  }

  private World world(String dimension) {
    for (var w : Bukkit.getWorlds()) if (Tracker.dimensionId(w).equals(dimension)) return w;
    return null;
  }

  /**
   * 現在讀不到的標記留在標記表，等下一次存檔：
   * 世界沒有載入的 section 不能當成空氣；沒有載入的被追蹤實體不能當成被移除。
   */
  private Marks defer(Marks marks) {
    var sections = new TreeMap<SectionKey, Marks.Section>();
    marks.sections().forEach((key, section) -> {
      if (world(key.dimension()) != null) {
        sections.put(key, section);
        return;
      }
      var d = table.dimension(key.dimension());
      var cells = section.cells();
      for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
        var pos = key.pos(i);
        d.markCell(pos.x(), pos.y(), pos.z(), section.author(marks, i));
      }
    });
    var entities = new TreeMap<String, SortedMap<UUID, Integer>>();
    marks.entities().forEach((dim, map) -> {
      var keep = new TreeMap<UUID, Integer>();
      map.forEach((uuid, author) -> {
        boolean absent = world(dim) == null || tracker.isUnloaded(uuid) && Bukkit.getEntity(uuid) == null;
        if (absent) table.dimension(dim).markEntity(uuid, marks.authors().get(author));
        else keep.put(uuid, author);
      });
      if (!keep.isEmpty()) entities.put(dim, keep);
    });
    return new Marks(marks.authors(), sections, entities);
  }

  private record ChunkKey(String dimension, int x, int z) {}

  private static Map<ChunkKey, List<SectionKey>> chunks(Marks marks) {
    var result = new LinkedHashMap<ChunkKey, List<SectionKey>>();
    for (var key : marks.sections().keySet())
      result.computeIfAbsent(new ChunkKey(key.dimension(), key.chunkX(), key.chunkZ()), k -> new ArrayList<>()).add(key);
    return result;
  }

  private final LiveReader reader = new LiveReader();

  private void read(Chunk chunk, List<SectionKey> keys, Marks marks, Snapshot snapshot) {
    for (var key : keys) {
      try {
        snapshot.cells.put(key, reader.readSection(chunk, key.sectionY(), marks.sections().get(key).cells()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private void readEntities(Marks marks, Snapshot snapshot) {
    marks.entities().forEach((dim, map) -> {
      for (var uuid : map.keySet()) {
        var entity = Bukkit.getEntity(uuid);
        Optional<EntityState> state = Optional.empty();
        if (entity != null && !entity.isDead() && Tracker.dimensionId(entity.getWorld()).equals(dim)) {
          try {
            state = Optional.of(LiveReader.readEntity(entity));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
        snapshot.entities.put(dim + "/" + uuid, state);
      }
    });
  }

  /** 存檔當下讀好的工作世界內容，交給背景執行緒的提交使用。 */
  private static final class Snapshot implements StateSource {
    final Map<SectionKey, Cell[]> cells = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, Optional<EntityState>> entities = new HashMap<>();

    @Override
    public Cell[] readSection(SectionKey key, BitSet wanted) {
      var c = cells.get(key);
      if (c != null) return c;
      // chunk 不存在（從未生成）：沒有方塊。
      var air = new Cell[4096];
      Arrays.fill(air, Cell.AIR);
      return air;
    }

    @Override
    public Optional<EntityState> readEntity(String dimension, UUID uuid) {
      return entities.getOrDefault(dimension + "/" + uuid, Optional.empty());
    }
  }

  // ---- 自動存檔（設計 §6.3） ----

  /** 每秒呼叫：所有人停手夠久，或離上次存檔太久，就自動存檔。 */
  public void autosaveTick(Consumer<SaveResult> done) {
    if (table == null || committing.get() || table.isEmpty()) return;
    var settings = tracker.settings();
    long now = Bukkit.getCurrentTick();
    boolean idle = settings.autosaveIdleTicks() > 0 && now - table.lastChange() >= settings.autosaveIdleTicks();
    boolean overdue = settings.autosaveMaxTicks() > 0 && now - lastSaveTick >= settings.autosaveMaxTicks();
    if (!idle && !overdue) return;
    save(Author.SYSTEM, "autosave", dev.twme.buildgit.core.repo.Commit.AUTO, done, e -> plugin.getLogger().log(Level.WARNING, "自動存檔失敗", e));
  }
}
