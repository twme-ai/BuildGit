package dev.twme.buildgit.core;

import static dev.twme.buildgit.core.AnvilSourceTest.uniformChunk;
import static dev.twme.buildgit.core.AnvilSourceTest.writeRegion;
import static dev.twme.buildgit.core.FakeWorld.OVERWORLD;
import static org.junit.jupiter.api.Assertions.*;

import dev.twme.buildgit.core.anvil.AnvilSource;
import dev.twme.buildgit.core.anvil.RegionFile;
import dev.twme.buildgit.core.anvil.RegionSynthesizer;
import dev.twme.buildgit.core.anvil.RegionWriter;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.track.MarkTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynthesizerTest {
  @TempDir Path dir;

  static int[] uuidInts(UUID u) {
    return new int[] {
      (int) (u.getMostSignificantBits() >> 32), (int) u.getMostSignificantBits(),
      (int) (u.getLeastSignificantBits() >> 32), (int) u.getLeastSignificantBits()
    };
  }

  static Nbt.Compound entity(UUID uuid, double x, double y, double z) {
    return new Nbt.Compound()
        .with("id", "minecraft:armor_stand")
        .with("UUID", uuidInts(uuid))
        .with("Pos", new Nbt.ListTag(6, List.of(x, y, z)));
  }

  @Test
  void regionIsGenesisPlusOverlay() throws Exception {
    Path genesisDim = dir.resolve("genesis/overworld");
    // chunk 0,0：section 4 全是石頭，(3,70,3) 是箱子；chunk 1,0 沒有覆寫。
    var chunk00 = uniformChunk("minecraft:stone", 4);
    chunk00.with("block_entities", new Nbt.ListTag(10, List.of(
        new Nbt.Compound().with("id", "minecraft:chest").with("x", 3).with("y", 70).with("z", 3))));
    chunk00.with("isLightOn", (byte) 1).with("Heightmaps", new Nbt.Compound());
    try (var w = new RegionWriter(genesisDim.resolve("region/r.0.0.mca"))) {
      w.write(RegionFile.index(0, 0), Nbt.write(chunk00));
      w.write(RegionFile.index(1, 0), Nbt.write(uniformChunk("minecraft:dirt", 4)));
    }
    UUID removed = UUID.randomUUID(), kept = UUID.randomUUID(), added = UUID.randomUUID();
    writeRegion(genesisDim.resolve("entities/r.0.0.mca"), 0, 0, new Nbt.Compound()
        .with("DataVersion", 4000)
        .with("Position", new int[] {0, 0})
        .with("Entities", new Nbt.ListTag(10, List.of(entity(removed, 1.5, 70, 1.5), entity(kept, 2.5, 70, 2.5)))));

    var genesis = new AnvilSource(d -> genesisDim);
    var repo = Repository.init(dir.resolve("repo"), Author.SYSTEM, 1);
    var alice = new Author(UUID.randomUUID(), "alice");
    var world = new FakeWorld()
        .set(0, 64, 0, "minecraft:glass")
        .set(3, 70, 3, "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]")
        .set(4, 170, 4, "minecraft:gold_block") // section 10 起點沒有
        .entity(kept, 2.5, 70, 2.5, "minecraft:armor_stand")
        .entity(added, 40.5, 70, 8.5, "minecraft:armor_stand"); // chunk 2,0
    var marks = new MarkTable();
    for (var p : List.of(new BlockPos(0, 64, 0), new BlockPos(3, 70, 3), new BlockPos(4, 170, 4)))
      marks.markCell(OVERWORLD, p, alice);
    marks.markEntity(OVERWORLD, removed, alice);
    marks.markEntity(OVERWORLD, added, alice);
    var id = repo.commit(new Repository.CommitRequest(Repository.MAIN, repo.head(Repository.MAIN), marks.drain(), world,
        genesis, alice, 2, "build", 0, new TreeMap<>())).orElseThrow();

    var synth = new RegionSynthesizer(genesisDim, repo.reader(id), OVERWORLD, 4000);
    Path branch = dir.resolve("branch");
    assertTrue(synth.blocks(branch.resolve("region/r.0.0.mca"), 0, 0));
    assertTrue(synth.entities(branch.resolve("entities/r.0.0.mca"), 0, 0));
    assertFalse(synth.blocks(branch.resolve("region/r.5.5.mca"), 5, 5));
    assertFalse(Files.exists(branch.resolve("region/r.5.5.mca")));

    try (var out = new AnvilSource(d -> branch)) {
      var all = new BitSet();
      all.set(0, 4096);
      var s4 = out.readSection(new SectionKey(OVERWORLD, 0, 4, 0), all);
      assertEquals(Cell.of("minecraft:glass"), s4[new BlockPos(0, 64, 0).sectionIndex()]);
      assertEquals(Cell.of("minecraft:stone"), s4[new BlockPos(1, 64, 0).sectionIndex()]);
      var stairs = s4[new BlockPos(3, 70, 3).sectionIndex()];
      assertEquals("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]", stairs.state());
      assertNull(stairs.blockEntity(), "箱子換成樓梯後方塊實體要移除");
      assertEquals(Cell.of("minecraft:gold_block"), out.readSection(new SectionKey(OVERWORLD, 0, 10, 0), all)[new BlockPos(4, 170, 4).sectionIndex()]);
      assertEquals(Cell.of("minecraft:dirt"), out.readSection(new SectionKey(OVERWORLD, 1, 4, 0), all)[0]);
      assertTrue(out.readEntity(OVERWORLD, kept).isPresent());
      assertTrue(out.readEntity(OVERWORLD, removed).isEmpty());
      assertTrue(out.readEntity(OVERWORLD, added).isPresent());
    }
    try (var r = new RegionFile(branch.resolve("region/r.0.0.mca"))) {
      var c = r.read(RegionFile.index(0, 0));
      assertFalse(c.containsKey("isLightOn"));
      assertFalse(c.containsKey("Heightmaps"));
    }
    try (var r = new RegionFile(branch.resolve("entities/r.0.0.mca"))) {
      assertArrayEquals(new int[] {2, 0}, (int[]) r.read(RegionFile.index(2, 0)).get("Position"));
    }
    assertThrows(java.io.IOException.class, () -> synth.blocks(branch.resolve("region/r.0.0.mca"), 0, 0));
    genesis.close();
  }
}
