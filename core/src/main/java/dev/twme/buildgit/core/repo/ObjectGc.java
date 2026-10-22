package dev.twme.buildgit.core.repo;

import dev.twme.buildgit.core.overlay.Tree;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectStore;
import dev.twme.buildgit.core.store.ObjectType;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 回收沒人用的物件：從所有起點（分支、進行中的整合、取出紀錄等）走過整個歷史，標記用得到的提交、tree 與內容，
 * 再刪掉物件目錄裡沒標記到、修改時間早於 {@code cutoff} 的檔案。
 *
 * <p>寬限期保護正在進行、還沒寫進分支的存檔：它們寫入或沿用的物件修改時間是新的（{@link ObjectStore#put}）。
 * 刪除前在物件目錄的鎖裡再看一次修改時間。
 */
public final class ObjectGc {
  private ObjectGc() {}

  public record Result(long kept, long deleted, long bytes) {}

  /**
   * 標記從 {@code roots}（提交）走得到的所有物件。不存在的起點略過（例如取出紀錄的存檔已經不在）；
   * 起點之下缺物件時丟出例外，呼叫的人不該清除。
   */
  public static Set<ObjectId> mark(ObjectStore store, Collection<ObjectId> roots) throws IOException {
    var seen = new HashSet<ObjectId>();
    var commits = new ArrayDeque<ObjectId>();
    for (var id : roots) if (store.contains(id)) commits.push(id);
    var trees = new ArrayDeque<ObjectId>();
    while (!commits.isEmpty()) {
      var id = commits.pop();
      if (!seen.add(id)) continue;
      var c = Commit.decode(store.get(id, ObjectType.COMMIT));
      if (c.changeset() != null) seen.add(c.changeset());
      commits.addAll(c.parents());
      if (c.tree() != null) trees.push(c.tree());
      while (!trees.isEmpty()) {
        var t = trees.pop();
        if (!seen.add(t)) continue;
        for (var e : Tree.decode(store.get(t, ObjectType.TREE)).entries().values()) {
          if (e.type() == ObjectType.TREE) trees.push(e.id());
          else seen.add(e.id());
        }
      }
    }
    return seen;
  }

  /** 刪掉 {@code root} 裡沒標記到、修改時間早於 {@code cutoff}（毫秒）的物件，以及留下來的舊暫存檔。 */
  public static Result sweep(Path root, Set<ObjectId> marked, long cutoff) throws IOException {
    long kept = 0, deleted = 0, bytes = 0;
    var lock = ObjectStore.lock(root);
    if (!Files.isDirectory(root)) return new Result(0, 0, 0);
    List<Path> dirs;
    try (var s = Files.list(root)) {
      dirs = s.filter(d -> Files.isDirectory(d) && d.getFileName().toString().matches("[0-9a-f]{2}")).toList();
    }
    for (var dir : dirs) {
      List<Path> files;
      try (var s = Files.list(dir)) {
        files = s.toList();
      }
      for (var f : files) {
        var name = f.getFileName().toString();
        boolean tmp = name.startsWith(".tmp-");
        if (!tmp) {
          if (!name.matches("[0-9a-f]{62}")) continue; // 不是物件
          if (marked.contains(ObjectId.parse(dir.getFileName() + name))) {
            kept++;
            continue;
          }
        }
        synchronized (lock) {
          try {
            if (Files.getLastModifiedTime(f).toMillis() >= cutoff) {
              kept++;
              continue;
            }
            long size = Files.size(f);
            Files.delete(f);
            if (!tmp) deleted++;
            bytes += size;
          } catch (NoSuchFileException e) {
            // 已經不在
          }
        }
      }
    }
    return new Result(kept, deleted, bytes);
  }
}
