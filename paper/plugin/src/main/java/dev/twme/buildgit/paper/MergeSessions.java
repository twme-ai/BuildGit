package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.overlay.ThreeWayMerge;
import dev.twme.buildgit.core.repo.Commit;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.paper.nms.LiveWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 衝突整合（設計 §5.3）。
 *
 * <p>整合世界是一個暫時分支 {@code merge-<id>}：起點是自動合併的結果（衝突格先放目標分支的內容），
 * 所以它和草稿一樣會合成、追蹤、自動存檔、重啟後繼續。目標與來源兩邊以唯讀的檢視世界對照。
 * 完成時把整合分支的內容合併回目標分支（期間目標分支有新存檔也沒關係），整合時誰改了哪一格會記進合併提交。
 *
 * <p>進行中的整合存在 {@code buildgit/merge/<id>.yml}。
 */
final class MergeSessions {
  enum Kind {
    /** 草稿整份併回或底層 merge：合併提交 */
    MERGE,
    /** 部分併回：單親提交 */
    PARTIAL,
    /** 撤銷某次存檔：單親提交 */
    REVERT
  }

  enum Side { OURS, THEIRS }

  record Session(String id, Kind kind, String target, String source, ObjectId base, ObjectId ours, ObjectId theirs,
      Workspace.Scope scope, ObjectId start) {
    String branch() {
      return Workspace.MERGE_PREFIX + id;
    }
  }

  /** 相鄰衝突格的一群。 */
  record Cluster(String dimension, Box box, int cells) {}

  private static final SecureRandom RANDOM = new SecureRandom();
  private final Workspace ws;
  private final Map<String, Session> sessions = new LinkedHashMap<>();
  private final Map<String, ThreeWayMerge.Result> results = new ConcurrentHashMap<>();

  MergeSessions(Workspace ws) {
    this.ws = ws;
  }

  private Path dir() {
    return ws.dir().resolve("merge");
  }

  // ---- 保存 ----

  void load() throws IOException {
    if (!Files.isDirectory(dir())) return;
    try (var s = Files.list(dir())) {
      for (var f : s.filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
        var y = YamlConfiguration.loadConfiguration(f.toFile());
        try {
          Workspace.Scope scope = null;
          if (y.isList("box")) {
            var b = y.getIntegerList("box");
            scope = new Workspace.Scope(y.getString("scope-dimension"), new Box(b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5)));
          }
          var session = new Session(y.getString("id"), Kind.valueOf(y.getString("kind")), y.getString("target"), y.getString("source"),
              ObjectId.parse(y.getString("base")), ObjectId.parse(y.getString("ours")), ObjectId.parse(y.getString("theirs")), scope,
              ObjectId.parse(y.getString("start")));
          if (ws.repo().refs().get(session.branch()).isEmpty()) Files.delete(f);
          else sessions.put(session.id(), session);
        } catch (RuntimeException e) {
          ws.log().log(Level.WARNING, "無法讀取進行中的衝突整合 " + f, e);
        }
      }
    }
  }

  private void save(Session s) throws IOException {
    Files.createDirectories(dir());
    var y = new YamlConfiguration();
    y.set("id", s.id());
    y.set("kind", s.kind().name());
    y.set("target", s.target());
    y.set("source", s.source());
    y.set("base", s.base().hex());
    y.set("ours", s.ours().hex());
    y.set("theirs", s.theirs().hex());
    y.set("start", s.start().hex());
    if (s.scope() != null) {
      var b = s.scope().box();
      y.set("scope-dimension", s.scope().dimension());
      y.set("box", List.of(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
    }
    y.save(dir().resolve(s.id() + ".yml").toFile());
  }

  // ---- 查詢 ----

  Collection<Session> all() {
    return List.copyOf(sessions.values());
  }

  Session get(String id) {
    return sessions.get(id);
  }

  Session byBranch(String branch) {
    if (!Workspace.isScratch(branch)) return null;
    return sessions.get(branch.substring(Workspace.MERGE_PREFIX.length()));
  }

  /** 玩家所在世界屬於哪個整合：整合世界本身，或兩邊的檢視世界。 */
  Session of(World world) {
    var wt = ws.worktree(world);
    if (wt != null) return byBranch(wt.branch());
    var view = ws.view(world);
    if (view == null) return null;
    for (var s : sessions.values()) if (s.ours().equals(view.commit()) || s.theirs().equals(view.commit())) return s;
    return null;
  }

  Session find(String target, String source) {
    for (var s : sessions.values()) if (s.target().equals(target) && Objects.equals(s.source(), source)) return s;
    return null;
  }

  /** 衝突清單（自動合併結果中的衝突格與實體）。 */
  CompletableFuture<ThreeWayMerge.Result> result(Session s) {
    var cached = results.get(s.id());
    if (cached != null) return CompletableFuture.completedFuture(cached);
    return CompletableFuture.supplyAsync(() -> {
      try {
        var repo = ws.repo();
        var r = s.scope() == null ? repo.mergeTrees(s.base(), s.ours(), s.theirs(), null)
            : repo.mergeTrees(s.base(), s.ours(), s.theirs(), s.scope().dimension(), s.scope().box());
        results.put(s.id(), r);
        return r;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, ws::async);
  }

  /** 相鄰（含斜角）的衝突格分成一群，大的在前。 */
  static List<Cluster> clusters(ThreeWayMerge.Result result) {
    var byDim = new HashMap<String, Set<BlockPos>>();
    for (var c : result.cellConflicts()) byDim.computeIfAbsent(c.dimension(), k -> new HashSet<>()).add(c.pos());
    var clusters = new ArrayList<Cluster>();
    byDim.forEach((dim, cells) -> {
      var left = new HashSet<>(cells);
      while (!left.isEmpty()) {
        var first = left.iterator().next();
        left.remove(first);
        var queue = new ArrayDeque<BlockPos>(List.of(first));
        int minX = first.x(), minY = first.y(), minZ = first.z(), maxX = minX, maxY = minY, maxZ = minZ, n = 0;
        while (!queue.isEmpty()) {
          var p = queue.poll();
          n++;
          minX = Math.min(minX, p.x());
          minY = Math.min(minY, p.y());
          minZ = Math.min(minZ, p.z());
          maxX = Math.max(maxX, p.x());
          maxY = Math.max(maxY, p.y());
          maxZ = Math.max(maxZ, p.z());
          for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
              for (int dz = -1; dz <= 1; dz++) {
                var q = new BlockPos(p.x() + dx, p.y() + dy, p.z() + dz);
                if (left.remove(q)) queue.add(q);
              }
        }
        clusters.add(new Cluster(dim, new Box(minX, minY, minZ, maxX, maxY, maxZ), n));
      }
    });
    for (var e : result.entityConflicts()) {
      var state = e.theirs() != null && e.theirs().isPresent() ? e.theirs() : e.ours();
      if (state == null || state.isEmpty() || state.get().position() == null) continue;
      var p = state.get().position();
      int x = (int) Math.floor(p[0]), y = (int) Math.floor(p[1]), z = (int) Math.floor(p[2]);
      clusters.add(new Cluster(e.dimension(), new Box(x, y, z, x, y, z), 1));
    }
    clusters.sort(Comparator.comparingInt(Cluster::cells).reversed());
    return clusters;
  }

  // ---- 開始 ----

  /**
   * 合併有衝突時開始整合（主執行緒）：建立整合分支（內容是自動合併結果）並開啟它的世界。
   * 同一組目標與來源已有進行中的整合時，沿用它。
   */
  void start(Kind kind, String target, String source, Workspace.Scope scope, Workspace.ConflictException c, Author committer,
      Consumer<Session> done, Consumer<Throwable> failed) {
    var existing = find(target, source);
    if (existing != null && existing.ours().equals(c.ours) && existing.theirs().equals(c.theirs)) {
      done.accept(existing);
      return;
    }
    if (existing != null) discard(existing);
    String id = HexFormat.of().formatHex(RANDOM.generateSeed(3));
    String branch = Workspace.MERGE_PREFIX + id;
    try {
      ws.createBranch(branch, target, c.ours, true);
    } catch (IOException e) {
      failed.accept(e);
      return;
    }
    CompletableFuture.supplyAsync(() -> {
      try {
        ws.awaitGenesis();
        return ws.repo().commitTree(branch, List.of(c.ours), c.result.tree(), null, ws.genesis(), Author.MERGE, committer,
            System.currentTimeMillis(), "自動合併（衝突格先放 " + target + " 的內容）", Commit.AUTO, new TreeMap<>()).orElse(c.ours);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, ws::async).whenComplete((start, error) -> ws.sync(() -> {
      try {
        if (error != null) throw Workspace.unwrap(error);
        var session = new Session(id, kind, target, source, c.base, c.ours, c.theirs, scope, start);
        save(session);
        sessions.put(id, session);
        results.put(id, c.result);
        ws.openBranch(branch);
        done.accept(session);
      } catch (Throwable t) {
        try {
          ws.deleteBranch(branch, true);
        } catch (IOException | RuntimeException cleanup) {
          t.addSuppressed(cleanup);
        }
        failed.accept(t);
      }
    }));
  }

  // ---- 處理 ----

  private record CellValue(String dimension, BlockPos pos, Cell cell) {}

  private record EntityValue(String dimension, UUID uuid, Optional<EntityState> state, int chunkX, int chunkZ) {}

  /**
   * 把範圍內的衝突格設成某一邊的內容，記在 {@code author} 名下（之後存進整合分支）。
   *
   * @param scope null 表示全部
   */
  void take(Session s, Side side, Workspace.Scope scope, Author author, Consumer<Integer> done, Consumer<Throwable> failed) {
    Worktree scratch;
    try {
      scratch = ws.openBranch(s.branch());
    } catch (IOException e) {
      failed.accept(e);
      return;
    }
    result(s).thenApplyAsync(result -> {
      try {
        var cells = new ArrayList<CellValue>();
        for (var c : result.cellConflicts()) {
          if (scope != null && (!scope.dimension().equals(c.dimension()) || !scope.box().contains(c.pos()))) continue;
          var v = side == Side.OURS ? c.ours() : c.theirs();
          cells.add(new CellValue(c.dimension(), c.pos(), v != null ? v : ws.genesisCell(c.dimension(), c.pos())));
        }
        var entities = new ArrayList<EntityValue>();
        for (var e : result.entityConflicts()) {
          var v = side == Side.OURS ? e.ours() : e.theirs();
          if (v == null) v = ws.genesis().readEntity(e.dimension(), e.uuid());
          var located = v.isPresent() ? v : (e.ours() != null && e.ours().isPresent() ? e.ours() : e.theirs());
          if (located == null || located.isEmpty() || located.get().position() == null) continue;
          var p = located.get().position();
          if (scope != null && (!scope.dimension().equals(e.dimension()) || !scope.box().contains(p[0], p[1], p[2]))) continue;
          entities.add(new EntityValue(e.dimension(), e.uuid(), v, (int) Math.floor(p[0]) >> 4, (int) Math.floor(p[2]) >> 4));
        }
        return Map.entry(cells, entities);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, ws::async).whenComplete((values, error) -> ws.sync(() -> {
      if (error != null) {
        failed.accept(Workspace.unwrap(error));
        return;
      }
      var futures = new ArrayList<CompletableFuture<?>>();
      var tracker = ws.tracker();
      for (var v : values.getKey()) {
        var world = scratch.world(v.dimension());
        if (world == null) continue;
        futures.add(world.getChunkAtAsync(v.pos().x() >> 4, v.pos().z() >> 4, true).thenAccept(chunk -> tracker.systemWrite(() -> {
          try {
            LiveWriter.setCell(world, v.pos().x(), v.pos().y(), v.pos().z(), v.cell());
            scratch.table().dimension(v.dimension()).markCell(v.pos(), author);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        })));
      }
      for (var v : values.getValue()) {
        var world = scratch.world(v.dimension());
        if (world == null) continue;
        futures.add(world.getChunkAtAsync(v.chunkX(), v.chunkZ(), true).thenCompose(ws::entitiesLoaded).thenAccept(chunk ->
            tracker.systemWrite(() -> {
              try {
                if (v.state().isPresent()) LiveWriter.spawnEntity(world, v.state().get());
                else LiveWriter.removeEntity(world, v.uuid());
                scratch.table().dimension(v.dimension()).markEntity(v.uuid(), author);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            })));
      }
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((x, e) -> ws.sync(() -> {
        if (e != null) failed.accept(Workspace.unwrap(e));
        else done.accept(values.getKey().size() + values.getValue().size());
      }));
    }));
  }

  /**
   * 完成整合（主執行緒）：先存整合分支與目標分支，再把整合結果合併進目標分支、寫進目標世界，最後收掉整合分支。
   * 目標分支在整合期間有新存檔時一起合併；和整合結果衝突時以 {@link Workspace.ConflictException} 失敗，整合保留。
   */
  void finish(Session s, Author committer, Consumer<Optional<Workspace.Applied>> done, Consumer<Throwable> failed) {
    Worktree scratch, target;
    try {
      scratch = ws.openBranch(s.branch());
      target = ws.openBranch(s.target());
    } catch (IOException e) {
      failed.accept(e);
      return;
    }
    saveThen(scratch, committer, failed, () -> saveThen(target, committer, failed, () -> ws.transact(target, head -> {
      var repo = ws.repo();
      var resolved = repo.head(s.branch());
      var result = repo.mergeTrees(s.ours(), head, resolved, null);
      if (!result.clean()) throw new Workspace.ConflictException(result, s.ours(), head, resolved);
      var by = repo.authorsSince(resolved, s.start());
      if (s.kind() == Kind.REVERT)
        return repo.commitTree(s.target(), List.of(head), result.tree(), by.isEmpty() ? null : Repository.Resolution.authors(by),
            ws.genesis(), committer, committer, System.currentTimeMillis(), "撤銷 " + s.base().abbrev(), 0,
            new TreeMap<>(Map.of("Reverts", s.base().hex())));
      return ws.commitMerge(s.target(), s.source(), s.kind() == Kind.PARTIAL ? s.scope() : null, head, s.theirs(), s.base(),
          result.tree(), by, committer);
    }, applied -> {
      discard(s);
      done.accept(applied);
    }, failed)));
  }

  private void saveThen(Worktree wt, Author author, Consumer<Throwable> failed, Runnable next) {
    if (wt.table().isEmpty()) {
      next.run();
      return;
    }
    if (!wt.save(author, "autosave", Commit.AUTO, r -> next.run(), failed))
      failed.accept(new IOException("分支正在存檔或併回，請稍後再試：" + wt.branch()));
  }

  /** 放棄整合，或完成後收尾（主執行緒）：刪掉整合分支與它的世界，收掉沒人的檢視世界。 */
  void discard(Session s) {
    sessions.remove(s.id());
    results.remove(s.id());
    try {
      Files.deleteIfExists(dir().resolve(s.id() + ".yml"));
    } catch (IOException e) {
      ws.log().log(Level.WARNING, "無法刪除整合紀錄 " + s.id(), e);
    }
    var target = ws.worktree(s.target());
    for (var commit : List.of(s.ours(), s.theirs()))
      for (var world : org.bukkit.Bukkit.getWorlds()) {
        var view = ws.view(world);
        if (view == null || !view.commit().equals(commit)) continue;
        if (target != null) for (var p : world.getPlayers()) ws.teleport(p, target);
        ws.left(world);
      }
    try {
      ws.deleteBranch(s.branch(), true);
    } catch (IOException e) {
      ws.log().log(Level.WARNING, "無法刪除整合分支 " + s.branch(), e);
    }
  }
}
