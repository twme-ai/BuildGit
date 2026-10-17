package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.util.*;

/**
 * 批次修改覆寫表，最後一次寫出新的根節點。沒被改到的子樹沿用原本的雜湊。
 *
 * <p>格子值 null 表示「回到與起點相同」，也就是刪除覆寫；生態域與實體值 null 同理，實體的 {@code Optional.empty()} 是墓碑。
 */
public final class OverlayEditor {
  /** 用來表示「刪除這一項」的哨兵值，因為 TreeMap 的值不能用 null 區分「沒改」與「刪除」。 */
  private static final Object REMOVE = new Object();

  private final OverlayStore store;
  private final ObjectId base;
  private final SortedMap<SectionKey, SortedMap<Integer, Object>> cells = new TreeMap<>();
  private final SortedMap<SectionKey, SortedMap<Integer, Object>> biomes = new TreeMap<>();
  private final SortedMap<String, SortedMap<UUID, Object>> entities = new TreeMap<>();

  public OverlayEditor(OverlayStore store, ObjectId base) {
    this.store = store;
    this.base = base;
  }

  public OverlayEditor setCell(String dimension, BlockPos pos, Cell cell) {
    cells.computeIfAbsent(SectionKey.of(dimension, pos), k -> new TreeMap<>())
        .put(pos.sectionIndex(), cell == null ? REMOVE : cell);
    return this;
  }

  /** @param index section 內的生態域格（{@link SectionOverlay#biomeIndex}） */
  public OverlayEditor setBiome(SectionKey key, int index, String biome) {
    biomes.computeIfAbsent(key, k -> new TreeMap<>()).put(index, biome == null ? REMOVE : biome);
    return this;
  }

  public OverlayEditor setEntity(String dimension, UUID uuid, Optional<EntityState> state) {
    entities.computeIfAbsent(dimension, k -> new TreeMap<>()).put(uuid, state == null ? REMOVE : state);
    return this;
  }

  public boolean isEmpty() {
    return cells.isEmpty() && biomes.isEmpty() && entities.isEmpty();
  }

  public ObjectId build() throws IOException {
    // 維度 → region → chunk → section 名稱 → 新的項目（null 表示刪除）
    var plan = new TreeMap<String, Map<String, Map<String, Map<String, Tree.Entry>>>>();
    var root = store.tree(base);
    var keys = new TreeSet<>(cells.keySet());
    keys.addAll(biomes.keySet());
    for (var key : keys) {
      var dim = root.get(key.dimension());
      var region = store.tree(dim).get(Tree.region(key.chunkX(), key.chunkZ()));
      var chunk = store.tree(region).get(Tree.chunk(key.chunkX(), key.chunkZ()));
      var old = store.section(store.tree(chunk).get(Tree.section(key.sectionY())));
      var updated = new TreeMap<>(old.cells());
      cells.getOrDefault(key, Collections.emptySortedMap()).forEach((index, value) -> {
        if (value == REMOVE) updated.remove(index);
        else updated.put(index, (Cell) value);
      });
      var updatedBiomes = new TreeMap<>(old.biomes());
      biomes.getOrDefault(key, Collections.emptySortedMap()).forEach((index, value) -> {
        if (value == REMOVE) updatedBiomes.remove(index);
        else updatedBiomes.put(index, (String) value);
      });
      var entry = store.put(new SectionOverlay(updated, updatedBiomes));
      plan.computeIfAbsent(key.dimension(), k -> new TreeMap<>())
          .computeIfAbsent(Tree.region(key.chunkX(), key.chunkZ()), k -> new TreeMap<>())
          .computeIfAbsent(Tree.chunk(key.chunkX(), key.chunkZ()), k -> new TreeMap<>())
          .put(Tree.section(key.sectionY()), entry);
    }
    var rootEdits = new TreeMap<String, Tree.Entry>();
    var touchedDims = new TreeSet<String>(plan.keySet());
    touchedDims.addAll(entities.keySet());
    for (String dimName : touchedDims) {
      var dimTree = store.tree(root.get(dimName));
      var dimEdits = new TreeMap<String, Tree.Entry>();
      for (var regionPlan : plan.getOrDefault(dimName, Map.of()).entrySet()) {
        var regionTree = store.tree(dimTree.get(regionPlan.getKey()));
        var regionEdits = new TreeMap<String, Tree.Entry>();
        for (var chunkPlan : regionPlan.getValue().entrySet()) {
          var chunkTree = store.tree(regionTree.get(chunkPlan.getKey()));
          regionEdits.put(chunkPlan.getKey(), store.put(apply(chunkTree, chunkPlan.getValue())));
        }
        dimEdits.put(regionPlan.getKey(), store.put(apply(regionTree, regionEdits)));
      }
      var entityEdits = entities.get(dimName);
      if (entityEdits != null) dimEdits.put(Tree.ENTITIES, buildEntities(store.tree(dimTree.get(Tree.ENTITIES)), entityEdits));
      rootEdits.put(dimName, store.put(apply(dimTree, dimEdits)));
    }
    return store.putRoot(apply(root, rootEdits));
  }

  private Tree.Entry buildEntities(Tree shards, SortedMap<UUID, Object> edits) throws IOException {
    var byShard = new TreeMap<String, SortedMap<UUID, Object>>();
    edits.forEach((uuid, v) -> byShard.computeIfAbsent(Tree.entityShard(uuid), k -> new TreeMap<>()).put(uuid, v));
    var shardEdits = new TreeMap<String, Tree.Entry>();
    for (var e : byShard.entrySet()) {
      var updated = new TreeMap<>(store.entities(shards.get(e.getKey())).entities());
      e.getValue().forEach((uuid, value) -> {
        if (value == REMOVE) updated.remove(uuid);
        else {
          @SuppressWarnings("unchecked")
          var state = (Optional<EntityState>) value;
          updated.put(uuid, state);
        }
      });
      shardEdits.put(e.getKey(), store.put(new EntityOverlay(updated)));
    }
    return store.put(apply(shards, shardEdits));
  }

  /** 把編輯套到目錄上；值為 null 的項目刪除。 */
  static Tree apply(Tree tree, Map<String, Tree.Entry> edits) {
    var map = new TreeMap<>(tree.entries());
    edits.forEach((name, entry) -> {
      if (entry == null) map.remove(name);
      else map.put(name, entry);
    });
    return new Tree(map);
  }
}
