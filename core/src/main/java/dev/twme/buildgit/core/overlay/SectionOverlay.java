package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.Upgrader;
import dev.twme.buildgit.core.store.Bin;
import java.io.IOException;
import java.util.*;

/** 一個 section 中與起點世界不同的格子：格子索引（0–4095）→ 內容。 */
public record SectionOverlay(SortedMap<Integer, Cell> cells) {
  private static final int VERSION = 2;
  public static final SectionOverlay EMPTY = new SectionOverlay(new TreeMap<>());

  public SectionOverlay {
    for (int i : cells.keySet()) if (i < 0 || i > 4095) throw new IllegalArgumentException("格子索引超出範圍：" + i);
    cells = Collections.unmodifiableSortedMap(new TreeMap<>(cells));
  }

  public Cell get(int index) {
    return cells.get(index);
  }

  public boolean isEmpty() {
    return cells.isEmpty();
  }

  /**
   * 調色盤依格子索引順序、第一次出現的順序編號，同樣內容一定得到同樣位元組。
   *
   * @param dataVersion 內容所屬的 Minecraft 資料版本
   */
  public byte[] encode(int dataVersion) {
    var palette = new LinkedHashMap<Cell, Integer>();
    for (var cell : cells.values()) palette.putIfAbsent(cell, palette.size());
    var out = new Bin.Out().u8(VERSION).i32(dataVersion).i32(palette.size());
    for (var cell : palette.keySet()) out.str(cell.state()).nullableBytes(cell.blockEntity());
    out.i32(cells.size());
    cells.forEach((index, cell) -> out.i32(index).i32(palette.get(cell)));
    return out.toByteArray();
  }

  /**
   * @param legacy 第 1 版編碼（沒有記錄資料版本）的資料版本
   * @param upgrader 把較舊的內容升級到目前版本
   */
  public static SectionOverlay decode(byte[] data, int legacy, Upgrader upgrader) throws IOException {
    var in = new Bin.In(data);
    int dataVersion = switch (in.u8()) {
      case 1 -> legacy;
      case VERSION -> in.i32();
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
    in.end();
    return new SectionOverlay(map);
  }
}
