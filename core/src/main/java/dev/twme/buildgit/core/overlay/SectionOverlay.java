package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.Upgrader;
import dev.twme.buildgit.core.store.Bin;
import java.io.IOException;
import java.util.*;

/**
 * 一個 section 中與起點世界不同的內容：格子索引（0–4095）→ 內容，以及生態域格（4×4×4，索引 0–63）→ 生態域 ID。
 *
 * <p>生態域格的索引與原版相同：{@code (y / 4) * 16 + (z / 4) * 4 + x / 4}（section 內的座標）。
 */
public record SectionOverlay(SortedMap<Integer, Cell> cells, SortedMap<Integer, String> biomes) {
  private static final int VERSION = 2;
  /** 有生態域時的格式；沒有生態域的 section 仍寫第 2 版，同樣的內容雜湊不變。 */
  private static final int WITH_BIOMES = 3;
  public static final SectionOverlay EMPTY = new SectionOverlay(new TreeMap<>());

  public SectionOverlay {
    for (int i : cells.keySet()) if (i < 0 || i > 4095) throw new IllegalArgumentException("格子索引超出範圍：" + i);
    for (int i : biomes.keySet()) if (i < 0 || i > 63) throw new IllegalArgumentException("生態域索引超出範圍：" + i);
    cells = Collections.unmodifiableSortedMap(new TreeMap<>(cells));
    biomes = Collections.unmodifiableSortedMap(new TreeMap<>(biomes));
  }

  public SectionOverlay(SortedMap<Integer, Cell> cells) {
    this(cells, new TreeMap<>());
  }

  public Cell get(int index) {
    return cells.get(index);
  }

  public String biome(int index) {
    return biomes.get(index);
  }

  public boolean isEmpty() {
    return cells.isEmpty() && biomes.isEmpty();
  }

  /** 格子所在的生態域格。 */
  public static int biomeIndex(int cell) {
    return (cell >> 10) << 4 | (cell >> 6 & 3) << 2 | (cell & 15) >> 2;
  }

  /** 生態域格最小角的格子索引。 */
  public static int biomeCorner(int biome) {
    return (biome >> 4) << 10 | (biome >> 2 & 3) << 6 | (biome & 3) << 2;
  }

  /**
   * 調色盤依格子索引順序、第一次出現的順序編號，同樣內容一定得到同樣位元組。
   *
   * @param dataVersion 內容所屬的 Minecraft 資料版本
   */
  public byte[] encode(int dataVersion) {
    var palette = new LinkedHashMap<Cell, Integer>();
    for (var cell : cells.values()) palette.putIfAbsent(cell, palette.size());
    var out = new Bin.Out().u8(biomes.isEmpty() ? VERSION : WITH_BIOMES).i32(dataVersion).i32(palette.size());
    for (var cell : palette.keySet()) out.str(cell.state()).nullableBytes(cell.blockEntity());
    out.i32(cells.size());
    cells.forEach((index, cell) -> out.i32(index).i32(palette.get(cell)));
    if (!biomes.isEmpty()) {
      out.i32(biomes.size());
      biomes.forEach((index, biome) -> out.u8(index).str(biome));
    }
    return out.toByteArray();
  }

  /**
   * @param legacy 第 1 版編碼（沒有記錄資料版本）的資料版本
   * @param upgrader 把較舊的內容升級到目前版本
   */
  public static SectionOverlay decode(byte[] data, int legacy, Upgrader upgrader) throws IOException {
    var in = new Bin.In(data);
    int version = in.u8();
    int dataVersion = switch (version) {
      case 1 -> legacy;
      case VERSION, WITH_BIOMES -> in.i32();
      default -> throw new IOException("不支援的 section 版本");
    };
    boolean upgrade = dataVersion < upgrader.current();
    int paletteSize = in.count();
    var palette = new ArrayList<Cell>(paletteSize);
    for (int i = 0; i < paletteSize; i++) {
      var cell = new Cell(in.str(), in.nullableBytes());
      palette.add(upgrade ? upgrader.cell(cell, dataVersion) : cell);
    }
    int n = in.count();
    var map = new TreeMap<Integer, Cell>();
    for (int i = 0; i < n; i++) {
      int index = in.i32(), p = in.i32();
      if (p < 0 || p >= palette.size()) throw new IOException("section 調色盤索引無效");
      map.put(index, palette.get(p));
    }
    var biomes = new TreeMap<Integer, String>();
    if (version == WITH_BIOMES) {
      int nb = in.count();
      for (int i = 0; i < nb; i++) {
        int index = in.u8();
        if (index > 63) throw new IOException("生態域索引無效");
        var biome = in.str();
        biomes.put(index, upgrade ? upgrader.biome(biome, dataVersion) : biome);
      }
    }
    in.end();
    return new SectionOverlay(map, biomes);
  }
}
