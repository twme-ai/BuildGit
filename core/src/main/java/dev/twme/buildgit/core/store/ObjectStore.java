package dev.twme.buildgit.core.store;

import com.github.luben.zstd.Zstd;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 內容定址的物件儲存：{@code objects/ab/cdef…}，內容是 zstd 壓縮的「種類位元組＋資料」。
 * 物件不可變，所以寫入時已存在就略過；先寫暫存檔再改名，中途當機不會留下半個物件。
 *
 * <p>回收沒人用的物件（{@code ObjectGc}）只刪修改時間夠舊的，所以寫入時已存在、或確認存在要拿來用時，更新它的修改時間
 * （同一個物件一小時內只更新一次）；更新與刪除在同一個鎖裡，不會刪掉剛說要用的物件。
 */
public final class ObjectStore {
  private static final int LEVEL = 3;
  /** 記得最近寫過的物件，省去重複檢查檔案；有上限，否則會隨版本庫大小一直長。 */
  private static final int KNOWN = 1 << 16;
  /** 多久更新一次已存在物件的修改時間（毫秒）；必須比回收的寬限期短很多。 */
  public static final long TOUCH_MILLIS = 3600_000L;
  /** 每個物件目錄一把鎖（好幾個版本庫可能共用同一個目錄）。 */
  private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
  private final Path root;
  private final Object lock;
  /** 最近寫過或確認過的物件與當時的時間。 */
  private final Map<ObjectId, Long> known =
      Collections.synchronizedMap(
          new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ObjectId, Long> eldest) {
              return size() > KNOWN;
            }
          });

  public ObjectStore(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root);
    lock = lock(root);
  }

  /** 物件目錄的鎖：回收時刪除與更新修改時間互斥。 */
  public static Object lock(Path root) {
    return LOCKS.computeIfAbsent(root.toAbsolutePath().normalize(), k -> new Object());
  }

  public record Stored(ObjectType type, byte[] data) {}

  public ObjectId put(ObjectType type, byte[] data) throws IOException {
    byte[] raw = new byte[data.length + 1];
    raw[0] = (byte) type.code;
    System.arraycopy(data, 0, raw, 1, data.length);
    var id = ObjectId.hash(raw);
    long now = System.currentTimeMillis();
    var last = known.get(id);
    if (last != null && now - last < TOUCH_MILLIS) return id;
    var path = path(id);
    if (Files.exists(path) && touch(path, now)) {
      known.put(id, now);
      return id;
    }
    Files.createDirectories(path.getParent());
    var tmp = Files.createTempFile(path.getParent(), ".tmp-", "");
    try {
      Files.write(tmp, Zstd.compress(raw, LEVEL));
      synchronized (lock) {
        if (!touch(path, now)) Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
      }
    } catch (FileAlreadyExistsException e) {
      // 另一個程序同時寫了同一個物件，內容必然相同。
    } finally {
      Files.deleteIfExists(tmp);
    }
    known.put(id, now);
    return id;
  }

  /** 更新已存在物件的修改時間；不存在（或剛被回收）時為 false。 */
  private boolean touch(Path path, long now) {
    synchronized (lock) {
      try {
        Files.setLastModifiedTime(path, FileTime.fromMillis(now));
        return true;
      } catch (IOException e) {
        return false;
      }
    }
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

  /** 物件存在嗎；存在時視為要拿來用，更新修改時間（免得在用到之前被回收）。 */
  public boolean contains(ObjectId id) {
    long now = System.currentTimeMillis();
    var last = known.get(id);
    if (last != null && now - last < TOUCH_MILLIS) return true;
    if (!touch(path(id), now)) return false;
    known.put(id, now);
    return true;
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
