package dev.twme.buildgit.core.anvil;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.Upgrader;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.overlay.SectionOverlay;
import java.io.IOException;
import java.util.*;

/**
 * 把覆寫表套到存檔格式的 chunk NBT 上（設計 §4.2 合成讀取）。輸入不會被修改。
 *
 * <p>改過方塊的 chunk 拿掉高度圖與光照完成標記，伺服器載入時重新計算；其他欄位（結構、排程刻）原樣保留。
 * 生態域只改覆寫表裡有的生態域格。
 */
public final class ChunkPatcher {
  private ChunkPatcher() {}

  /** 方塊與方塊實體。 */
  /** @param upgrader 調色盤項目的格式依伺服器版本而定 */
  public static Nbt.Compound blocks(Nbt.Compound chunk, int chunkX, int chunkZ, SortedMap<Integer, SectionOverlay> overlay,
      Upgrader upgrader) throws IOException {
    var result = Nbt.copy(chunk);
    var sections = new TreeMap<Integer, Nbt.Compound>();
    for (var o : result.list("sections").values())
      if (o instanceof Nbt.Compound s && s.get("Y") instanceof Byte y) sections.put((int) y, s);

    var changed = new HashSet<Integer>(); // 被覆寫的格子的方塊實體鍵
    var placed = new ArrayList<Object>();
    boolean biomes = false;
    for (var entry : overlay.entrySet()) {
      int sy = entry.getKey();
      if (entry.getValue().isEmpty()) continue;
      var section = sections.get(sy);
      if (section == null) {
        section = new Nbt.Compound().with("Y", (byte) sy);
        var neighbour = nearest(sections, sy);
        if (neighbour != null && neighbour.get("biomes") instanceof Nbt.Compound b) section.with("biomes", Nbt.copy(b));
        sections.put(sy, section);
      }
      if (!entry.getValue().biomes().isEmpty()) {
        patchBiomes(section, entry.getValue().biomes());
        biomes = true;
      }
      if (entry.getValue().cells().isEmpty()) continue;
      patchSection(section, entry.getValue(), upgrader);
      section.remove("BlockLight");
      section.remove("SkyLight");
      for (var cell : entry.getValue().cells().entrySet()) {
        int index = cell.getKey();
        changed.add(sy << 12 | index);
        var be = cell.getValue().blockEntity();
        if (be == null) continue;
        var pos = position(chunkX, sy, chunkZ, index);
        placed.add(Nbt.read(be).with("x", pos.x()).with("y", pos.y()).with("z", pos.z()));
      }
    }
    if (!changed.isEmpty() || biomes) result.with("sections", new Nbt.ListTag(10, new ArrayList<>(sections.values())));
    if (changed.isEmpty()) return result;

    var blockEntities = new ArrayList<Object>();
    for (var o : result.list("block_entities").values()) {
      if (o instanceof Nbt.Compound be
          && be.get("x") instanceof Integer x
          && be.get("y") instanceof Integer y
          && be.get("z") instanceof Integer z) {
        var pos = new BlockPos(x, y, z);
        if (changed.contains(pos.sectionY() << 12 | pos.sectionIndex())) continue;
      }
      blockEntities.add(o);
    }
    blockEntities.addAll(placed);
    result.with("block_entities", new Nbt.ListTag(blockEntities.isEmpty() ? 0 : 10, blockEntities));
    // 讓伺服器重算：Paper 只在 isLightOn 存在且光照版本相符時沿用存檔的光照。
    result.remove("isLightOn");
    result.remove("Heightmaps");
    return result;
  }

  private static Nbt.Compound nearest(SortedMap<Integer, Nbt.Compound> sections, int y) {
    var below = sections.headMap(y);
    if (!below.isEmpty()) return below.get(below.lastKey());
    var above = sections.tailMap(y);
    return above.isEmpty() ? null : above.get(above.firstKey());
  }

  static BlockPos position(int chunkX, int sectionY, int chunkZ, int index) {
    return new BlockPos(chunkX << 4 | index & 15, sectionY << 4 | index >> 8, chunkZ << 4 | index >> 4 & 15);
  }

  private static void patchSection(Nbt.Compound section, SectionOverlay overlay, Upgrader upgrader) throws IOException {
    var states = section.compound("block_states");
    var palette = new ArrayList<String>();
    for (var o : states.list("palette").values()) palette.add(upgrader.paletteState(Nbt.unwrap(o)));
    if (palette.isEmpty()) palette.add(Cell.AIR.state());
    int[] indices = palette.size() > 1 && states.get("data") instanceof long[] data
        ? AnvilSource.unpack(data, palette.size())
        : new int[4096];

    var ids = new HashMap<String, Integer>();
    for (int i = 0; i < palette.size(); i++) ids.putIfAbsent(palette.get(i), i);
    for (var e : overlay.cells().entrySet()) {
      String state = e.getValue().state();
      Integer id = ids.get(state);
      if (id == null) {
        id = palette.size();
        palette.add(state);
        ids.put(state, id);
      }
      indices[e.getKey()] = id;
    }

    // 只留下還在使用的調色盤項目，依第一次出現的順序重新編號。
    var remap = new int[palette.size()];
    Arrays.fill(remap, -1);
    var used = new ArrayList<String>();
    for (int i = 0; i < 4096; i++) {
      int old = indices[i];
      if (remap[old] < 0) {
        remap[old] = used.size();
        used.add(palette.get(old));
      }
      indices[i] = remap[old];
    }
    var entries = new ArrayList<Object>(used.size());
    for (var state : used) entries.add(upgrader.paletteEntry(state));
    var out = new Nbt.Compound().with("palette", Nbt.list(entries));
    if (used.size() > 1) out.with("data", pack(indices, used.size()));
    section.with("block_states", out);
  }

  private static void patchBiomes(Nbt.Compound section, SortedMap<Integer, String> overlay) throws IOException {
    var biomes = AnvilSource.biomes(section);
    if (biomes == null) {
      biomes = new String[64];
      Arrays.fill(biomes, overlay.get(overlay.firstKey()));
    }
    for (var e : overlay.entrySet()) biomes[e.getKey()] = e.getValue();
    var palette = new ArrayList<String>();
    var ids = new HashMap<String, Integer>();
    var values = new int[64];
    for (int i = 0; i < 64; i++) values[i] = ids.computeIfAbsent(biomes[i], b -> { palette.add(b); return palette.size() - 1; });
    var out = new Nbt.Compound().with("palette", new Nbt.ListTag(8, new ArrayList<Object>(palette)));
    if (palette.size() > 1) {
      int bits = AnvilSource.biomeBits(palette.size()), perLong = 64 / bits;
      var data = new long[(64 + perLong - 1) / perLong];
      for (int i = 0; i < 64; i++) data[i / perLong] |= (long) values[i] << (i % perLong) * bits;
      out.with("data", data);
    }
    section.with("biomes", out);
  }

  /** {@link AnvilSource#unpack} 的反向。 */
  static long[] pack(int[] values, int paletteSize) {
    int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    int perLong = 64 / bits;
    var data = new long[(4096 + perLong - 1) / perLong];
    for (int i = 0; i < 4096; i++) data[i / perLong] |= (long) values[i] << (i % perLong) * bits;
    return data;
  }

  /**
   * 實體 chunk（{@code entities/*.mca} 的格式）。
   *
   * @param genesis 起點世界的實體 chunk，沒有則為 null
   * @param overridden 覆寫表中出現的所有 UUID：起點世界的這些實體由覆寫表決定在哪裡、是否存在
   * @param placed 覆寫表中位置落在這個 chunk 的實體
   * @return 沒有任何實體且起點也沒有時為 null
   */
  public static Nbt.Compound entities(
      Nbt.Compound genesis, int chunkX, int chunkZ, int dataVersion, Set<UUID> overridden, List<EntityState> placed)
      throws IOException {
    var list = new ArrayList<Object>();
    if (genesis != null)
      for (var o : genesis.list("Entities").values()) {
        if (o instanceof Nbt.Compound e && e.get("UUID") instanceof int[] u && u.length == 4 && overridden.contains(uuid(u)))
          continue;
        list.add(o);
      }
    for (var e : placed) {
      var u = e.uuid();
      list.add(Nbt.read(e.nbt()).with("UUID", new int[] {
        (int) (u.getMostSignificantBits() >> 32), (int) u.getMostSignificantBits(),
        (int) (u.getLeastSignificantBits() >> 32), (int) u.getLeastSignificantBits()
      }));
    }
    if (genesis == null && list.isEmpty()) return null;
    var result = genesis == null ? new Nbt.Compound().with("DataVersion", dataVersion) : Nbt.copy(genesis);
    result.with("Position", new int[] {chunkX, chunkZ});
    result.with("Entities", new Nbt.ListTag(list.isEmpty() ? 0 : 10, list));
    return result;
  }

  static UUID uuid(int[] u) {
    return new UUID((long) u[0] << 32 | u[1] & 0xFFFFFFFFL, (long) u[2] << 32 | u[3] & 0xFFFFFFFFL);
  }
}
