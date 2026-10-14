package dev.twme.buildgit.paper;

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
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * 一個分支的工作區（設計 §2.1 {@code worktree/<分支>/}）：它的世界、標記表與日誌、存檔。
 *
 * <p>主分支的世界是伺服器原本的世界；其他分支的世界由 BuildGit 建立（§4）。
 */
public final class Worktree {
  private final Workspace ws;
  private final String branch;
  private final Path dir;
  private final MarkTable table;
  private final MarkJournal journal;
  /** 版本中的維度 id → 世界。 */
  private final Map<String, World> worlds = new LinkedHashMap<>();
  private final boolean uncleanShutdown;
  private final AtomicBoolean committing = new AtomicBoolean();
  private long lastSaveTick;

  Worktree(Workspace ws, String branch) throws IOException {
    this.ws = ws;
    this.branch = branch;
    this.dir = ws.dir().resolve("worktree").resolve(branch);
    Files.createDirectories(dir);
    table = new MarkTable(() -> Bukkit.getCurrentTick());
    uncleanShutdown = Files.exists(lockFile());
    // 開新檔之前先列出舊檔，重播後由下次提交一併刪除。
    var old = new MarkJournal(dir);
    var files = old.files();
    old.close();
    MarkJournal.replay(files, table);
    journal = new MarkJournal(dir);
    Files.writeString(lockFile(), String.valueOf(ProcessHandle.current().pid()));
    lastSaveTick = Bukkit.getCurrentTick();
    if (table.cellCount() > 0) ws.log().info(branch + "：從標記日誌恢復 " + table.cellCount() + " 格未存的變更");
  }

  private Path lockFile() {
    return dir.resolve("running");
  }

  public String branch() {
    return branch;
  }

  public MarkTable table() {
    return table;
  }

  public boolean uncleanShutdown() {
    return uncleanShutdown;
  }

  public Map<String, World> worlds() {
    return Collections.unmodifiableMap(worlds);
  }

  public World world(String dimension) {
    return worlds.get(dimension);
  }

  /** 這個世界開始屬於本分支（主世界啟用時、分支世界載入時）。 */
  void addWorld(String dimension, World world) {
    worlds.put(dimension, world);
    ws.tracker().attach(table, Map.of(world, dimension));
  }

  void removeWorld(World world) {
    worlds.values().removeIf(w -> w.getUID().equals(world.getUID()));
    ws.tracker().detach(table);
    ws.tracker().attach(table, inverse());
  }

  private Map<World, String> inverse() {
    var m = new HashMap<World, String>();
    worlds.forEach((d, w) -> m.put(w, d));
    return m;
  }

  void close() {
    ws.tracker().detach(table);
    try {
      journal.flush(table);
      journal.close();
      Files.deleteIfExists(lockFile());
    } catch (IOException e) {
      ws.log().log(Level.SEVERE, "無法寫入標記日誌（" + branch + "）", e);
    }
  }

  /** 定期把標記寫到磁碟（非同步執行緒）。 */
  void flushJournal() {
    try {
      journal.flush(table);
    } catch (IOException e) {
      ws.log().log(Level.SEVERE, "無法寫入標記日誌（" + branch + "）", e);
    }
  }

  public boolean isCommitting() {
    return committing.get();
  }

  // ---- 存檔 ----

  public record SaveResult(Optional<ObjectId> commit, int cells, int entities) {}

  /**
   * 存檔（設計 §5.1）。主執行緒上取走標記並讀取被標記的格子（未載入的 chunk 以非同步方式載入），
   * 雜湊與寫入物件在背景執行緒進行。
   *
   * @return false 表示已有存檔或合併在進行
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
        var world = worlds.get(key.dimension);
        if (world.isChunkLoaded(key.x, key.z)) read(world.getChunkAt(key.x, key.z), entry.getValue(), marks, snapshot);
        else
          // gen=true：合成的 chunk 沒有光照資料，載入時要補完最後的步驟；gen=false 會把它當成不存在。
          // 從未生成的 chunk 也照常生成，讀到的就是玩家會看到的內容。讀不到絕不能當成空氣。
          futures.add(world.getChunkAtAsync(key.x, key.z, true).thenAccept(chunk -> {
            if (chunk == null) throw new IllegalStateException("無法載入 chunk " + key.x + "," + key.z);
            read(chunk, entry.getValue(), marks, snapshot);
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
    var repo = ws.repo();
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .thenApplyAsync(ignored -> {
          try {
            ws.awaitGenesis();
            var head = repo.head(branch);
            var id = repo.commit(new Repository.CommitRequest(branch, head, marks, snapshot, ws.genesis(), committer,
                System.currentTimeMillis(), message, flags, new TreeMap<>()));
            journal.delete(drained.previousFiles());
            if (id.isEmpty()) return new SaveResult(id, 0, 0);
            // 標記可能比實際變化多（多記無害）；回報實際寫進版本的數量。
            var changes = repo.changeset(repo.commit(id.get()).changeset());
            return new SaveResult(id, changes.cells().size(), changes.entities().size());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }, ws::async)
        .whenComplete((result, error) -> ws.sync(() -> {
          committing.set(false);
          if (error == null) {
            lastSaveTick = Bukkit.getCurrentTick();
            done.accept(result);
            return;
          }
          try {
            journal.restore(table, drained);
          } catch (IOException e) {
            ws.log().log(Level.SEVERE, "存檔失敗後無法寫回標記", e);
          }
          failed.accept(error instanceof java.util.concurrent.CompletionException c && c.getCause() != null ? c.getCause() : error);
        }));
    return true;
  }

  /** 合併等寫入期間占用存檔鎖：同一個分支同時只能有一個提交或合併（設計 §5.1）。 */
  boolean lock() {
    return committing.compareAndSet(false, true);
  }

  void unlock() {
    committing.set(false);
  }

  /**
   * 現在讀不到的標記留在標記表，等下一次存檔：
   * 世界沒有載入的 section 不能當成空氣；沒有載入的被追蹤實體不能當成被移除。
   */
  private Marks defer(Marks marks) {
    var tracker = ws.tracker();
    var sections = new TreeMap<SectionKey, Marks.Section>();
    marks.sections().forEach((key, section) -> {
      if (worlds.get(key.dimension()) != null) {
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
        boolean absent = worlds.get(dim) == null || tracker.isUnloaded(uuid) && Bukkit.getEntity(uuid) == null;
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
      var world = worlds.get(dim);
      for (var uuid : map.keySet()) {
        var entity = Bukkit.getEntity(uuid);
        Optional<EntityState> state = Optional.empty();
        if (entity != null && !entity.isDead() && world != null && entity.getWorld().getUID().equals(world.getUID())) {
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
      if (c == null) throw new IllegalStateException("存檔時沒有讀到 " + key);
      return c;
    }

    @Override
    public Optional<EntityState> readEntity(String dimension, UUID uuid) {
      return entities.getOrDefault(dimension + "/" + uuid, Optional.empty());
    }
  }

  // ---- 自動存檔（設計 §6.3） ----

  /** 每秒呼叫：所有人停手夠久，或離上次存檔太久，就自動存檔。 */
  void autosaveTick(Consumer<SaveResult> done) {
    if (committing.get() || table.isEmpty()) return;
    var settings = ws.tracker().settings();
    long now = Bukkit.getCurrentTick();
    boolean idle = settings.autosaveIdleTicks() > 0 && now - table.lastChange() >= settings.autosaveIdleTicks();
    boolean overdue = settings.autosaveMaxTicks() > 0 && now - lastSaveTick >= settings.autosaveMaxTicks();
    if (!idle && !overdue) return;
    save(Author.SYSTEM, "autosave", dev.twme.buildgit.core.repo.Commit.AUTO, done,
        e -> ws.log().log(Level.WARNING, "自動存檔失敗（" + branch + "）", e));
  }
}
