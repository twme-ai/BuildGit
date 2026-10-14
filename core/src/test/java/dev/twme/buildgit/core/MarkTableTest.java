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
    t.listener(journal);
    t.markCell(OVERWORLD, new BlockPos(1, 2, 3), ALICE);
    t.markCell("minecraft:the_nether", new BlockPos(-5, 6, -7), BOB);
    var e = UUID.randomUUID();
    t.markEntity(OVERWORLD, e, BOB);
    journal.flush();
    journal.close();
    // 模擬當機：最後一筆只寫了一半
    var file = journal.files().getLast();
    Files.write(file, new byte[] {3, 0, 0}, StandardOpenOption.APPEND);

    var restored = new MarkTable();
    MarkJournal.replay(new MarkJournal(dir).files(), restored);
    assertEquals(ALICE, restored.authorAt(OVERWORLD, new BlockPos(1, 2, 3)));
    assertEquals(BOB, restored.authorAt("minecraft:the_nether", new BlockPos(-5, 6, -7)));
    assertEquals(BOB, restored.entityAuthor(OVERWORLD, e));
    assertEquals(2, restored.cellCount());
  }

  @Test
  void rotateSeparatesCommittedMarks() throws Exception {
    var t = new MarkTable();
    var journal = new MarkJournal(dir);
    t.listener(journal);
    t.markCell(OVERWORLD, new BlockPos(1, 1, 1), ALICE);
    var old = journal.rotate();
    t.drain();
    t.markCell(OVERWORLD, new BlockPos(2, 2, 2), ALICE); // 新檔要重寫作者定義
    journal.flush();
    journal.delete(old); // 提交成功
    var restored = new MarkTable();
    MarkJournal.replay(journal.files(), restored);
    assertNull(restored.authorAt(OVERWORLD, new BlockPos(1, 1, 1)));
    assertEquals(ALICE, restored.authorAt(OVERWORLD, new BlockPos(2, 2, 2)));
  }
}
