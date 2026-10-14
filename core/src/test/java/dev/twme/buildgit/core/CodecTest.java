package dev.twme.buildgit.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.nbt.Normalizer;
import dev.twme.buildgit.core.overlay.SectionOverlay;
import dev.twme.buildgit.core.repo.Changeset;
import dev.twme.buildgit.core.repo.Commit;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectStore;
import dev.twme.buildgit.core.store.ObjectType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodecTest {
  @TempDir Path dir;

  @Test
  void objectStoreRoundTripAndDedupe() throws Exception {
    var store = new ObjectStore(dir);
    var a = store.put(ObjectType.TREE, new byte[] {1, 2, 3});
    var b = store.put(ObjectType.TREE, new byte[] {1, 2, 3});
    assertEquals(a, b);
    assertNotEquals(a, store.put(ObjectType.SECTION, new byte[] {1, 2, 3}), "種類參與雜湊");
    assertArrayEquals(new byte[] {1, 2, 3}, store.get(a, ObjectType.TREE));
    assertThrows(java.io.IOException.class, () -> store.get(a, ObjectType.COMMIT));
  }

  @Test
  void objectStoreDetectsCorruption() throws Exception {
    var store = new ObjectStore(dir);
    var id = store.put(ObjectType.TREE, new byte[] {9, 9});
    var other = new ObjectStore(dir.resolve("x"));
    var bad = other.put(ObjectType.TREE, new byte[] {8});
    Files.copy(dir.resolve("x").resolve(bad.hex().substring(0, 2)).resolve(bad.hex().substring(2)),
        dir.resolve(id.hex().substring(0, 2)).resolve(id.hex().substring(2)),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    var fresh = new ObjectStore(dir);
    assertThrows(java.io.IOException.class, () -> fresh.get(id));
  }

  @Test
  void sectionEncodingIsDeterministic() throws Exception {
    var be = Nbt.write(new Nbt.Compound().with("Items", new Nbt.ListTag(0, List.of())));
    var a = new TreeMap<Integer, Cell>();
    a.put(5, Cell.of("minecraft:stone"));
    a.put(1, new Cell("minecraft:chest[facing=north]", be));
    a.put(3, Cell.of("minecraft:stone"));
    var b = new TreeMap<Integer, Cell>();
    b.put(3, Cell.of("minecraft:stone"));
    b.put(5, Cell.of("minecraft:stone"));
    b.put(1, new Cell("minecraft:chest[facing=north]", be.clone()));
    var sa = new SectionOverlay(a);
    assertArrayEquals(sa.encode(), new SectionOverlay(b).encode());
    assertEquals(sa, SectionOverlay.decode(sa.encode()));
  }

  @Test
  void changesetRoundTripAndBinarySearch() throws Exception {
    var alice = new Author(UUID.randomUUID(), "alice");
    var cells = new ArrayList<Changeset.CellChange>();
    for (int x = 10; x >= 0; x--)
      cells.add(new Changeset.CellChange("minecraft:overworld", new BlockPos(x, 64, -x), Changeset.Kind.ADDED, 0));
    var cs = new Changeset(List.of(alice), cells, List.of());
    var decoded = Changeset.decode(cs.encode());
    assertEquals(cs, decoded);
    assertTrue(decoded.find("minecraft:overworld", new BlockPos(7, 64, -7)).isPresent());
    assertTrue(decoded.find("minecraft:overworld", new BlockPos(7, 65, -7)).isEmpty());
    assertTrue(decoded.find("minecraft:the_nether", new BlockPos(7, 64, -7)).isEmpty());
    assertEquals(Map.of(alice, 11), decoded.cellsByAuthor());
  }

  @Test
  void commitRoundTrip() throws Exception {
    var id = ObjectId.parse("00".repeat(32));
    var trailers = new TreeMap<String, String>(Map.of(Commit.SOURCE_DRAFT, "屋頂B"));
    var c = new Commit(id, List.of(id, id), id, new Author(UUID.randomUUID(), "建築師"), 123L, "完成大廳", Commit.AUTO, trailers);
    var decoded = Commit.decode(c.encode());
    assertEquals(c, decoded);
    assertTrue(decoded.auto());
    assertTrue(decoded.merge());
  }

  @Test
  void normalizerDropsPositionAndTransientFields() {
    var raw = new Nbt.Compound().with("x", 1).with("y", 2).with("z", 3).with("CookTime", (short) 7)
        .with("Items", new Nbt.ListTag(0, List.of()));
    assertEquals(Set.of("Items"), Normalizer.blockEntity(raw).keySet());
    var entity = new Nbt.Compound().with("id", "minecraft:armor_stand").with("Motion", new Nbt.ListTag(0, List.of()))
        .with("Paper.Custom", 1);
    assertEquals(Set.of("id"), Normalizer.entity(entity).keySet());
  }
}
