package dev.twme.buildgit.core.backup;

import dev.twme.buildgit.core.overlay.Tree;
import dev.twme.buildgit.core.repo.Commit;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectStore;
import dev.twme.buildgit.core.store.ObjectType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 從備份補齊物件（設計 §2.5）：從提交往下走（parents、tree、changeset），本機沒有的才下載，同一層同時下載。
 * 物件總是在它引用的物件之後寫入，所以本機已有的物件底下也都齊全，不必再往下走。
 */
public final class ObjectFetch {
  private ObjectFetch() {}

  /** 遠端的物件檔（和本機相同的壓縮格式）；不存在時為 null。 */
  public interface Source {
    byte[] get(ObjectId id) throws IOException;
  }

  /**
   * @param checkExisting 本機已有的物件也往下走：上次補到一半中斷時，先下載的物件底下可能還缺
   * @return 下載的物件數
   */
  public static int fetch(ObjectStore store, Collection<ObjectId> heads, boolean checkExisting, Source source, ExecutorService pool)
      throws IOException, InterruptedException {
    var seen = new HashSet<ObjectId>();
    var frontier = new ArrayList<ObjectId>();
    for (var h : heads) if (seen.add(h)) frontier.add(h);
    int count = 0;
    while (!frontier.isEmpty()) {
      var downloads = new LinkedHashMap<ObjectId, Future<?>>();
      for (var id : frontier) if (!Files.exists(store.file(id))) downloads.put(id, pool.submit(() -> download(store, id, source)));
      for (var f : downloads.values())
        try {
          f.get();
        } catch (java.util.concurrent.ExecutionException e) {
          throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
      count += downloads.size();
      var next = new ArrayList<ObjectId>();
      for (var id : frontier) {
        if (!checkExisting && !downloads.containsKey(id)) continue;
        var stored = store.get(id);
        if (stored.type() == ObjectType.COMMIT) {
          var c = Commit.decode(stored.data());
          next.addAll(c.parents());
          next.add(c.tree());
          next.add(c.changeset());
        } else if (stored.type() == ObjectType.TREE) {
          for (var e : Tree.decode(stored.data()).entries().values()) next.add(e.id());
        }
      }
      frontier = new ArrayList<>();
      for (var id : next) if (seen.add(id)) frontier.add(id);
    }
    return count;
  }

  private static Void download(ObjectStore store, ObjectId id, Source source) throws IOException {
    var bytes = source.get(id);
    if (bytes == null) throw new IOException("備份缺少物件 " + id.hex());
    var file = store.file(id);
    Files.createDirectories(file.getParent());
    var tmp = file.resolveSibling(".fetch-" + file.getFileName());
    Files.write(tmp, bytes);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    try {
      store.get(id); // 雜湊不符時丟出例外
    } catch (IOException e) {
      Files.deleteIfExists(file);
      throw e;
    }
    return null;
  }
}
