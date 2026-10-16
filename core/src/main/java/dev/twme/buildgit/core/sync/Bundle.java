package dev.twme.buildgit.core.sync;

import com.github.luben.zstd.Zstd;
import dev.twme.buildgit.core.model.*;
import dev.twme.buildgit.core.overlay.OverlayReader;
import dev.twme.buildgit.core.repo.StateSource;
import dev.twme.buildgit.core.track.Marks;
import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/**
 * 帶到單人世界的一塊範圍（設計 §6.7）：某個存檔在選取範圍內的全部格子與建築實體。
 * 伺服器以它送出範圍，單人世界改完後以同樣的格式整塊送回，由伺服器和當初的存檔比較。
 *
 * @param cells 範圍內每一格，依 {@link #index} 排列
 * @param base 取出時的存檔（顯示用；伺服器以自己的紀錄為準）
 */
public record Bundle(String name, String dimension, Box box, int dataVersion, String base, Cell[] cells, List<EntityState> entities) {
  private static final int MAGIC = 0x42475042; // "BGPB"
  private static final int VERSION = 1;
  /** 單人世界讀自己的檔案時，解壓後的上限。 */
  private static final long MAX_RAW = 512L * 1024 * 1024;

  public Bundle {
    Objects.requireNonNull(name);
    Objects.requireNonNull(dimension);
    if (cells.length != volume(box)) throw new IllegalArgumentException("格子數與範圍不符");
    entities = List.copyOf(entities);
  }

  /** 這個範圍的資料解壓後合理的最大大小：每格 4 位元組，加上調色盤與實體的餘裕。 */
  public static long maxRaw(Box box) {
    return volume(box) * 4 + 32L * 1024 * 1024;
  }

  public static long volume(Box box) {
    return (long) (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1);
  }

  public int index(int x, int y, int z) {
    int sx = box.maxX() - box.minX() + 1, sz = box.maxZ() - box.minZ() + 1;
    return ((y - box.minY()) * sz + (z - box.minZ())) * sx + (x - box.minX());
  }

  public Cell cell(int x, int y, int z) {
    return cells[index(x, y, z)];
  }

  /** 範圍內的實體，依 UUID。 */
  public Map<UUID, EntityState> entityMap() {
    var map = new HashMap<UUID, EntityState>();
    for (var e : entities) map.put(e.uuid(), e);
    return map;
  }

  // ---- 從存檔取出 ----

  /** 讀一個 section 裡想要的格子。 */
  public interface SectionReader {
    Cell[] read(SectionKey key, BitSet wanted) throws IOException;
  }

  /** 依序讀出範圍內的每一格；讀不到的當成空氣。 */
  public static Cell[] read(String dimension, Box box, SectionReader source) throws IOException {
    var cells = new Cell[Math.toIntExact(volume(box))];
    int sx = box.maxX() - box.minX() + 1, sz = box.maxZ() - box.minZ() + 1;
    for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++)
      for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++)
        for (int sy = box.minY() >> 4; sy <= box.maxY() >> 4; sy++) {
          var key = new SectionKey(dimension, cx, sy, cz);
          var wanted = new BitSet(4096);
          for (int i = 0; i < 4096; i++) if (box.contains(key.pos(i))) wanted.set(i);
          var result = source.read(key, wanted);
          for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
            var p = key.pos(i);
            cells[((p.y() - box.minY()) * sz + (p.z() - box.minZ())) * sx + (p.x() - box.minX())] = result[i] == null ? Cell.AIR : result[i];
          }
        }
    return cells;
  }

  /**
   * 讀出存檔在範圍內的內容：覆寫表優先，其餘是起點世界。
   *
   * @param genesisEntities 起點世界這個維度的所有實體
   * @param keep 要帶走的實體（建築實體），其餘略過
   */
  public static Bundle capture(String name, String dimension, Box box, int dataVersion, String base, OverlayReader reader,
      StateSource genesis, Collection<EntityState> genesisEntities, Predicate<EntityState> keep) throws IOException {
    var cells = read(dimension, box, (key, wanted) -> {
      var overlay = reader.section(key);
      var origin = genesis.readSection(key, wanted);
      var result = new Cell[4096];
      for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
        var c = overlay.get(i);
        result[i] = c != null ? c : origin[i];
      }
      return result;
    });
    var entities = new LinkedHashMap<UUID, EntityState>();
    for (var e : genesisEntities) entities.put(e.uuid(), e);
    for (var e : reader.entities(dimension).entrySet()) {
      if (e.getValue().isPresent()) entities.put(e.getKey(), e.getValue().get());
      else entities.remove(e.getKey());
    }
    entities.values().removeIf(e -> !keep.test(e) || !inside(box, e));
    return new Bundle(name, dimension, box, dataVersion, base, cells, new ArrayList<>(entities.values()));
  }

  /** 實體在範圍內；NBT 損壞或沒有位置時為 false。 */
  public static boolean inside(Box box, EntityState e) {
    try {
      var p = e.position();
      return p != null && box.contains(p[0], p[1], p[2]);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  // ---- 送回伺服器後存成版本 ----

  /** 範圍內每一格與兩邊出現過的實體都標記給 {@code author}；提交時只有真的和存檔不同的會留下。 */
  public Marks marks(Author author, Collection<UUID> baseEntities) {
    var sections = new TreeMap<SectionKey, Marks.Section>();
    for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++)
      for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++)
        for (int sy = box.minY() >> 4; sy <= box.maxY() >> 4; sy++) {
          var key = new SectionKey(dimension, cx, sy, cz);
          var cellsIn = new BitSet(4096);
          for (int i = 0; i < 4096; i++) if (box.contains(key.pos(i))) cellsIn.set(i);
          sections.put(key, new Marks.Section(cellsIn, null, (short) 0));
        }
    var uuids = new TreeMap<UUID, Integer>();
    for (var u : baseEntities) uuids.put(u, 0);
    for (var e : entities) uuids.put(e.uuid(), 0);
    var entityMarks = new TreeMap<String, SortedMap<UUID, Integer>>();
    if (!uuids.isEmpty()) entityMarks.put(dimension, uuids);
    return new Marks(List.of(author), sections, entityMarks);
  }

  /** 以這塊範圍的內容當成「工作世界」。只回答範圍內的格子與這個維度的實體。 */
  public StateSource source() {
    var map = entityMap();
    return new StateSource() {
      @Override
      public Cell[] readSection(SectionKey key, BitSet wanted) {
        var result = new Cell[4096];
        if (!key.dimension().equals(dimension)) return result;
        for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
          var p = key.pos(i);
          if (box.contains(p)) result[i] = cell(p.x(), p.y(), p.z());
        }
        return result;
      }

      @Override
      public Optional<EntityState> readEntity(String dim, UUID uuid) {
        return dim.equals(dimension) ? Optional.ofNullable(map.get(uuid)) : Optional.empty();
      }
    };
  }

  // ---- 編碼 ----

  public byte[] encode() {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(VERSION);
      out.writeUTF(name);
      out.writeUTF(dimension);
      for (int v : new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()}) out.writeInt(v);
      out.writeInt(dataVersion);
      out.writeUTF(base);
      var palette = new LinkedHashMap<Cell, Integer>();
      for (var c : cells) palette.putIfAbsent(c, palette.size());
      out.writeInt(palette.size());
      for (var c : palette.keySet()) {
        out.writeUTF(c.state());
        var nbt = c.blockEntity();
        out.writeInt(nbt == null ? -1 : nbt.length);
        if (nbt != null) out.write(nbt);
      }
      for (var c : cells) out.writeInt(palette.get(c));
      out.writeInt(entities.size());
      for (var e : entities) {
        out.writeLong(e.uuid().getMostSignificantBits());
        out.writeLong(e.uuid().getLeastSignificantBits());
        var nbt = e.nbt();
        out.writeInt(nbt.length);
        out.write(nbt);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Zstd.compress(bytes.toByteArray(), 6);
  }

  public static Bundle decode(byte[] compressed, Upgrader upgrader) throws IOException {
    return decode(compressed, upgrader, MAX_RAW);
  }

  /**
   * 解壓前先檢查宣告的大小，所以惡意的資料最多只會讓伺服器配置 {@code maxRaw}；
   * 格子數也受解壓後的大小限制（每格至少 4 位元組）。
   *
   * @param upgrader 較舊版本寫的內容升級到目前版本；較新版本寫的拒絕
   */
  public static Bundle decode(byte[] compressed, Upgrader upgrader, long maxRaw) throws IOException {
    long size = Zstd.getFrameContentSize(compressed);
    if (size <= 0 || size > maxRaw || size > Integer.MAX_VALUE - 16) throw new IOException("資料損壞或太大");
    byte[] raw;
    try {
      raw = Zstd.decompress(compressed, (int) size);
    } catch (RuntimeException e) {
      throw new IOException("資料損壞", e);
    }
    try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
      if (in.readInt() != MAGIC) throw new IOException("不是 BuildGit 的範圍資料");
      if (in.readInt() != VERSION) throw new IOException("範圍資料的格式版本不支援");
      String name = in.readUTF(), dimension = in.readUTF();
      var box = new Box(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
      int dataVersion = in.readInt();
      if (dataVersion > upgrader.current() && upgrader != Upgrader.NONE)
        throw new IOException("範圍資料來自較新的 Minecraft（資料版本 " + dataVersion + "），這裡無法讀取");
      String base = in.readUTF();
      long volume = volume(box);
      if (volume * 4 > raw.length) throw new IOException("資料損壞");
      int paletteSize = in.readInt();
      if (paletteSize < 0 || paletteSize > volume) throw new IOException("資料損壞");
      var palette = new Cell[paletteSize];
      for (int i = 0; i < paletteSize; i++) {
        String state = in.readUTF();
        int len = in.readInt();
        byte[] nbt = len < 0 ? null : in.readNBytes(len);
        if (nbt != null && nbt.length != len) throw new EOFException();
        var cell = new Cell(state, nbt);
        palette[i] = dataVersion < upgrader.current() ? upgrader.cell(cell, dataVersion) : cell;
      }
      var cells = new Cell[(int) volume];
      for (int i = 0; i < cells.length; i++) {
        int p = in.readInt();
        if (p < 0 || p >= paletteSize) throw new IOException("資料損壞");
        cells[i] = palette[p];
      }
      int n = in.readInt();
      if (n < 0 || n > raw.length) throw new IOException("資料損壞");
      var entities = new ArrayList<EntityState>(n);
      for (int i = 0; i < n; i++) {
        var uuid = new UUID(in.readLong(), in.readLong());
        int len = in.readInt();
        byte[] nbt = in.readNBytes(len);
        if (len < 0 || nbt.length != len) throw new IOException("資料損壞");
        if (dataVersion < upgrader.current()) nbt = upgrader.entity(nbt, dataVersion);
        entities.add(new EntityState(uuid, nbt));
      }
      return new Bundle(name, dimension, box, Math.max(dataVersion, upgrader.current()), base, cells, entities);
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Bundle b && name.equals(b.name) && dimension.equals(b.dimension) && box.equals(b.box)
        && dataVersion == b.dataVersion && base.equals(b.base) && Arrays.equals(cells, b.cells) && entities.equals(b.entities);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, dimension, box, base) * 31 + Arrays.hashCode(cells);
  }
}
