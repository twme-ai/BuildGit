package dev.twme.buildgit.core.repo;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.model.Upgrader;
import dev.twme.buildgit.core.overlay.*;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectStore;
import dev.twme.buildgit.core.store.ObjectType;
import dev.twme.buildgit.core.store.RefStore;
import dev.twme.buildgit.core.track.Marks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BuildGit 儲存庫（設計 §2、§5）。一台伺服器或一個單人存檔一個。
 *
 * <p>所有改變 ref 的操作都持有同一把鎖，並以 compare-and-set 更新 ref，所以同一時間只有一個提交或合併生效。
 */
public final class Repository {
  public static final String MAIN = "main";

  private final Path dir;
  private final ObjectStore objects;
  private final OverlayStore overlays;
  private final RefStore refs;
  private final ReentrantLock lock = new ReentrantLock();

  private Repository(Path dir, Upgrader upgrader) throws IOException {
    this.dir = dir;
    objects = new ObjectStore(dir.resolve("objects"));
    overlays = new OverlayStore(objects, upgrader, upgrader == Upgrader.NONE ? 0 : dataVersions(dir, upgrader.current()));
    refs = new RefStore(dir.resolve("refs").resolve("branches"));
  }

  /**
   * 檔案 {@code data-version} 記錄兩個資料版本（設計 §4.6）：{@code legacy} 是沒有標記資料版本的舊物件所屬的版本
   * （第一次記錄時的伺服器版本，之後不變），{@code newest} 是寫過這個版本庫的最新版本。
   * Minecraft 不能降級，所以伺服器比 {@code newest} 舊時拒絕開啟。
   *
   * @return {@code legacy}
   */
  private static int dataVersions(Path dir, int current) throws IOException {
    var file = dir.resolve("data-version");
    var props = new Properties();
    if (Files.exists(file))
      try (var in = Files.newBufferedReader(file)) {
        props.load(in);
      }
    int legacy, newest;
    try {
      legacy = Integer.parseInt(props.getProperty("legacy", String.valueOf(current)));
      newest = Integer.parseInt(props.getProperty("newest", String.valueOf(current)));
    } catch (NumberFormatException e) {
      throw new IOException("data-version 格式錯誤", e);
    }
    if (newest > current)
      throw new IOException("這個版本庫曾由較新的 Minecraft（資料版本 " + newest + "）寫入，目前伺服器是 " + current + "；Minecraft 不能降級，請用相同或更新的版本");
    if (Files.exists(file) && newest == current) return legacy;
    props.setProperty("legacy", String.valueOf(legacy));
    props.setProperty("newest", String.valueOf(current));
    Files.createDirectories(dir);
    var tmp = dir.resolve("data-version.tmp");
    try (var out = Files.newBufferedWriter(tmp)) {
      props.store(out, "BuildGit: Minecraft data versions");
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    return legacy;
  }

  public static boolean exists(Path dir) {
    return Files.isDirectory(dir.resolve("refs").resolve("branches"));
  }

  /** 建立儲存庫與 {@code main} 的第一個提交（覆寫表為空，也就是與起點世界相同）。 */
  public static Repository init(Path dir, Author author, long time) throws IOException {
    return init(dir, author, time, Upgrader.NONE);
  }

  /** @param upgrader 伺服器的資料版本與升級方式（見 {@link Upgrader}） */
  public static Repository init(Path dir, Author author, long time, Upgrader upgrader) throws IOException {
    if (exists(dir)) throw new IOException("儲存庫已存在：" + dir);
    var repo = new Repository(dir, upgrader);
    var tree = repo.overlays.putRoot(Tree.EMPTY);
    var changeset = repo.put(new Changeset(List.of(), List.of(), List.of()));
    var commit = repo.put(new Commit(tree, List.of(), changeset, author, time, "init", 0, new TreeMap<>()));
    repo.refs.compareAndSet(MAIN, null, commit);
    return repo;
  }

  public static Repository open(Path dir) throws IOException {
    return open(dir, Upgrader.NONE);
  }

  public static Repository open(Path dir, Upgrader upgrader) throws IOException {
    if (!exists(dir)) throw new IOException("找不到儲存庫：" + dir);
    return new Repository(dir, upgrader);
  }

  public Path dir() {
    return dir;
  }

  public OverlayStore overlays() {
    return overlays;
  }

  public RefStore refs() {
    return refs;
  }

  // ---- 物件 ----

  public Commit commit(ObjectId id) throws IOException {
    return Commit.decode(objects.get(id, ObjectType.COMMIT));
  }

  public Changeset changeset(ObjectId id) throws IOException {
    return Changeset.decode(objects.get(id, ObjectType.CHANGESET));
  }

  private ObjectId put(Commit commit) throws IOException {
    return objects.put(ObjectType.COMMIT, commit.encode());
  }

  private ObjectId put(Changeset changeset) throws IOException {
    return objects.put(ObjectType.CHANGESET, changeset.encode());
  }

  public ObjectId head(String branch) throws IOException {
    return refs.get(branch).orElseThrow(() -> new IOException("找不到分支：" + branch));
  }

  public OverlayReader reader(ObjectId commit) throws IOException {
    return new OverlayReader(overlays, commit(commit).tree());
  }

  // ---- 分支 ----

  public void createBranch(String name, ObjectId from) throws IOException {
    commit(from);
    if (!refs.compareAndSet(name, null, from)) throw new IOException("分支已存在：" + name);
  }

  public void deleteBranch(String name) throws IOException {
    if (name.equals(MAIN)) throw new IOException("不能刪除 main");
    if (!refs.delete(name, head(name))) throw new IOException("分支在刪除時被更新：" + name);
  }

  // ---- 提交 ----

  /**
   * @param branch 要提交到的分支
   * @param expectedHead 預期的分支目前提交；不符時拒絕（有別的提交搶先）
   * @param marks 取走的標記
   * @param world 工作世界的目前內容
   * @param genesis 起點世界的內容
   * @param flags {@link Commit#AUTO} 等
   */
  public record CommitRequest(
      String branch,
      ObjectId expectedHead,
      Marks marks,
      StateSource world,
      StateSource genesis,
      Author committer,
      long time,
      String message,
      int flags,
      SortedMap<String, String> trailers) {}

  /** @return 新提交；標記的格子都沒有實際改變時回傳 empty，不產生提交 */
  public Optional<ObjectId> commit(CommitRequest req) throws IOException {
    lock.lock();
    try {
      var head = head(req.branch());
      if (!head.equals(req.expectedHead())) throw new ConcurrentUpdateException(req.branch());
      var parent = commit(head);
      var capture = capture(parent.tree(), req.marks(), req.world(), req.genesis());
      if (capture.changes.isEmpty()) return Optional.empty();
      var tree = capture.editor.build();
      var changeset = put(capture.changes.build());
      var id = put(new Commit(tree, List.of(head), changeset, req.committer(), req.time(), req.message(), req.flags(), req.trailers()));
      if (!refs.compareAndSet(req.branch(), head, id)) throw new ConcurrentUpdateException(req.branch());
      return Optional.of(id);
    } finally {
      lock.unlock();
    }
  }

  private record Capture(OverlayEditor editor, ChangesetBuilder changes) {}

  /** 讀出被標記格子的目前內容，與 {@code baseTree} 比較，產生覆寫表的修改與 changeset（設計 §5.1）。 */
  private Capture capture(ObjectId baseTree, Marks marks, StateSource world, StateSource genesis) throws IOException {
    var reader = new OverlayReader(overlays, baseTree);
    var editor = new OverlayEditor(overlays, baseTree);
    var changes = new ChangesetBuilder();
    for (var e : marks.sections().entrySet()) {
      var key = e.getKey();
      var wanted = e.getValue().cells();
      var current = world.readSection(key, wanted);
      var parent = reader.section(key);
      Cell[] origin = null;
      for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
        var now = orAir(current[i]);
        var overlay = parent.get(i);
        if (overlay != null && overlay.equals(now)) continue;
        if (origin == null) origin = genesis.readSection(key, wanted);
        var start = orAir(origin[i]);
        var before = overlay != null ? overlay : start;
        if (before.equals(now)) continue;
        var pos = key.pos(i);
        editor.setCell(key.dimension(), pos, now.equals(start) ? null : now);
        changes.cell(key.dimension(), pos, before, now, e.getValue().author(marks, i));
      }
      captureBiomes(key, e.getValue(), marks, world, genesis, parent, editor, changes);
    }
    for (var d : marks.entities().entrySet()) {
      for (var m : d.getValue().entrySet()) {
        var now = world.readEntity(d.getKey(), m.getKey());
        var overlay = reader.entity(d.getKey(), m.getKey());
        var start = genesis.readEntity(d.getKey(), m.getKey());
        var before = overlay != null ? overlay : start;
        if (before.equals(now)) continue;
        editor.setEntity(d.getKey(), m.getKey(), now.equals(start) ? null : now);
        changes.entity(d.getKey(), m.getKey(), before, now, marks.authors().get(m.getValue()));
      }
    }
    return new Capture(editor, changes);
  }

  /**
   * 有標記的 section 一律比較全部 64 個生態域格：生態域只能以指令或工具改，這些來源都會標記到所在的 section。
   * 作者取這個 section 第一個被標記的格子的作者。
   */
  private static void captureBiomes(SectionKey key, Marks.Section section, Marks marks, StateSource world, StateSource genesis,
      SectionOverlay parent, OverlayEditor editor, ChangesetBuilder changes) throws IOException {
    var now = world.readBiomes(key);
    if (now == null) return;
    String[] origin = null;
    for (int i = 0; i < 64; i++) {
      if (now[i] == null) continue;
      var overlay = parent.biome(i);
      if (now[i].equals(overlay)) continue;
      if (origin == null) {
        origin = genesis.readBiomes(key);
        if (origin == null) origin = new String[64];
      }
      var before = overlay != null ? overlay : origin[i];
      if (now[i].equals(before)) continue;
      editor.setBiome(key, i, now[i].equals(origin[i]) ? null : now[i]);
      changes.biome(key.dimension(), key.pos(SectionOverlay.biomeCorner(i)), section.author(marks, section.cells().nextSetBit(0)));
    }
  }

  private static Cell orAir(Cell c) {
    return c == null ? Cell.AIR : c;
  }

  // ---- 歷史 ----

  /** 從 {@code start} 往回的歷史，依時間由新到舊；每個提交只出現一次。 */
  public List<ObjectId> history(ObjectId start, int limit) throws IOException {
    var result = new ArrayList<ObjectId>();
    var seen = new HashSet<ObjectId>();
    var queue = new PriorityQueue<Map.Entry<ObjectId, Commit>>(
        Comparator.comparingLong((Map.Entry<ObjectId, Commit> e) -> e.getValue().time()).reversed()
            .thenComparing(Map.Entry::getKey));
    queue.add(Map.entry(start, commit(start)));
    seen.add(start);
    while (!queue.isEmpty() && result.size() < limit) {
      var e = queue.poll();
      result.add(e.getKey());
      for (var p : e.getValue().parents()) if (seen.add(p)) queue.add(Map.entry(p, commit(p)));
    }
    return result;
  }

  public boolean isAncestor(ObjectId ancestor, ObjectId of) throws IOException {
    var seen = new HashSet<ObjectId>();
    var stack = new ArrayDeque<ObjectId>(List.of(of));
    while (!stack.isEmpty()) {
      var id = stack.pop();
      if (id.equals(ancestor)) return true;
      if (seen.add(id)) stack.addAll(commit(id).parents());
    }
    return false;
  }

  /** 最近共同祖先。有多個候選時，取不是其他候選祖先的、時間最新的那個。 */
  public ObjectId mergeBase(ObjectId a, ObjectId b) throws IOException {
    var ofA = new HashSet<ObjectId>();
    var stack = new ArrayDeque<ObjectId>(List.of(a));
    while (!stack.isEmpty()) {
      var id = stack.pop();
      if (ofA.add(id)) stack.addAll(commit(id).parents());
    }
    var common = new ArrayList<ObjectId>();
    var seen = new HashSet<ObjectId>();
    stack.add(b);
    while (!stack.isEmpty()) {
      var id = stack.pop();
      if (!seen.add(id)) continue;
      if (ofA.contains(id)) common.add(id);
      else stack.addAll(commit(id).parents());
    }
    if (common.isEmpty()) throw new IOException("兩個提交沒有共同祖先");
    var best = new ArrayList<>(common);
    for (var c : common) for (var other : common) if (!c.equals(other) && isAncestor(c, other)) best.remove(c);
    best.sort(Comparator.comparingLong((ObjectId id) -> {
      try {
        return commit(id).time();
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }).reversed().thenComparing(Comparator.naturalOrder()));
    return best.getFirst();
  }

  // ---- 合併、還原 ----

  /**
   * 合併的試算，不改變任何 ref。
   *
   * @param ours 目標分支的提交
   * @param theirs 來源的提交
   * @param base 共同祖先；revert 時傳入要撤銷的提交
   * @param filter 只合併範圍內的變更（部分併回）；null 表示全部
   */
  public ThreeWayMerge.Result mergeTrees(ObjectId base, ObjectId ours, ObjectId theirs, Box filter) throws IOException {
    return mergeTrees(base, ours, theirs, null, filter);
  }

  /** @param filterDimension 範圍所在的維度；null 表示範圍套用到所有維度 */
  public ThreeWayMerge.Result mergeTrees(ObjectId base, ObjectId ours, ObjectId theirs, String filterDimension, Box filter)
      throws IOException {
    return ThreeWayMerge.merge(overlays, commit(base).tree(), commit(ours).tree(), commit(theirs).tree(), filterDimension, filter);
  }

  /**
   * 以 {@code resultTree} 為內容在 {@code branch} 上建立提交。合併、部分併回、revert、restore、衝突整合後都經過這裡。
   *
   * @param parents 第一個必須是 {@code branch} 目前的提交
   * @param resolution 衝突整合時在整合世界取得的標記與內容；沒有時為 null
   * @param defaultAuthor 不是由 {@code resolution} 標記的變更記在這個作者名下
   * @return 新提交；結果與目前提交相同時回傳 empty
   */
  public Optional<ObjectId> commitTree(
      String branch,
      List<ObjectId> parents,
      ObjectId resultTree,
      Resolution resolution,
      StateSource genesis,
      Author defaultAuthor,
      Author committer,
      long time,
      String message,
      int flags,
      SortedMap<String, String> trailers)
      throws IOException {
    lock.lock();
    try {
      var head = head(branch);
      if (!head.equals(parents.getFirst())) throw new ConcurrentUpdateException(branch);
      var tree = resultTree;
      Map<CellKey, Author> resolvedBy = Map.of();
      if (resolution != null && resolution.authors() != null) {
        resolvedBy = resolution.authors();
      } else if (resolution != null && !resolution.marks().isEmpty()) {
        var capture = capture(resultTree, resolution.marks(), resolution.world(), genesis);
        tree = capture.editor.build();
        resolvedBy = capture.changes.cellAuthors();
      }
      var headTree = commit(head).tree();
      if (tree.equals(headTree) && parents.size() == 1) return Optional.empty();
      var changes = new ChangesetBuilder();
      var diff = TreeDiff.collect(overlays, headTree, tree);
      Map<SectionKey, Cell[]> origins = new HashMap<>();
      for (var c : diff.cells()) {
        var before = c.before() != null ? c.before() : genesisCell(genesis, origins, c.dimension(), c.pos());
        var after = c.after() != null ? c.after() : genesisCell(genesis, origins, c.dimension(), c.pos());
        var author = resolvedBy.getOrDefault(new CellKey(c.dimension(), c.pos()), defaultAuthor);
        changes.cell(c.dimension(), c.pos(), before, after, author);
      }
      for (var b : diff.biomes()) {
        var corner = b.corner();
        changes.biome(b.key().dimension(), corner, resolvedBy.getOrDefault(new CellKey(b.key().dimension(), corner), defaultAuthor));
      }
      for (var e : diff.entities()) {
        var start = (e.before() == null || e.after() == null) ? genesis.readEntity(e.dimension(), e.uuid()) : null;
        changes.entity(e.dimension(), e.uuid(), e.before() != null ? e.before() : start,
            e.after() != null ? e.after() : start, defaultAuthor);
      }
      var changeset = put(changes.build());
      var id = put(new Commit(tree, parents, changeset, committer, time, message, flags, trailers));
      if (!refs.compareAndSet(branch, head, id)) throw new ConcurrentUpdateException(branch);
      return Optional.of(id);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 衝突整合的作者來源：整合世界的標記與讀取來源（結果樹會再收進這些格子），
   * 或已經提交好的整合結果中每格的作者（{@code authors} 不為 null 時，結果樹照用）。
   */
  public record Resolution(Marks marks, StateSource world, Map<CellKey, Author> authors) {
    public Resolution(Marks marks, StateSource world) {
      this(marks, world, null);
    }

    public static Resolution authors(Map<CellKey, Author> authors) {
      return new Resolution(null, null, authors);
    }
  }

  public record CellKey(String dimension, BlockPos pos) {}

  /**
   * 從 {@code head} 沿第一個父提交往回到 {@code stop}（不含）為止，每格最後的作者。
   * 衝突整合分支用來把整合時誰改了哪一格帶進合併提交。
   */
  public Map<CellKey, Author> authorsSince(ObjectId head, ObjectId stop) throws IOException {
    var result = new HashMap<CellKey, Author>();
    var id = head;
    while (id != null && !id.equals(stop)) {
      var commit = commit(id);
      var cs = changeset(commit.changeset());
      for (var c : cs.cells()) result.putIfAbsent(new CellKey(c.dimension(), c.pos()), cs.author(c.author()));
      for (var b : cs.biomes()) result.putIfAbsent(new CellKey(b.dimension(), b.corner()), cs.author(b.author()));
      id = commit.parents().isEmpty() ? null : commit.parents().getFirst();
    }
    return result;
  }

  private static Cell genesisCell(StateSource genesis, Map<SectionKey, Cell[]> cache, String dim, BlockPos pos)
      throws IOException {
    var key = SectionKey.of(dim, pos);
    var cells = cache.get(key);
    if (cells == null) {
      var all = new BitSet(4096);
      all.set(0, 4096);
      cells = genesis.readSection(key, all);
      cache.put(key, cells);
    }
    return orAir(cells[pos.sectionIndex()]);
  }

  /**
   * 把目前分支（或範圍內）還原成 {@code target} 的內容（設計 §5.4），回傳新的覆寫表根節點，由呼叫者以 {@link #commitTree} 提交。
   */
  public ObjectId restoreTree(ObjectId head, ObjectId target, Box filter) throws IOException {
    return restoreTree(head, target, null, filter);
  }

  /** @param filterDimension 範圍所在的維度；null 表示所有維度 */
  public ObjectId restoreTree(ObjectId head, ObjectId target, String filterDimension, Box filter) throws IOException {
    var headTree = commit(head).tree();
    var diff = TreeDiff.collect(overlays, headTree, commit(target).tree());
    var editor = new OverlayEditor(overlays, headTree);
    for (var c : diff.cells()) {
      if (filter != null && (filterDimension != null && !filterDimension.equals(c.dimension()) || !filter.contains(c.pos()))) continue;
      editor.setCell(c.dimension(), c.pos(), c.after());
    }
    for (var b : diff.biomes()) {
      if (filter != null && (filterDimension != null && !filterDimension.equals(b.key().dimension()) || !filter.intersects(b.bounds()))) continue;
      editor.setBiome(b.key(), b.index(), b.after());
    }
    for (var e : diff.entities()) {
      if (filter != null && filterDimension != null && !filterDimension.equals(e.dimension())) continue;
      if (filter != null) {
        var state = e.after() != null && e.after().isPresent() ? e.after() : e.before();
        double[] p = state != null && state.isPresent() ? state.get().position() : null;
        if (p == null || !filter.contains(p[0], p[1], p[2])) continue;
      }
      editor.setEntity(e.dimension(), e.uuid(), e.after());
    }
    return editor.isEmpty() ? headTree : editor.build();
  }

  // ---- 作者查詢 ----

  public record Blame(ObjectId commit, Author author, Changeset.Kind kind) {}

  /**
   * 某一格最後是誰、在哪次提交改的。沿第一個父提交往回找；遇到合併帶進來的變更（作者 {@link Author#MERGE}）時，改從第二個父提交找原作者。
   */
  public Optional<Blame> blame(ObjectId start, String dimension, BlockPos pos) throws IOException {
    var id = start;
    var mergeHit = (Blame) null;
    while (true) {
      var commit = commit(id);
      var cs = changeset(commit.changeset());
      var hit = cs.find(dimension, pos);
      if (hit.isPresent()) {
        var author = cs.author(hit.get().author());
        var blame = new Blame(id, author, hit.get().kind());
        if (!author.equals(Author.MERGE) || commit.parents().size() < 2) return Optional.of(blame);
        if (mergeHit == null) mergeHit = blame;
        id = commit.parents().get(1);
        continue;
      }
      if (commit.parents().isEmpty()) return Optional.ofNullable(mergeHit);
      id = commit.parents().getFirst();
    }
  }

  public static final class ConcurrentUpdateException extends IOException {
    public ConcurrentUpdateException(String branch) {
      super("分支 " + branch + " 已被其他操作更新，請重試");
    }
  }

  /** 累積 changeset 項目。 */
  static final class ChangesetBuilder {
    private final List<Author> authors = new ArrayList<>();
    private final Map<Author, Integer> index = new HashMap<>();
    private final List<Changeset.CellChange> cells = new ArrayList<>();
    private final List<Changeset.EntityChange> entities = new ArrayList<>();
    private final List<Changeset.BiomeChange> biomes = new ArrayList<>();
    private final Map<CellKey, Author> cellAuthors = new HashMap<>();

    private int author(Author a) {
      return index.computeIfAbsent(a, k -> {
        authors.add(k);
        return authors.size() - 1;
      });
    }

    void cell(String dim, BlockPos pos, Cell before, Cell after, Author author) {
      var kind = before.isAir() ? Changeset.Kind.ADDED : after.isAir() ? Changeset.Kind.REMOVED : Changeset.Kind.MODIFIED;
      cells.add(new Changeset.CellChange(dim, pos, kind, author(author)));
      cellAuthors.put(new CellKey(dim, pos), author);
    }

    void biome(String dim, BlockPos corner, Author author) {
      biomes.add(new Changeset.BiomeChange(dim, corner, author(author)));
    }

    void entity(String dim, UUID uuid, Optional<EntityState> before, Optional<EntityState> after, Author author) {
      var kind = before.isEmpty() ? Changeset.Kind.ADDED : after.isEmpty() ? Changeset.Kind.REMOVED : Changeset.Kind.MODIFIED;
      entities.add(new Changeset.EntityChange(dim, uuid, kind, author(author)));
    }

    boolean isEmpty() {
      return cells.isEmpty() && entities.isEmpty() && biomes.isEmpty();
    }

    Map<CellKey, Author> cellAuthors() {
      return cellAuthors;
    }

    Changeset build() {
      return new Changeset(authors, cells, entities, biomes);
    }
  }
}
