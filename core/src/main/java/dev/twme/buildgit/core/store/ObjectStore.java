package dev.twme.buildgit.core.store;

import com.github.luben.zstd.Zstd;
import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 內容定址的物件儲存：{@code objects/ab/cdef…}，內容是 zstd 壓縮的「種類位元組＋資料」。
 * 物件不可變，所以寫入時已存在就略過；先寫暫存檔再改名，中途當機不會留下半個物件。
 */
public final class ObjectStore {
  private static final int LEVEL = 3;
  /** 記得最近寫過的物件，省去重複檢查檔案；有上限，否則會隨版本庫大小一直長。 */
  private static final int KNOWN = 1 << 16;
  private final Path root;
  private final Map<ObjectId, Boolean> known =
      Collections.synchronizedMap(
          new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ObjectId, Boolean> eldest) {
              return size() > KNOWN;
            }
          });

  public ObjectStore(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root);
  }

  public record Stored(ObjectType type, byte[] data) {}

  public ObjectId put(ObjectType type, byte[] data) throws IOException {
    byte[] raw = new byte[data.length + 1];
    raw[0] = (byte) type.code;
    System.arraycopy(data, 0, raw, 1, data.length);
    var id = ObjectId.hash(raw);
    if (known.containsKey(id)) return id;
    var path = path(id);
    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent());
      var tmp = Files.createTempFile(path.getParent(), ".tmp-", "");
      try {
        Files.write(tmp, Zstd.compress(raw, LEVEL));
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
      } catch (FileAlreadyExistsException e) {
        // 另一個執行緒同時寫了同一個物件，內容必然相同。
      } finally {
        Files.deleteIfExists(tmp);
      }
    }
    known.put(id, true);
    return id;
  }

  public Stored get(ObjectId id) throws IOException {
    byte[] compressed;
    try {
      compressed = Files.readAllBytes(path(id));
    } catch (NoSuchFileException e) {
      throw new MissingObjectException(id);
    }
    long size = Zstd.getFrameContentSize(compressed);
    if (size <= 0 || size > Integer.MAX_VALUE) throw new IOException("物件損毀：" + id);
    byte[] raw = Zstd.decompress(compressed, (int) size);
    if (!ObjectId.hash(raw).equals(id)) throw new IOException("物件雜湊不符：" + id);
    byte[] data = new byte[raw.length - 1];
    System.arraycopy(raw, 1, data, 0, data.length);
    return new Stored(ObjectType.of(raw[0]), data);
  }

  public byte[] get(ObjectId id, ObjectType expected) throws IOException {
    var stored = get(id);
    if (stored.type() != expected)
      throw new IOException("物件 " + id.abbrev() + " 是 " + stored.type() + "，預期 " + expected);
    return stored.data();
  }

  public boolean contains(ObjectId id) {
    return known.containsKey(id) || Files.exists(path(id));
  }

  /** 物件在磁碟上的檔案（壓縮後的內容）；備份原樣上傳。 */
  public Path file(ObjectId id) {
    return path(id);
  }

  private Path path(ObjectId id) {
    String hex = id.hex();
    return root.resolve(hex.substring(0, 2)).resolve(hex.substring(2));
  }

  public static final class MissingObjectException extends IOException {
    public MissingObjectException(ObjectId id) {
      super("找不到物件：" + id);
    }
  }
}
