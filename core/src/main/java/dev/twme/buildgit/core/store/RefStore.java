package dev.twme.buildgit.core.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 分支 ref：{@code refs/branches/<名稱>}，內容是提交雜湊。更新一律比對舊值（compare-and-set）。 */
public final class RefStore {
  /** 允許中文等文字，不允許路徑字元。 */
  private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}._-]{1,64}");

  private final Path dir;

  public RefStore(Path dir) throws IOException {
    this.dir = dir;
    Files.createDirectories(dir);
  }

  public static boolean validName(String name) {
    return NAME.matcher(name).matches() && !name.startsWith(".") && !name.endsWith(".lock");
  }

  private Path path(String name) {
    if (!validName(name)) throw new IllegalArgumentException("分支名稱無效：" + name);
    return dir.resolve(name);
  }

  public synchronized Optional<ObjectId> get(String name) throws IOException {
    try {
      return Optional.of(ObjectId.parse(Files.readString(path(name), StandardCharsets.US_ASCII).strip()));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }

  /**
   * 只有目前值等於 {@code expected} 時才更新；{@code expected} 為 null 表示分支必須不存在。
   *
   * @return 是否成功更新
   */
  public synchronized boolean compareAndSet(String name, ObjectId expected, ObjectId value) throws IOException {
    var current = get(name);
    if (expected == null ? current.isPresent() : !current.equals(Optional.of(expected))) return false;
    var path = path(name);
    var tmp = Files.createTempFile(dir, ".tmp-", "");
    try {
      Files.writeString(tmp, value.hex() + "\n", StandardCharsets.US_ASCII);
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
    return true;
  }

  public synchronized boolean delete(String name, ObjectId expected) throws IOException {
    if (!get(name).equals(Optional.of(expected))) return false;
    Files.delete(path(name));
    return true;
  }

  public synchronized SortedMap<String, ObjectId> list() throws IOException {
    var result = new TreeMap<String, ObjectId>();
    try (Stream<Path> files = Files.list(dir)) {
      for (var file : (Iterable<Path>) files::iterator) {
        String name = file.getFileName().toString();
        if (validName(name)) get(name).ifPresent(id -> result.put(name, id));
      }
    }
    return result;
  }
}
