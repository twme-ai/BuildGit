package dev.twme.buildgit.core;

import static dev.twme.buildgit.core.FakeWorld.OVERWORLD;
import static org.junit.jupiter.api.Assertions.*;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.overlay.Tree;
import dev.twme.buildgit.core.repo.Changeset;
import dev.twme.buildgit.core.repo.Commit;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.track.MarkTable;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTest {
  static final Author ALICE = new Author(UUID.randomUUID(), "alice");
  static final Author BOB = new Author(UUID.randomUUID(), "bob");
  static final String NETHER = "minecraft:the_nether";

  @TempDir Path dir;
  Repository repo;
  FakeWorld genesis;
  long clock = 1000;

  @BeforeEach
  void setUp() throws Exception {
    genesis = new FakeWorld().set(0, 64, 0, "minecraft:grass_block").set(1, 64, 0, "minecraft:grass_block");
    repo = Repository.init(dir, Author.SYSTEM, clock);
  }

  /** 一個分支的工作世界與它的標記表。 */
  final class Work {
    final String branch;
    final FakeWorld world;
    final MarkTable marks = new MarkTable();

    Work(String branch, FakeWorld world) {
      this.branch = branch;
      this.world = world;
    }

    Work set(Author who, int x, int y, int z, String state) {
      world.set(x, y, z, state);
      marks.markCell(OVERWORLD, new BlockPos(x, y, z), who);
      return this;
    }

    Optional<ObjectId> commit(String message) throws Exception {
      var drained = marks.drain();
      return repo.commit(new Repository.CommitRequest(branch, repo.head(branch), drained, world, genesis, ALICE,
          ++clock, message, 0, new TreeMap<>()));
    }
  }

  Work main() {
    return new Work(Repository.MAIN, genesis.copy());
  }

  Work branch(String name, Work from) throws Exception {
    repo.createBranch(name, repo.head(from.branch));
    return new Work(name, from.world.copy());
  }

  /** 無衝突時把 theirs 併進 ours，回傳新提交。 */
  ObjectId merge(Work ours, Work theirs, Box filter) throws Exception {
    var o = repo.head(ours.branch);
    var t = repo.head(theirs.branch);
    var result = repo.mergeTrees(repo.mergeBase(o, t), o, t, filter);
    assertTrue(result.clean(), "預期沒有衝突：" + result.cellConflicts());
    var parents = filter == null ? List.of(o, t) : List.of(o);
    return repo.commitTree(ours.branch, parents, result.tree(), null, genesis, Author.MERGE, ALICE, ++clock, "merge",
        0, new TreeMap<>()).orElseThrow();
  }

  Cell cellAt(ObjectId commit, int x, int y, int z) throws Exception {
    var c = repo.reader(commit).cell(OVERWORLD, new BlockPos(x, y, z));
    return c != null ? c : genesis.get(x, y, z);
  }

  @Test
  void initCreatesEmptyMain() throws Exception {
    var head = repo.head(Repository.MAIN);
    assertTrue(repo.overlays().tree(repo.commit(head).tree()).entries().isEmpty());
    assertThrows(java.io.IOException.class, () -> Repository.init(dir, Author.SYSTEM, 0));
  }

  @Test
  void commitStoresOnlyMarkedChangedCells() throws Exception {
    var w = main();
    w.set(ALICE, 0, 65, 0, "minecraft:oak_planks");
    w.set(BOB, 5, 70, 5, "minecraft:glass");
    w.marks.markCell(OVERWORLD, new BlockPos(9, 9, 9), ALICE); // 被標記但沒改變
    w.world.set(20, 64, 20, "minecraft:wheat[age=7]"); // 自然變化，沒有標記
    var id = w.commit("first").orElseThrow();
    var commit = repo.commit(id);
    var cs = repo.changeset(commit.changeset());
    assertEquals(2, cs.cells().size());
    assertEquals(Map.of(ALICE, 1, BOB, 1), cs.cellsByAuthor());
    assertEquals(Cell.of("minecraft:oak_planks"), repo.reader(id).cell(OVERWORLD, new BlockPos(0, 65, 0)));
    assertNull(repo.reader(id).cell(OVERWORLD, new BlockPos(20, 64, 20)), "自然變化不進版本");
    assertEquals(Changeset.Kind.ADDED, cs.find(OVERWORLD, new BlockPos(0, 65, 0)).orElseThrow().kind());
  }

  @Test
  void noChangesMeansNoCommit() throws Exception {
    var w = main();
    w.marks.markCell(OVERWORLD, new BlockPos(0, 64, 0), ALICE);
    assertTrue(w.commit("nothing").isEmpty());
    assertTrue(w.commit("still nothing").isEmpty());
  }

  @Test
  void returningToGenesisRemovesOverlay() throws Exception {
    var w = main();
    w.set(ALICE, 0, 64, 0, "minecraft:stone").commit("change").orElseThrow();
    var back = w.set(ALICE, 0, 64, 0, "minecraft:grass_block").commit("back").orElseThrow();
    assertTrue(repo.overlays().tree(repo.commit(back).tree()).entries().isEmpty(), "與起點相同時覆寫表為空");
    var cs = repo.changeset(repo.commit(back).changeset());
    assertEquals(Changeset.Kind.MODIFIED, cs.cells().getFirst().kind());
  }

  @Test
  void removalIsRecorded() throws Exception {
    var w = main();
    var id = w.set(BOB, 1, 64, 0, "minecraft:air").commit("dig").orElseThrow();
    assertEquals(Cell.AIR, repo.reader(id).cell(OVERWORLD, new BlockPos(1, 64, 0)));
    assertEquals(Changeset.Kind.REMOVED, repo.changeset(repo.commit(id).changeset()).cells().getFirst().kind());
  }

  @Test
  void staleHeadIsRejected() throws Exception {
    var w = main();
    var stale = repo.head(Repository.MAIN);
    w.set(ALICE, 3, 64, 3, "minecraft:stone").commit("a").orElseThrow();
    w.set(ALICE, 4, 64, 3, "minecraft:stone");
    var drained = w.marks.drain();
    assertThrows(Repository.ConcurrentUpdateException.class, () -> repo.commit(new Repository.CommitRequest(
        Repository.MAIN, stale, drained, w.world, genesis, ALICE, 5, "b", 0, new TreeMap<>())));
  }

  @Test
  void entitiesUseTombstonesForGenesisEntities() throws Exception {
    var stand = UUID.randomUUID();
    var frame = UUID.randomUUID();
    genesis.entity(stand, 0.5, 65, 0.5, "minecraft:armor_stand");
    var w = main();
    w.world.removeEntity(stand).entity(frame, 2.5, 66, 0.5, "minecraft:item_frame");
    w.marks.markEntity(OVERWORLD, stand, ALICE);
    w.marks.markEntity(OVERWORLD, frame, ALICE);
    var id = w.commit("entities").orElseThrow();
    assertEquals(Optional.empty(), repo.reader(id).entity(OVERWORLD, stand), "起點實體被移除 → 墓碑");
    assertTrue(repo.reader(id).entity(OVERWORLD, frame).isPresent());
    var kinds = repo.changeset(repo.commit(id).changeset()).entities().stream().map(Changeset.EntityChange::kind).toList();
    assertTrue(kinds.containsAll(List.of(Changeset.Kind.ADDED, Changeset.Kind.REMOVED)));
    // 放回原狀 → 覆寫消失
    w.world.entity(stand, 0.5, 65, 0.5, "minecraft:armor_stand");
    w.marks.markEntity(OVERWORLD, stand, ALICE);
    var back = w.commit("restore").orElseThrow();
    assertNull(repo.reader(back).entity(OVERWORLD, stand));
  }

  @Test
  void cleanMergeCombinesBothSides() throws Exception {
    var main = main();
    var draft = branch("屋頂B", main);
    main.set(ALICE, 0, 70, 0, "minecraft:stone").commit("main work").orElseThrow();
    draft.set(BOB, 600, 70, 600, "minecraft:glass").commit("draft work").orElseThrow();
    var merged = merge(main, draft, null);
    assertEquals(Cell.of("minecraft:stone"), cellAt(merged, 0, 70, 0));
    assertEquals(Cell.of("minecraft:glass"), cellAt(merged, 600, 70, 600));
    assertEquals(2, repo.commit(merged).parents().size());
    var blame = repo.blame(merged, OVERWORLD, new BlockPos(600, 70, 600)).orElseThrow();
    assertEquals(BOB, blame.author(), "合併帶進來的格子追到原作者");
    assertEquals(ALICE, repo.blame(merged, OVERWORLD, new BlockPos(0, 70, 0)).orElseThrow().author());
  }

  @Test
  void sameChangeOnBothSidesIsNotAConflict() throws Exception {
    var main = main();
    var draft = branch("d", main);
    main.set(ALICE, 0, 70, 0, "minecraft:stone").commit("a").orElseThrow();
    draft.set(BOB, 0, 70, 0, "minecraft:stone").commit("b").orElseThrow();
    merge(main, draft, null);
  }

  @Test
  void conflictingCellsAreReportedAndKeepOurs() throws Exception {
    var main = main();
    var draft = branch("d", main);
    main.set(ALICE, 0, 70, 0, "minecraft:stone").set(ALICE, 1, 70, 0, "minecraft:dirt").commit("a").orElseThrow();
    draft.set(BOB, 0, 70, 0, "minecraft:glass").set(BOB, 2, 70, 0, "minecraft:sand").commit("b").orElseThrow();
    var o = repo.head("main");
    var t = repo.head("d");
    var result = repo.mergeTrees(repo.mergeBase(o, t), o, t, null);
    assertEquals(1, result.cellConflicts().size());
    var conflict = result.cellConflicts().getFirst();
    assertEquals(new BlockPos(0, 70, 0), conflict.pos());
    assertNull(conflict.base());
    assertEquals(Cell.of("minecraft:stone"), conflict.ours());
    assertEquals(Cell.of("minecraft:glass"), conflict.theirs());
    var reader = new dev.twme.buildgit.core.overlay.OverlayReader(repo.overlays(), result.tree());
    assertEquals(Cell.of("minecraft:stone"), reader.cell(OVERWORLD, new BlockPos(0, 70, 0)), "衝突格先放 ours");
    assertEquals(Cell.of("minecraft:sand"), reader.cell(OVERWORLD, new BlockPos(2, 70, 0)), "非衝突格照常合併");
  }

  @Test
  void conflictResolutionCapturesIntegrationWorld() throws Exception {
    var main = main();
    var draft = branch("d", main);
    main.set(ALICE, 0, 70, 0, "minecraft:stone").commit("a").orElseThrow();
    draft.set(BOB, 0, 70, 0, "minecraft:glass").set(BOB, 5, 70, 0, "minecraft:sand").commit("b").orElseThrow();
    var o = repo.head("main");
    var t = repo.head("d");
    var result = repo.mergeTrees(repo.mergeBase(o, t), o, t, null);
    // 玩家在整合世界把衝突格改成第三種方塊
    var integration = new Work("integration", main.world.copy());
    integration.world.set(5, 70, 0, "minecraft:sand");
    integration.set(ALICE, 0, 70, 0, "minecraft:quartz_block");
    var id = repo.commitTree("main", List.of(o, t), result.tree(),
        new Repository.Resolution(integration.marks.drain(), integration.world), genesis, Author.MERGE, ALICE, ++clock,
        "resolve", 0, new TreeMap<>()).orElseThrow();
    assertEquals(Cell.of("minecraft:quartz_block"), cellAt(id, 0, 70, 0));
    assertEquals(Cell.of("minecraft:sand"), cellAt(id, 5, 70, 0));
    assertEquals(ALICE, repo.blame(id, OVERWORLD, new BlockPos(0, 70, 0)).orElseThrow().author());
    assertEquals(BOB, repo.blame(id, OVERWORLD, new BlockPos(5, 70, 0)).orElseThrow().author());
  }

  @Test
  void partialPublishTakesOnlySelectionAndLaterFullMergeIsClean() throws Exception {
    var main = main();
    var draft = branch("d", main);
    draft.set(BOB, 10, 70, 10, "minecraft:glass").set(BOB, 100, 70, 100, "minecraft:sand").commit("two builds").orElseThrow();
    var partial = merge(main, draft, new Box(0, 0, 0, 20, 100, 20));
    assertEquals(Cell.of("minecraft:glass"), cellAt(partial, 10, 70, 10));
    assertEquals(Cell.AIR, cellAt(partial, 100, 70, 100), "範圍外不併回");
    assertEquals(1, repo.commit(partial).parents().size(), "部分併回是單親提交");
    var full = merge(main, draft, null);
    assertEquals(Cell.of("minecraft:sand"), cellAt(full, 100, 70, 100));
  }

  @Test
  void selectionIsLimitedToItsDimension() throws Exception {
    var main = main();
    var draft = branch("d", main);
    var pos = new BlockPos(10, 70, 10);
    draft.set(BOB, 10, 70, 10, "minecraft:glass");
    draft.world.set(NETHER, pos, Cell.of("minecraft:netherrack"));
    draft.marks.markCell(NETHER, pos, BOB);
    draft.commit("both dimensions").orElseThrow();
    var o = repo.head("main");
    var t = repo.head("d");
    var result = repo.mergeTrees(repo.mergeBase(o, t), o, t, OVERWORLD, new Box(0, 0, 0, 20, 100, 20));
    assertTrue(result.clean());
    var reader = new dev.twme.buildgit.core.overlay.OverlayReader(repo.overlays(), result.tree());
    assertEquals(Cell.of("minecraft:glass"), reader.cell(OVERWORLD, pos));
    assertNull(reader.cell(NETHER, pos), "另一個維度的同一座標不在選取範圍內");

    var restored = repo.restoreTree(t, o, NETHER, new Box(0, 0, 0, 20, 100, 20));
    var r = new dev.twme.buildgit.core.overlay.OverlayReader(repo.overlays(), restored);
    assertNull(r.cell(NETHER, pos), "地獄的範圍還原了");
    assertEquals(Cell.of("minecraft:glass"), r.cell(OVERWORLD, pos), "主世界不受影響");
  }

  @Test
  void authorsSinceAndExplicitResolutionAuthors() throws Exception {
    var main = main();
    var draft = branch("d", main);
    var base = repo.head("d");
    draft.set(BOB, 1, 70, 0, "minecraft:stone").set(BOB, 2, 70, 0, "minecraft:stone").commit("bob").orElseThrow();
    draft.set(ALICE, 2, 70, 0, "minecraft:glass").commit("alice").orElseThrow();
    var by = repo.authorsSince(repo.head("d"), base);
    assertEquals(BOB, by.get(new Repository.CellKey(OVERWORLD, new BlockPos(1, 70, 0))));
    assertEquals(ALICE, by.get(new Repository.CellKey(OVERWORLD, new BlockPos(2, 70, 0))), "最新的作者優先");

    var o = repo.head("main");
    var t = repo.head("d");
    var result = repo.mergeTrees(repo.mergeBase(o, t), o, t, null);
    var id = repo.commitTree("main", List.of(o), result.tree(), Repository.Resolution.authors(by), genesis, Author.MERGE, ALICE,
        ++clock, "partial", 0, new TreeMap<>()).orElseThrow();
    var cs = repo.changeset(repo.commit(id).changeset());
    assertEquals(Map.of(BOB, 1, ALICE, 1), cs.cellsByAuthor());
    assertEquals(BOB, repo.blame(id, OVERWORLD, new BlockPos(1, 70, 0)).orElseThrow().author(), "單親提交也追得到作者");
  }

  @Test
  void revertUndoesOneCommitAndKeepsLaterWork() throws Exception {
    var main = main();
    var bad = main.set(BOB, 0, 70, 0, "minecraft:tnt").commit("oops").orElseThrow();
    main.set(ALICE, 3, 70, 0, "minecraft:stone").commit("later").orElseThrow();
    var head = repo.head("main");
    var badParent = repo.commit(bad).parents().getFirst();
    var result = repo.mergeTrees(bad, head, badParent, null);
    assertTrue(result.clean());
    var id = repo.commitTree("main", List.of(head), result.tree(), null, genesis, ALICE, ALICE, ++clock, "revert", 0,
        new TreeMap<>()).orElseThrow();
    assertEquals(Cell.AIR, cellAt(id, 0, 70, 0));
    assertEquals(Cell.of("minecraft:stone"), cellAt(id, 3, 70, 0));
  }

  @Test
  void restoreSelectionOnly() throws Exception {
    var main = main();
    var old = main.set(ALICE, 0, 70, 0, "minecraft:stone").set(ALICE, 50, 70, 0, "minecraft:stone").commit("v1").orElseThrow();
    main.set(BOB, 0, 70, 0, "minecraft:glass").set(BOB, 50, 70, 0, "minecraft:glass").commit("v2").orElseThrow();
    var head = repo.head("main");
    var tree = repo.restoreTree(head, old, new Box(-5, 0, -5, 5, 100, 5));
    var id = repo.commitTree("main", List.of(head), tree, null, genesis, ALICE, ALICE, ++clock, "restore", 0,
        new TreeMap<>()).orElseThrow();
    assertEquals(Cell.of("minecraft:stone"), cellAt(id, 0, 70, 0));
    assertEquals(Cell.of("minecraft:glass"), cellAt(id, 50, 70, 0));
  }

  @Test
  void historyAndMergeBase() throws Exception {
    var main = main();
    var a = main.set(ALICE, 0, 70, 0, "minecraft:stone").commit("a").orElseThrow();
    var draft = branch("d", main);
    var b = draft.set(BOB, 9, 70, 0, "minecraft:stone").commit("b").orElseThrow();
    var c = main.set(ALICE, 1, 70, 0, "minecraft:stone").commit("c").orElseThrow();
    assertEquals(a, repo.mergeBase(b, c));
    var merged = merge(main, draft, null);
    assertEquals(List.of(merged, c, b, a), repo.history(merged, 4));
    assertTrue(repo.isAncestor(b, merged));
    assertFalse(repo.isAncestor(merged, b));
    var trailers = repo.commit(merged).trailers();
    assertFalse(trailers.containsKey(Commit.SOURCE_DRAFT));
  }

  @Test
  void dimensionsAreSeparate() throws Exception {
    var w = main();
    var pos = new BlockPos(0, 70, 0);
    w.world.set("minecraft:the_nether", pos, Cell.of("minecraft:netherrack"));
    w.marks.markCell("minecraft:the_nether", pos, ALICE);
    var id = w.commit("nether").orElseThrow();
    assertNull(repo.reader(id).cell(OVERWORLD, pos));
    assertEquals(Cell.of("minecraft:netherrack"), repo.reader(id).cell("minecraft:the_nether", pos));
    assertEquals(Set.of("minecraft:the_nether"), repo.overlays().tree(repo.commit(id).tree()).entries().keySet());
    assertNotNull(repo.overlays().tree(repo.commit(id).tree()).get("minecraft:the_nether"));
    assertNull(repo.overlays().tree(repo.commit(id).tree()).get(Tree.ENTITIES));
  }

  @Test
  void branchNamesAreValidated() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> repo.createBranch("../evil", repo.head("main")));
    repo.createBranch("屋頂方案_B-2", repo.head("main"));
    assertThrows(java.io.IOException.class, () -> repo.createBranch("屋頂方案_B-2", repo.head("main")));
    assertThrows(java.io.IOException.class, () -> repo.deleteBranch("main"));
    repo.deleteBranch("屋頂方案_B-2");
    assertEquals(Set.of("main"), repo.refs().list().keySet());
  }

  @Test
  void largeCommitTouchesOnlyChangedSections() throws Exception {
    var w = main();
    for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) w.set(ALICE, x, 80, z, "minecraft:stone");
    var first = w.commit("floor").orElseThrow();
    var w2 = new Work("main", w.world);
    w2.set(BOB, 0, 81, 0, "minecraft:torch");
    var second = w2.commit("torch").orElseThrow();
    var diff = dev.twme.buildgit.core.overlay.TreeDiff.collect(repo.overlays(), repo.commit(first).tree(),
        repo.commit(second).tree());
    assertEquals(1, diff.cells().size());
    assertEquals(64 * 64, repo.changeset(repo.commit(first).changeset()).cells().size());
  }
}
