package dev.twme.buildgit.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.twme.buildgit.core.anvil.AnvilSource;
import dev.twme.buildgit.core.anvil.RegionFile;
import dev.twme.buildgit.core.anvil.RegionWriter;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.nbt.Nbt;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnvilSourceTest {
  static final String OVERWORLD = "minecraft:overworld";

  @TempDir Path dir;

  /** 依真實格式寫一個只有一個 chunk 的 region 檔。 */
  static void writeRegion(Path file, int chunkX, int chunkZ, Nbt.Compound chunk) throws Exception {
    var compressed = new ByteArrayOutputStream();
    try (var out = new DeflaterOutputStream(compressed)) {
      out.write(Nbt.write(chunk));
    }
    byte[] body = compressed.toByteArray();
    int sectors = (body.length + 5 + 4095) / 4096;
    var buffer = ByteBuffer.allocate((2 + sectors) * 4096);
    buffer.putInt(RegionFile.index(chunkX, chunkZ) * 4, 2 << 8 | sectors);
    buffer.position(8192);
    buffer.putInt(body.length + 1).put((byte) 2).put(body);
    Files.createDirectories(file.getParent());
    Files.write(file, buffer.array());
  }

  static Nbt.Compound state(String name, String... properties) {
    var c = new Nbt.Compound().with("Name", name);
    if (properties.length > 0) {
      var p = new Nbt.Compound();
      for (int i = 0; i < properties.length; i += 2) p.with(properties[i], properties[i + 1]);
      c.with("Properties", p);
    }
    return c;
  }

  @Test
  void upgradeRewritesOnlyOlderChunks() throws Exception {
    var file = dir.resolve("region/r.0.0.mca");
    try (var w = new RegionWriter(file)) {
      w.write(0, Nbt.write(new Nbt.Compound().with("DataVersion", 4000).with("v", "old")));
      w.write(1, Nbt.write(new Nbt.Compound().with("DataVersion", 5000).with("v", "current")));
      w.write(2, Nbt.write(new Nbt.Compound().with("DataVersion", 1343).with("v", "1.12")));
    }
    int n = dev.twme.buildgit.core.anvil.RegionUpgrade.rewrite(file, 5000, c -> c.with("DataVersion", 5000).with("v", "new"), () -> {});
    assertEquals(1, n);
    try (var r = new RegionFile(file)) {
      assertEquals("new", r.read(0).string("v"));
      assertEquals("current", r.read(1).string("v"));
      assertEquals("1.12", r.read(2).string("v"));
    }
    assertFalse(Files.exists(dir.resolve("region/.buildgit-upgrade")));
    assertEquals(0, dev.twme.buildgit.core.anvil.RegionUpgrade.rewrite(file, 5000, c -> c, () -> fail("沒有要升級的不換檔")));
  }

  @Test
  void readsPalettedSectionsBlockEntitiesAndEntities() throws Exception {
    // 調色盤 17 項 → 每格 5 bits，每個 long 12 格，最後一個 long 未滿。
    var palette = new java.util.ArrayList<Object>();
    palette.add(state("minecraft:air"));
    palette.add(state("minecraft:oak_stairs", "half", "bottom", "facing", "north", "waterlogged", "false"));
    palette.add(state("minecraft:chest", "type", "single", "facing", "east", "waterlogged", "false"));
    for (int i = 3; i < 17; i++) palette.add(state("minecraft:stone_" + i));
    int bits = 5, perLong = 64 / bits;
    var data = new long[(4096 + perLong - 1) / perLong];
    var stairs = new BlockPos(-31, 70, 5); // chunk -2,0 section 4
    var chest = new BlockPos(-32, 79, 15);
    var last = new BlockPos(-17, 79, 15); // 索引 4095
    int[] values = new int[4096];
    values[stairs.sectionIndex()] = 1;
    values[chest.sectionIndex()] = 2;
    values[last.sectionIndex()] = 16;
    for (int i = 0; i < 4096; i++) data[i / perLong] |= (long) values[i] << (i % perLong) * bits;
    var section =
        new Nbt.Compound()
            .with("Y", (byte) 4)
            .with("block_states", new Nbt.Compound().with("palette", new Nbt.ListTag(10, palette)).with("data", data));
    var single =
        new Nbt.Compound()
            .with("Y", (byte) -4)
            .with("block_states", new Nbt.Compound().with("palette", new Nbt.ListTag(10, List.of(state("minecraft:deepslate")))));
    var chestEntity =
        new Nbt.Compound()
            .with("id", "minecraft:chest")
            .with("x", chest.x())
            .with("y", chest.y())
            .with("z", chest.z())
            .with("CustomName", "\"Loot\"");
    var chunk =
        new Nbt.Compound()
            .with("sections", new Nbt.ListTag(10, List.of(section, single)))
            .with("block_entities", new Nbt.ListTag(10, List.of(chestEntity)));
    writeRegion(dir.resolve("overworld/region/r.-1.0.mca"), -2, 0, chunk);

    var uuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    var u = new int[] {
      (int) (uuid.getMostSignificantBits() >> 32), (int) uuid.getMostSignificantBits(),
      (int) (uuid.getLeastSignificantBits() >> 32), (int) uuid.getLeastSignificantBits()
    };
    var stand =
        new Nbt.Compound()
            .with("id", "minecraft:armor_stand")
            .with("UUID", u)
            .with("Pos", new Nbt.ListTag(6, List.of(1.5, 64.0, 2.5)))
            .with("Motion", new Nbt.ListTag(6, List.of(0.0, 0.0, 0.0)));
    writeRegion(
        dir.resolve("overworld/entities/r.0.0.mca"), 0, 0, new Nbt.Compound().with("Entities", new Nbt.ListTag(10, List.of(stand))));

    try (var source = new AnvilSource(d -> d.equals(OVERWORLD) ? dir.resolve("overworld") : null)) {
      var all = new BitSet();
      all.set(0, 4096);
      var cells = source.readSection(new SectionKey(OVERWORLD, -2, 4, 0), all);
      assertEquals(Cell.of("minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]"), cells[stairs.sectionIndex()]);
      assertEquals("minecraft:chest[facing=east,type=single,waterlogged=false]", cells[chest.sectionIndex()].state());
      var be = Nbt.read(cells[chest.sectionIndex()].blockEntity());
      assertEquals("\"Loot\"", be.string("CustomName"));
      assertFalse(be.containsKey("x"));
      assertEquals(Cell.of("minecraft:stone_16"), cells[4095]);
      assertEquals(Cell.AIR, cells[0]);
      assertEquals(Cell.of("minecraft:deepslate"), source.readSection(new SectionKey(OVERWORLD, -2, -4, 0), all)[123]);
      assertEquals(Cell.AIR, source.readSection(new SectionKey(OVERWORLD, -2, 9, 0), all)[0]); // 沒有這個 section
      assertEquals(Cell.AIR, source.readSection(new SectionKey(OVERWORLD, 50, 0, 50), all)[0]); // 沒有 region
      assertEquals(Cell.AIR, source.readSection(new SectionKey("minecraft:the_end", 0, 0, 0), all)[0]);

      var entity = source.readEntity(OVERWORLD, uuid).orElseThrow();
      assertArrayEquals(new double[] {1.5, 64.0, 2.5}, entity.position());
      assertFalse(Nbt.read(entity.nbt()).containsKey("Motion"));
      assertTrue(source.readEntity(OVERWORLD, UUID.randomUUID()).isEmpty());
    }
  }

  static Nbt.Compound uniformChunk(String block, int sectionY) {
    var section =
        new Nbt.Compound()
            .with("Y", (byte) sectionY)
            .with("block_states", new Nbt.Compound().with("palette", new Nbt.ListTag(10, List.of(state(block)))));
    return new Nbt.Compound().with("sections", new Nbt.ListTag(10, List.of(section)));
  }

  @Test
  void writerAppendsReplacesAndSpillsLargeChunks() throws Exception {
    var file = dir.resolve("overworld/region/r.0.0.mca");
    var all = new BitSet();
    all.set(0, 4096);
    try (var source = new AnvilSource(d -> dir.resolve("overworld"))) {
      // 讀過「沒有這個 chunk」之後再寫入：快取必須失效。
      assertEquals(Cell.AIR, source.readSection(new SectionKey(OVERWORLD, 3, 0, 4), all)[0]);
      try (var w = new RegionWriter(file)) {
        assertFalse(w.has(RegionFile.index(3, 4)));
        w.write(RegionFile.index(3, 4), Nbt.write(uniformChunk("minecraft:stone", 0)));
        w.write(RegionFile.index(5, 6), Nbt.write(uniformChunk("minecraft:dirt", 1)));
        w.write(RegionFile.index(3, 4), Nbt.write(uniformChunk("minecraft:glass", 0))); // 取代
        // 無法壓縮的大型資料 → 外部 .mcc
        var noise = new byte[1_200_000];
        new java.util.Random(1).nextBytes(noise);
        var big = uniformChunk("minecraft:sand", 2).with("noise", noise);
        w.write(RegionFile.index(7, 8), Nbt.write(big));
        assertTrue(w.has(RegionFile.index(5, 6)));
      }
      source.invalidate(OVERWORLD, 3, 4);
      assertEquals(Cell.of("minecraft:glass"), source.readSection(new SectionKey(OVERWORLD, 3, 0, 4), all)[100]);
      assertEquals(Cell.of("minecraft:dirt"), source.readSection(new SectionKey(OVERWORLD, 5, 1, 6), all)[4095]);
      assertEquals(Cell.of("minecraft:sand"), source.readSection(new SectionKey(OVERWORLD, 7, 2, 8), all)[7]);
    }
    assertTrue(Files.exists(dir.resolve("overworld/region/c.7.8.mcc")));
    // 重新開啟後標頭仍正確，可以繼續附加。
    try (var w = new RegionWriter(file)) {
      assertTrue(w.has(RegionFile.index(3, 4)));
      w.write(RegionFile.index(31, 31), Nbt.write(uniformChunk("minecraft:clay", 0)));
    }
    try (var r = new RegionFile(file)) {
      assertNotNull(r.read(RegionFile.index(31, 31)));
      assertNotNull(r.read(RegionFile.index(5, 6)));
    }
  }

  @Test
  void corruptRegionIsAnError() throws Exception {
    var file = dir.resolve("region/r.0.0.mca");
    Files.createDirectories(file.getParent());
    Files.write(file, new byte[100]);
    try (var source = new AnvilSource(d -> dir)) {
      var wanted = new BitSet();
      wanted.set(0);
      assertThrows(java.io.IOException.class, () -> source.readSection(new SectionKey(OVERWORLD, 0, 0, 0), wanted));
    }
  }
}
