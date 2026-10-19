package dev.twme.buildgit.core.backup;

import dev.twme.buildgit.core.overlay.Tree;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectType;
import java.io.IOException;
import java.util.*;

/**
 * 備份要上傳哪些物件（設計 §2.5）：從分支指向的提交往回走到已上傳的提交為止。
 * 每個新提交的 tree 只往下走和第一個父提交不同的子樹：相同的子樹在父提交上傳時已經上傳過。
 */
public final class ObjectWalk {
  private ObjectWalk() {}

  /** 要上傳的提交（父提交在前）與它們帶進來的物件。 */
  public record Result(List<ObjectId> commits, Set<ObjectId> objects) {}

  public static Result collect(Repository repo, Collection<ObjectId> heads, Set<ObjectId> uploaded) throws IOException {
    var commits = new ArrayList<ObjectId>();
    var seen = new HashSet<ObjectId>();
    var stack = new ArrayDeque<>(heads);
    while (!stack.isEmpty()) {
      var id = stack.pop();
      if (uploaded.contains(id) || !seen.add(id)) continue;
      commits.add(id);
      stack.addAll(repo.commit(id).parents());
    }
    Collections.reverse(commits);
    var objects = new LinkedHashSet<ObjectId>();
    for (var id : commits) {
      var c = repo.commit(id);
      objects.add(id);
      objects.add(c.changeset());
      tree(repo, c.tree(), c.parents().isEmpty() ? null : repo.commit(c.parents().getFirst()).tree(), objects);
    }
    return new Result(commits, objects);
  }

  private static void tree(Repository repo, ObjectId id, ObjectId base, Set<ObjectId> out) throws IOException {
    if (id.equals(base) || !out.add(id)) return;
    var tree = repo.overlays().tree(id);
    var old = base == null ? Tree.EMPTY : repo.overlays().tree(base);
    for (var e : tree.entries().entrySet()) {
      var entry = e.getValue();
      var before = old.get(e.getKey());
      if (entry.type() == ObjectType.TREE) tree(repo, entry.id(), before != null && before.type() == ObjectType.TREE ? before.id() : null, out);
      else if (before == null || !before.id().equals(entry.id())) out.add(entry.id());
    }
  }
}
