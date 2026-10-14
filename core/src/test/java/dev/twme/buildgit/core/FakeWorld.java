package dev.twme.buildgit.core;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.repo.StateSource;
import java.util.*;

/** 測試用的世界：沒設定的格子是空氣。 */
public final class FakeWorld implements StateSource {
  public static final String OVERWORLD = "minecraft:overworld";

  private final Map<String, Map<BlockPos, Cell>> cells = new HashMap<>();
  private final Map<String, Map<UUID, EntityState>> entities = new HashMap<>();
  public int sectionReads;

  public FakeWorld copy() {
    var w = new FakeWorld();
    cells.forEach((d, m) -> w.cells.put(d, new HashMap<>(m)));
    entities.forEach((d, m) -> w.entities.put(d, new HashMap<>(m)));
    return w;
  }

  public FakeWorld set(int x, int y, int z, String state) {
    return set(OVERWORLD, new BlockPos(x, y, z), Cell.of(state));
  }

  public FakeWorld set(String dim, BlockPos pos, Cell cell) {
    cells.computeIfAbsent(dim, k -> new HashMap<>()).put(pos, cell);
    return this;
  }

  public Cell get(int x, int y, int z) {
    return cells.getOrDefault(OVERWORLD, Map.of()).getOrDefault(new BlockPos(x, y, z), Cell.AIR);
  }

  public FakeWorld entity(UUID uuid, double x, double y, double z, String id) {
    var nbt = new Nbt.Compound().with("id", id)
        .with("Pos", new Nbt.ListTag(6, List.of(x, y, z)));
    entities.computeIfAbsent(OVERWORLD, k -> new HashMap<>()).put(uuid, new EntityState(uuid, Nbt.write(nbt)));
    return this;
  }

  public FakeWorld removeEntity(UUID uuid) {
    entities.getOrDefault(OVERWORLD, new HashMap<>()).remove(uuid);
    return this;
  }

  @Override
  public Cell[] readSection(SectionKey key, BitSet wanted) {
    sectionReads++;
    var result = new Cell[4096];
    var m = cells.getOrDefault(key.dimension(), Map.of());
    for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1))
      result[i] = m.getOrDefault(key.pos(i), Cell.AIR);
    return result;
  }

  @Override
  public Optional<EntityState> readEntity(String dimension, UUID uuid) {
    return Optional.ofNullable(entities.getOrDefault(dimension, Map.of()).get(uuid));
  }
}
