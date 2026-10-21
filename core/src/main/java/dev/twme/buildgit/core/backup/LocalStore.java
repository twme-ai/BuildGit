package dev.twme.buildgit.core.backup;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 本機資料夾當備份的目的地（設計 §2.7）：鍵就是相對路徑。複製用硬連結，不佔空間。 */
public final class LocalStore implements BackupStore {
  private final Path root;

  public LocalStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  private Path file(String key) {
    var f = root.resolve(key).normalize();
    if (!f.startsWith(root)) throw new IllegalArgumentException("鍵超出備份資料夾：" + key);
    return f;
  }

  @Override
  public void put(String key, byte[] data) throws IOException {
    var f = file(key);
    Files.createDirectories(f.getParent());
    var tmp = f.resolveSibling(f.getFileName() + ".tmp");
    Files.write(tmp, data);
    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  @Override
  public String putFile(String key, Path source) throws IOException {
    var f = file(key);
    Files.createDirectories(f.getParent());
    var tmp = f.resolveSibling(f.getFileName() + ".tmp");
    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    return null;
  }

  @Override
  public byte[] get(String key) throws IOException {
    var f = file(key);
    return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
  }

  @Override
  public void delete(String key) throws IOException {
    var f = file(key);
    Files.deleteIfExists(f);
    // 留下的空資料夾一起刪
    for (var d = f.getParent(); d != null && !d.equals(root) && d.startsWith(root); d = d.getParent()) {
      try (var s = Files.list(d)) {
        if (s.findAny().isPresent()) break;
      }
      Files.delete(d);
    }
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    int slash = prefix.lastIndexOf('/');
    var start = slash < 0 ? root : file(prefix.substring(0, slash));
    var keys = new ArrayList<String>();
    if (!Files.isDirectory(start)) return keys;
    try (var s = Files.walk(start)) {
      s.filter(Files::isRegularFile).forEach(p -> {
        String key = root.relativize(p).toString().replace('\\', '/');
        if (key.startsWith(prefix) && !key.endsWith(".tmp")) keys.add(key);
      });
    }
    Collections.sort(keys);
    return keys;
  }

  @Override
  public void copy(String from, String to) throws IOException {
    var source = file(from);
    var target = file(to);
    Files.createDirectories(target.getParent());
    Files.deleteIfExists(target);
    try {
      Files.createLink(target, source);
    } catch (UnsupportedOperationException | IOException e) {
      Files.copy(source, target);
    }
  }

  @Override
  public String toString() {
    return root.toString();
  }
}
