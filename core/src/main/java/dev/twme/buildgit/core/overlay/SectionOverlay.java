package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.store.Bin;
import java.io.IOException;
import java.util.*;

/** 一個 section 中與起點世界不同的格子：格子索引（0–4095）→ 內容。 */
public record SectionOverlay(SortedMap<Integer, Cell> cells) {
  private static final int VERSION = 1;
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

  /** 調色盤依格子索引順序、第一次出現的順序編號，同樣內容一定得到同樣位元組。 */
  public byte[] encode() {
    var palette = new LinkedHashMap<Cell, Integer>();
    for (var cell : cells.values()) palette.putIfAbsent(cell, palette.size());
    var out = new Bin.Out().u8(VERSION).i32(palette.size());
    for (var cell : palette.keySet()) out.str(cell.state()).nullableBytes(cell.blockEntity());
    out.i32(cells.size());
    cells.forEach((index, cell) -> out.i32(index).i32(palette.get(cell)));
    return out.toByteArray();
  }

  public static SectionOverlay decode(byte[] data) throws IOException {
    var in = new Bin.In(data);
    if (in.u8() != VERSION) throw new IOException("不支援的 section 版本");
    int paletteSize = in.count();
    var palette = new ArrayList<Cell>(paletteSize);
    for (int i = 0; i < paletteSize; i++) palette.add(new Cell(in.str(), in.nullableBytes()));
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
