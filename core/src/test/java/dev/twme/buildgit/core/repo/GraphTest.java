package dev.twme.buildgit.core.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.twme.buildgit.core.store.ObjectId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphTest {
  private static ObjectId id(String name) {
    return ObjectId.of(java.util.Arrays.copyOf(name.getBytes(StandardCharsets.UTF_8), ObjectId.LENGTH));
  }

  @Test
  void draftForkedAndMergedBack() {
    var m1 = id("m1");
    var d1 = id("d1");
    var m2 = id("m2");
    var d2 = id("d2");
    var m3 = id("m3");
    var lanes = new ArrayList<ObjectId>();
    assertEquals("*─┐", Graph.row(lanes, m3, List.of(m2, d2)));
    assertEquals("│ *", Graph.row(lanes, d2, List.of(d1)));
    assertEquals("* │", Graph.row(lanes, m2, List.of(m1)));
    assertEquals("│ *", Graph.row(lanes, d1, List.of(m1)));
    assertEquals("*─┘", Graph.row(lanes, m1, List.of()));
    assertEquals(List.of(), lanes);
  }

  @Test
  void secondParentAlreadyOnAnotherLaneJoinsIt() {
    var base = id("base");
    var b = id("b");
    var merge = id("merge");
    // base 已經有一條線在等；新的合併存檔開在右邊，第二個父存檔接回那條線
    var lanes = new ArrayList<>(List.of(base));
    assertEquals("├─*", Graph.row(lanes, merge, List.of(b, base)));
    assertEquals(List.of(base, b), lanes);
  }

  @Test
  void passingACornerMakesATee() {
    var base = id("base");
    var lanes = new ArrayList<>(java.util.Arrays.asList(base, base, base));
    assertEquals("*─┴─┘", Graph.row(lanes, base, List.of()));
    assertEquals(List.of(), lanes);
  }

  @Test
  void visibleParentsKeepFirstParentFirst() throws java.io.IOException {
    var main = id("main");
    var base = id("base");
    var draft = id("draft");
    var merge = id("merge");
    var commits = new java.util.HashMap<ObjectId, Commit>();
    commits.put(merge, commit(0, main, draft));
    commits.put(main, commit(0));
    commits.put(draft, commit(Commit.AUTO, base));
    commits.put(base, commit(0));
    // 隱藏的草稿存檔被跳過，但第一個父存檔仍然在最前面（主線留在左邊）
    assertEquals(List.of(main, base), Graph.visibleParents(null, commits, merge, c -> !c.auto()));
  }

  private static Commit commit(int flags, ObjectId... parents) {
    return new Commit(id("tree"), List.of(parents), null, dev.twme.buildgit.core.model.Author.SYSTEM, 0, "", flags, new java.util.TreeMap<>());
  }
}
