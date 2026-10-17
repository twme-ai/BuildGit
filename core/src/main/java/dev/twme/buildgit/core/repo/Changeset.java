package dev.twme.buildgit.core.repo;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.store.Bin;
import java.io.IOException;
import java.util.*;

/**
 * 一次提交相對於第一個父提交改了什麼、各是誰改的。供 log、show、blame 使用，不參與合併。
 * 格子依（維度、x、z、y）排序，所以可以二分搜尋。生態域格以最小角的座標記錄。
 */
public record Changeset(List<Author> authors, List<CellChange> cells, List<EntityChange> entities, List<BiomeChange> biomes) {
  private static final int VERSION = 1;
  /** 有生態域變更時的格式。 */
  private static final int WITH_BIOMES = 2;

  public enum Kind {
    ADDED,
    REMOVED,
    MODIFIED
  }

  public record CellChange(String dimension, BlockPos pos, Kind kind, int author)
      implements Comparable<CellChange> {
    @Override
    public int compareTo(CellChange o) {
      return compare(dimension, pos, o.dimension, o.pos);
    }
  }

  public record EntityChange(String dimension, UUID uuid, Kind kind, int author) {}

  public record BiomeChange(String dimension, BlockPos corner, int author) {}

  public Changeset {
    authors = List.copyOf(authors);
    var sorted = new ArrayList<>(cells);
    Collections.sort(sorted);
    cells = List.copyOf(sorted);
    entities = List.copyOf(entities);
    biomes = List.copyOf(biomes);
  }

  public Changeset(List<Author> authors, List<CellChange> cells, List<EntityChange> entities) {
    this(authors, cells, entities, List.of());
  }

  static int compare(String d1, BlockPos a, String d2, BlockPos b) {
    int c = d1.compareTo(d2);
    if (c == 0) c = Integer.compare(a.x(), b.x());
    if (c == 0) c = Integer.compare(a.z(), b.z());
    if (c == 0) c = Integer.compare(a.y(), b.y());
    return c;
  }

  public Optional<CellChange> find(String dimension, BlockPos pos) {
    int lo = 0, hi = cells.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      var c = cells.get(mid);
      int cmp = compare(c.dimension, c.pos, dimension, pos);
      if (cmp == 0) return Optional.of(c);
      if (cmp < 0) lo = mid + 1;
      else hi = mid - 1;
    }
    return Optional.empty();
  }

  public Author author(int index) {
    return authors.get(index);
  }

  /** 每位作者改了幾格。 */
  public Map<Author, Integer> cellsByAuthor() {
    var result = new LinkedHashMap<Author, Integer>();
    for (var c : cells) result.merge(authors.get(c.author), 1, Integer::sum);
    return result;
  }

  public byte[] encode() {
    var dims = new ArrayList<String>();
    var dimIndex = new HashMap<String, Integer>();
    for (var c : cells) dimIndex.computeIfAbsent(c.dimension, d -> { dims.add(d); return dims.size() - 1; });
    for (var e : entities) dimIndex.computeIfAbsent(e.dimension, d -> { dims.add(d); return dims.size() - 1; });
    for (var b : biomes) dimIndex.computeIfAbsent(b.dimension, d -> { dims.add(d); return dims.size() - 1; });
    var out = new Bin.Out().u8(biomes.isEmpty() ? VERSION : WITH_BIOMES).i32(authors.size());
    authors.forEach(a -> out.uuid(a.uuid()).str(a.name()));
    out.i32(dims.size());
    dims.forEach(out::str);
    out.i32(cells.size());
    for (var c : cells)
      out.i32(dimIndex.get(c.dimension)).i32(c.pos.x()).i32(c.pos.y()).i32(c.pos.z()).u8(c.kind.ordinal()).i32(c.author);
    out.i32(entities.size());
    for (var e : entities) out.i32(dimIndex.get(e.dimension)).uuid(e.uuid).u8(e.kind.ordinal()).i32(e.author);
    if (!biomes.isEmpty()) {
      out.i32(biomes.size());
      for (var b : biomes) out.i32(dimIndex.get(b.dimension)).i32(b.corner.x()).i32(b.corner.y()).i32(b.corner.z()).i32(b.author);
    }
    return out.toByteArray();
  }

  public static Changeset decode(byte[] data) throws IOException {
    var in = new Bin.In(data);
    int version = in.u8();
    if (version != VERSION && version != WITH_BIOMES) throw new IOException("不支援的 changeset 版本");
    int na = in.count();
    var authors = new ArrayList<Author>(na);
    for (int i = 0; i < na; i++) authors.add(new Author(in.uuid(), in.str()));
    int nd = in.count();
    var dims = new ArrayList<String>(nd);
    for (int i = 0; i < nd; i++) dims.add(in.str());
    int nc = in.count();
    var cells = new ArrayList<CellChange>(nc);
    var kinds = Kind.values();
    for (int i = 0; i < nc; i++) {
      String dim = dims.get(in.i32());
      var pos = new BlockPos(in.i32(), in.i32(), in.i32());
      cells.add(new CellChange(dim, pos, kinds[in.u8()], checkAuthor(in.i32(), na)));
    }
    int ne = in.count();
    var entities = new ArrayList<EntityChange>(ne);
    for (int i = 0; i < ne; i++)
      entities.add(new EntityChange(dims.get(in.i32()), in.uuid(), kinds[in.u8()], checkAuthor(in.i32(), na)));
    var biomes = new ArrayList<BiomeChange>();
    if (version == WITH_BIOMES) {
      int nb = in.count();
      for (int i = 0; i < nb; i++)
        biomes.add(new BiomeChange(dims.get(in.i32()), new BlockPos(in.i32(), in.i32(), in.i32()), checkAuthor(in.i32(), na)));
    }
    in.end();
    return new Changeset(authors, cells, entities, biomes);
  }

  private static int checkAuthor(int index, int count) throws IOException {
    if (index < 0 || index >= count) throw new IOException("changeset 作者索引無效");
    return index;
  }
}
