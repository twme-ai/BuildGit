package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.util.*;

/** 讀取某個版本的覆寫表。查不到的格子或實體回傳 null，表示「與起點世界相同」。 */
public final class OverlayReader {
  private final OverlayStore store;
  private final ObjectId root;

  public OverlayReader(OverlayStore store, ObjectId root) {
    this.store = store;
    this.root = root;
  }

  public ObjectId root() {
    return root;
  }

  public SectionOverlay section(SectionKey key) throws IOException {
    var dim = store.tree(root).get(key.dimension());
    var region = store.tree(dim).get(Tree.region(key.chunkX(), key.chunkZ()));
    var chunk = store.tree(region).get(Tree.chunk(key.chunkX(), key.chunkZ()));
    return store.section(store.tree(chunk).get(Tree.section(key.sectionY())));
  }

  public Cell cell(String dimension, BlockPos pos) throws IOException {
    return section(SectionKey.of(dimension, pos)).get(pos.sectionIndex());
  }

  /** 一個 chunk 中所有有覆寫的 section，供合成讀取使用。 */
  public SortedMap<Integer, SectionOverlay> chunk(String dimension, int chunkX, int chunkZ) throws IOException {
    var dim = store.tree(root).get(dimension);
    var region = store.tree(dim).get(Tree.region(chunkX, chunkZ));
    var chunk = store.tree(store.tree(region).get(Tree.chunk(chunkX, chunkZ)));
    var result = new TreeMap<Integer, SectionOverlay>();
    for (var e : chunk.entries().entrySet()) result.put(Tree.sectionY(e.getKey()), store.section(e.getValue()));
    return result;
  }

  /** region 內有覆寫的 chunk 座標（{x, z}）。 */
  public List<int[]> chunksInRegion(String dimension, int regionX, int regionZ) throws IOException {
    var dim = store.tree(root).get(dimension);
    var region = store.tree(store.tree(dim).get("r." + regionX + "." + regionZ));
    var result = new ArrayList<int[]>();
    for (var name : region.entries().keySet()) result.add(Tree.coords(name));
    return result;
  }

  /** null：與起點相同；{@code Optional.empty()}：起點的實體已被移除。 */
  public Optional<EntityState> entity(String dimension, UUID uuid) throws IOException {
    var dim = store.tree(root).get(dimension);
    var shards = store.tree(dim).get(Tree.ENTITIES);
    return store.entities(store.tree(shards).get(Tree.entityShard(uuid))).get(uuid);
  }

  /** 某維度所有被覆寫的實體。 */
  public SortedMap<UUID, Optional<EntityState>> entities(String dimension) throws IOException {
    var dim = store.tree(root).get(dimension);
    var shards = store.tree(store.tree(dim).get(Tree.ENTITIES));
    var result = new TreeMap<UUID, Optional<EntityState>>();
    for (var shard : shards.entries().values()) result.putAll(store.entities(shard).entities());
    return result;
  }

  public Set<String> dimensions() throws IOException {
    return store.tree(root).entries().keySet();
  }
}
