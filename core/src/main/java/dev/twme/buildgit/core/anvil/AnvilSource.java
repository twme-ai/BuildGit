package dev.twme.buildgit.core.anvil;

import dev.twme.buildgit.core.model.*;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.nbt.Normalizer;
import dev.twme.buildgit.core.repo.StateSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/**
 * 從存檔讀取世界內容：起點世界（init 時凍結的 region 複本），或伺服器沒載入的 chunk。
 * 每個維度一個目錄，內含 {@code region/} 與 {@code entities/}。執行緒安全。
 */
public final class AnvilSource implements StateSource, Closeable {
  private static final int CACHED_CHUNKS = 256;
  private static final int OPEN_REGIONS = 32;

  private final Function<String, Path> dimensions;
  private final Upgrader upgrader;
  /** 開著的 region 檔；超過上限時關掉最久沒用的，避免大世界占滿檔案代碼。 */
  private final Map<Path, RegionFile> regions =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, RegionFile> eldest) {
          if (size() <= OPEN_REGIONS) return false;
          try {
            eldest.getValue().close();
          } catch (IOException ignored) {
            // 唯讀檔案
          }
          return true;
        }
      };
  private final Map<String, ChunkData> chunks =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ChunkData> eldest) {
          return size() > CACHED_CHUNKS;
        }
      };
  private final Map<String, Map<UUID, EntityState>> entityIndex = new HashMap<>();

  /** @param dimensions 維度 id 對應的目錄；沒有這個維度時回傳 null */
  public AnvilSource(Function<String, Path> dimensions) {
    this(dimensions, Upgrader.NONE);
  }

  /** @param upgrader 讀到的 chunk 先升級到目前的格式（起點世界可能是較舊的版本存的） */
  public AnvilSource(Function<String, Path> dimensions, Upgrader upgrader) {
    this.dimensions = dimensions;
    this.upgrader = upgrader;
  }

  /** 解碼後的 chunk：每個 section 的調色盤與索引、生態域，以及方塊實體。 */
  private record ChunkData(Map<Integer, Section> sections, Map<Integer, String[]> biomes, Map<Integer, byte[]> blockEntities) {}

  /** 方塊索引保持存檔裡的打包格式（每格 4 位元起），快取的 chunk 只占展開成 int[] 的幾分之一。 */
  private record Section(String[] palette, long[] data, int bits) {
    String state(int cell) throws IOException {
      if (data == null) return palette[0];
      int perLong = 64 / bits;
      int v = (int) (data[cell / perLong] >>> (cell % perLong) * bits & (1L << bits) - 1);
      if (v >= palette.length) throw new IOException("section 調色盤索引超出範圍");
      return palette[v];
    }
  }

  @Override
  public synchronized Cell[] readSection(SectionKey key, BitSet wanted) throws IOException {
    var cells = new Cell[4096];
    var chunk = chunk(key.dimension(), key.chunkX(), key.chunkZ());
    var section = chunk == null ? null : chunk.sections.get(key.sectionY());
    for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
      if (section == null) {
        cells[i] = Cell.AIR;
        continue;
      }
      var be = chunk.blockEntities.get(blockEntityKey(key.sectionY(), i));
      cells[i] = new Cell(section.state(i), be);
    }
    return cells;
  }

  @Override
  public synchronized String[] readBiomes(SectionKey key) throws IOException {
    var chunk = chunk(key.dimension(), key.chunkX(), key.chunkZ());
    var biomes = chunk == null ? null : chunk.biomes.get(key.sectionY());
    return biomes == null ? null : biomes.clone();
  }

  private static int blockEntityKey(int sectionY, int cell) {
    return sectionY << 12 | cell;
  }

  private RegionFile region(Path dir, int chunkX, int chunkZ) throws IOException {
    Path file = dir.resolve(RegionFile.fileName(chunkX, chunkZ));
    var r = regions.get(file);
    if (r == null) {
      if (!Files.isRegularFile(file)) return null;
      r = new RegionFile(file);
      regions.put(file, r);
    }
    return r;
  }

  private ChunkData chunk(String dimension, int chunkX, int chunkZ) throws IOException {
    String id = dimension + "/" + chunkX + "/" + chunkZ;
    var cached = chunks.get(id);
    if (cached != null) return cached;
    Path dir = dimensions.apply(dimension);
    if (dir == null) return null;
    var region = region(dir.resolve("region"), chunkX, chunkZ);
    var nbt = region == null ? null : region.read(RegionFile.index(chunkX, chunkZ));
    var data = nbt == null ? new ChunkData(Map.of(), Map.of(), Map.of()) : decode(upgrader.chunk(nbt), upgrader);
    chunks.put(id, data);
    return data;
  }

  static ChunkData decode(Nbt.Compound chunk, Upgrader upgrader) throws IOException {
    var sections = new HashMap<Integer, Section>();
    var biomes = new HashMap<Integer, String[]>();
    for (var o : chunk.list("sections").values()) {
      if (!(o instanceof Nbt.Compound s) || !(s.get("Y") instanceof Byte y)) continue;
      var b = biomes(s);
      if (b != null) biomes.put((int) y, b);
      var states = s.compound("block_states");
      var palette = states.list("palette").values();
      if (palette.isEmpty()) continue;
      var names = new String[palette.size()];
      for (int i = 0; i < names.length; i++) names[i] = upgrader.paletteState(Nbt.unwrap(palette.get(i)));
      long[] packed = null;
      int bits = 0;
      if (names.length > 1) {
        if (!(states.get("data") instanceof long[] data)) throw new IOException("section 缺少方塊資料");
        bits = bits(names.length);
        if (data.length < (4096 + 64 / bits - 1) / (64 / bits)) throw new IOException("section 方塊資料長度不足");
        packed = data;
      }
      sections.put((int) y, new Section(names, packed, bits));
    }
    var blockEntities = new HashMap<Integer, byte[]>();
    for (var o : chunk.list("block_entities").values()) {
      if (!(o instanceof Nbt.Compound be)) continue;
      if (!(be.get("x") instanceof Integer x) || !(be.get("y") instanceof Integer y) || !(be.get("z") instanceof Integer z))
        continue;
      var pos = new BlockPos(x, y, z);
      blockEntities.put(blockEntityKey(pos.sectionY(), pos.sectionIndex()), Nbt.write(Normalizer.blockEntity(be)));
    }
    return new ChunkData(sections, biomes, blockEntities);
  }

  /** section 的 64 個生態域格；沒有生態域資料時為 null。 */
  static String[] biomes(Nbt.Compound section) throws IOException {
    var container = section.compound("biomes");
    var palette = container.list("palette").values();
    if (palette.isEmpty()) return null;
    var names = new String[palette.size()];
    for (int i = 0; i < names.length; i++) names[i] = String.valueOf(Nbt.unwrap(palette.get(i)));
    var result = new String[64];
    if (names.length == 1) {
      Arrays.fill(result, names[0]);
      return result;
    }
    if (!(container.get("data") instanceof long[] data)) throw new IOException("section 缺少生態域資料");
    int bits = biomeBits(names.length), perLong = 64 / bits;
    if (data.length < (64 + perLong - 1) / perLong) throw new IOException("section 生態域資料長度不足");
    for (int i = 0; i < 64; i++) {
      int v = (int) (data[i / perLong] >>> (i % perLong) * bits & (1L << bits) - 1);
      if (v >= names.length) throw new IOException("生態域調色盤索引超出範圍");
      result[i] = names[v];
    }
    return result;
  }

  /** 生態域的打包格式：沒有最少 4 位元的限制。 */
  static int biomeBits(int paletteSize) {
    return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
  }

  /** 1.16 之後的打包格式：每個 long 放 64/bits 個值，不跨 long。 */
  static int bits(int paletteSize) {
    return Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
  }

  static int[] unpack(long[] data, int paletteSize) throws IOException {
    int bits = bits(paletteSize);
    int perLong = 64 / bits;
    if (data.length < (4096 + perLong - 1) / perLong) throw new IOException("section 方塊資料長度不足");
    long mask = (1L << bits) - 1;
    var result = new int[4096];
    for (int i = 0; i < 4096; i++) {
      int v = (int) (data[i / perLong] >>> (i % perLong) * bits & mask);
      if (v >= paletteSize) throw new IOException("section 調色盤索引超出範圍");
      result[i] = v;
    }
    return result;
  }

  @Override
  public synchronized Optional<EntityState> readEntity(String dimension, UUID uuid) throws IOException {
    return Optional.ofNullable(entityMap(dimension).get(uuid));
  }

  /** 起點世界這個維度的所有實體。 */
  public synchronized List<EntityState> entities(String dimension) throws IOException {
    return List.copyOf(entityMap(dimension).values());
  }

  private Map<UUID, EntityState> entityMap(String dimension) throws IOException {
    var index = entityIndex.get(dimension);
    if (index == null) {
      index = indexEntities(dimension);
      entityIndex.put(dimension, index);
    }
    return index;
  }

  /** 起點世界是凍結的，所以第一次查詢時整個維度讀一次。 */
  private Map<UUID, EntityState> indexEntities(String dimension) throws IOException {
    var result = new HashMap<UUID, EntityState>();
    Path dir = dimensions.apply(dimension);
    if (dir == null || !Files.isDirectory(dir.resolve("entities"))) return result;
    List<Path> files;
    try (var s = Files.list(dir.resolve("entities"))) {
      files = s.filter(p -> RegionFile.NAME.matcher(p.getFileName().toString()).matches()).sorted().toList();
    }
    for (var file : files)
      try (var region = new RegionFile(file)) {
        for (int i = 0; i < 1024; i++) {
          var chunk = region.read(i);
          if (chunk != null) chunk = upgrader.entityChunk(chunk);
          if (chunk == null) continue;
          for (var o : chunk.list("Entities").values()) {
            if (!(o instanceof Nbt.Compound e) || !(e.get("UUID") instanceof int[] u) || u.length != 4) continue;
            var uuid = new UUID((long) u[0] << 32 | u[1] & 0xFFFFFFFFL, (long) u[2] << 32 | u[3] & 0xFFFFFFFFL);
            result.put(uuid, new EntityState(uuid, Nbt.write(Normalizer.entity(e))));
          }
        }
      }
    return result;
  }

  /** 世界存檔後讀到的內容可能已過時；讀取未載入 chunk 前呼叫。 */
  public synchronized void invalidate() {
    chunks.clear();
    entityIndex.clear();
    closeRegions();
  }

  /** 這個 chunk 剛被寫進目錄（例如補進起點世界的新生成 chunk）：丟掉快取的內容與 region 標頭。 */
  public synchronized void invalidate(String dimension, int chunkX, int chunkZ) {
    chunks.remove(dimension + "/" + chunkX + "/" + chunkZ);
    Path dir = dimensions.apply(dimension);
    if (dir == null) return;
    var r = regions.remove(dir.resolve("region").resolve(RegionFile.fileName(chunkX, chunkZ)));
    if (r != null)
      try {
        r.close();
      } catch (IOException ignored) {
        // 唯讀檔案
      }
  }

  private void closeRegions() {
    for (var r : regions.values())
      try {
        r.close();
      } catch (IOException ignored) {
        // 唯讀檔案，關閉失敗不影響內容
      }
    regions.clear();
  }

  @Override
  public synchronized void close() {
    closeRegions();
  }
}
