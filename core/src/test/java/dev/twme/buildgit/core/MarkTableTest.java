package dev.twme.buildgit.core;

import static dev.twme.buildgit.core.FakeWorld.OVERWORLD;
import static org.junit.jupiter.api.Assertions.*;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.track.MarkJournal;
import dev.twme.buildgit.core.track.MarkTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.model.SectionKey;
import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkTableTest {
  static final Author ALICE = new Author(UUID.randomUUID(), "alice");
  static final Author BOB = new Author(UUID.randomUUID(), "bob");

  @TempDir Path dir;

  @Test
  void lastAuthorWinsAndDuplicatesAreIgnored() {
    var t = new MarkTable();
    var p = new BlockPos(-1, -64, 17);
    assertTrue(t.markCell(OVERWORLD, p, ALICE));
    assertFalse(t.markCell(OVERWORLD, p, ALICE));
    assertTrue(t.markCell(OVERWORLD, p, BOB));
    assertEquals(BOB, t.authorAt(OVERWORLD, p));
    assertEquals(1, t.cellCount());
    assertNull(t.authorAt("minecraft:the_end", p));
  }

  @Test
  void drainEmptiesAndRestoreKeepsNewerAuthors() {
    var t = new MarkTable();
    var p = new BlockPos(1, 2, 3);
    var q = new BlockPos(100, 2, 3);
    t.markCell(OVERWORLD, p, ALICE);
    t.markCell(OVERWORLD, q, ALICE);
    var entity = UUID.randomUUID();
    t.markEntity(OVERWORLD, entity, ALICE);
    var marks = t.drain();
    assertTrue(t.isEmpty());
    assertEquals(2, marks.cellCount());
    assertEquals(1, marks.entityCount());
    t.markCell(OVERWORLD, p, BOB); // 提交進行中又被改
    t.restore(marks); // 提交失敗，放回
    assertEquals(BOB, t.authorAt(OVERWORLD, p));
    assertEquals(ALICE, t.authorAt(OVERWORLD, q));
    assertEquals(ALICE, t.entityAuthor(OVERWORLD, entity));
    assertEquals(Map.of(BOB, 1, ALICE, 1), t.cellsByAuthor());
  }

  @Test
  void journalReplaysAcrossRestartIncludingTornTail() throws Exception {
    var t = new MarkTable();
    var journal = new MarkJournal(dir);
    t.markCell(OVERWORLD, new BlockPos(1, 2, 3), ALICE);
    t.markCell("minecraft:the_nether", new BlockPos(-5, 6, -7), BOB);
    var e = UUID.randomUUID();
    t.markEntity(OVERWORLD, e, BOB);
    journal.flush(t);
    t.markCell(OVERWORLD, new BlockPos(1, 2, 4), BOB); // 同 section 第二份快照：變成逐格作者
    journal.flush(t);
    journal.close();
    // 模擬當機：最後一筆只寫了一半
    var file = journal.files().getLast();
    Files.write(file, new byte[] {5, 0, 0}, StandardOpenOption.APPEND);

    var restored = new MarkTable();
    MarkJournal.replay(new MarkJournal(dir).files(), restored);
    assertEquals(ALICE, restored.authorAt(OVERWORLD, new BlockPos(1, 2, 3)));
    assertEquals(BOB, restored.authorAt(OVERWORLD, new BlockPos(1, 2, 4)));
    assertEquals(BOB, restored.authorAt("minecraft:the_nether", new BlockPos(-5, 6, -7)));
    assertEquals(BOB, restored.entityAuthor(OVERWORLD, e));
    assertEquals(3, restored.cellCount());
  }

  @Test
  void removedEntitiesSurviveRestartAndClearWhenPresentAgain() throws Exception {
    var t = new MarkTable();
    var journal = new MarkJournal(dir);
    var gone = UUID.randomUUID();
    var back = UUID.randomUUID();
    var unloaded = UUID.randomUUID();
    t.dimension(OVERWORLD).markEntityRemoved(gone, ALICE);
    t.dimension(OVERWORLD).markEntityRemoved(back, ALICE);
    t.markEntity(OVERWORLD, unloaded, ALICE);
    journal.flush(t);
    t.dimension(OVERWORLD).entityPresent(back); // 被放回
    journal.flush(t);
    journal.close();

    var restored = new MarkTable();
    MarkJournal.replay(new MarkJournal(dir).files(), restored);
    var marks = restored.drain();
    assertTrue(marks.isRemoved(OVERWORLD, gone));
    assertFalse(marks.isRemoved(OVERWORLD, back));
    assertFalse(marks.isRemoved(OVERWORLD, unloaded));
    assertEquals(3, marks.entityCount());
  }

  @Test
  void drainForCommitSeparatesCommittedMarks() throws Exception {
    var t = new MarkTable();
    var journal = new MarkJournal(dir);
    t.markCell(OVERWORLD, new BlockPos(1, 1, 1), ALICE);
    var drained = journal.drainForCommit(t);
    assertEquals(1, drained.marks().cellCount());
    t.markCell(OVERWORLD, new BlockPos(2, 2, 2), ALICE); // 新檔要重寫作者定義
    journal.flush(t);
    journal.delete(drained.previousFiles()); // 提交成功
    var restored = new MarkTable();
    MarkJournal.replay(journal.files(), restored);
    assertNull(restored.authorAt(OVERWORLD, new BlockPos(1, 1, 1)));
    assertEquals(ALICE, restored.authorAt(OVERWORLD, new BlockPos(2, 2, 2)));
  }

  @Test
  void failedCommitRestoresIntoJournal() throws Exception {
    var t = new MarkTable();
    var journal = new MarkJournal(dir);
    t.markCell(OVERWORLD, new BlockPos(1, 1, 1), ALICE);
    var drained = journal.drainForCommit(t);
    journal.restore(t, drained);
    journal.close();
    var restored = new MarkTable();
    MarkJournal.replay(journal.files(), restored);
    assertEquals(ALICE, restored.authorAt(OVERWORLD, new BlockPos(1, 1, 1)));
  }

  @Test
  void bulkSectionMarksSwitchToPerCellAuthors() {
    var t = new MarkTable();
    var d = t.dimension(OVERWORLD);
    var bits = new BitSet();
    bits.set(0, 4096);
    d.markSection(-1, 4, 2, bits, ALICE);
    assertEquals(4096, t.cellCount());
    assertEquals(ALICE, d.authorAt(-16, 64, 32));
    d.markCell(-1, 79, 47, BOB);
    assertEquals(BOB, d.authorAt(-1, 79, 47));
    assertEquals(ALICE, d.authorAt(-2, 79, 47));
    assertEquals(Map.of(ALICE, 4095, BOB, 1), t.cellsByAuthor());
    var marks = t.drain();
    var section = marks.sections().get(new SectionKey(OVERWORLD, -1, 4, 2));
    assertEquals(BOB, section.author(marks, new BlockPos(-1, 79, 47).sectionIndex()));
  }

  @Test
  void markBoxCoversExactlyTheBox() {
    var t = new MarkTable();
    t.dimension(OVERWORLD).markBox(new Box(-3, 10, 14, 17, 12, 18), ALICE);
    assertEquals(21 * 3 * 5, t.cellCount());
    assertTrue(t.isMarked(OVERWORLD, new BlockPos(-3, 10, 14)));
    assertTrue(t.isMarked(OVERWORLD, new BlockPos(17, 12, 18)));
    assertFalse(t.isMarked(OVERWORLD, new BlockPos(18, 12, 18)));
    assertFalse(t.isMarked(OVERWORLD, new BlockPos(-4, 10, 14)));
  }

  @Test
  void cascadeFollowsRecentNeighboursWithinWindowAndDistance() {
    long[] tick = {100};
    var t = new MarkTable(() -> tick[0]);
    var d = t.dimension(OVERWORLD);
    d.markCell(0, 64, 0, ALICE);
    assertEquals(ALICE, d.cascade(1, 64, 0, 200, 4)); // 相鄰
    assertEquals(ALICE, d.cascade(2, 64, 0, 200, 4)); // 沿鏈延伸
    assertNull(d.cascade(4, 64, 0, 200, 4)); // 不相鄰
    for (int x = 3; x <= 4; x++) assertEquals(ALICE, d.cascade(x, 64, 0, 200, 4));
    assertNull(d.cascade(5, 64, 0, 200, 4)); // 超過起點距離
    tick[0] += 201;
    assertNull(d.cascade(0, 65, 0, 200, 4)); // 超過時間窗
    assertEquals(ALICE, d.recentAuthor(4, 64, 0, 1000));
    assertNull(d.recentAuthor(4, 64, 0, 10));
  }
}
