package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.util.*;

/**
 * 逐格三方合併（設計 §5.2）。以雜湊分層短路，只展開兩邊都有變動的子樹。
 *
 * <pre>
 * ours == theirs → ours；ours == base → theirs；theirs == base → ours；否則衝突（結果先放 ours）
 * </pre>
 *
 * 有選取範圍時，只接受範圍內來自 theirs 的變更；範圍外一律保留 ours。生態域格（4×4×4）只要和範圍重疊就算在範圍內。
 *
 * <p>生態域兩邊都改成不同的值時保留 ours，列在 {@link Result#biomeConflicts} 但不算衝突，不必整合：
 * 只能用指令或工具改、很少兩邊同時改同一處，事後在目標世界用 {@code /fillbiome} 修正即可。
 */
public final class ThreeWayMerge {
  /** 一個 region 的高度範圍涵蓋所有可能的世界高度。 */
  private static final int MIN_Y = -2048, MAX_Y = 2047;

  public record CellConflict(String dimension, BlockPos pos, Cell base, Cell ours, Cell theirs) {}

  public record EntityConflict(
      String dimension, UUID uuid, Optional<EntityState> base, Optional<EntityState> ours, Optional<EntityState> theirs) {}

  /** 兩邊都改了、結果保留 ours 的生態域格。 */
  public record BiomeConflict(SectionKey key, int index, String base, String ours, String theirs) {}

  public record Result(ObjectId tree, List<CellConflict> cellConflicts, List<EntityConflict> entityConflicts,
      List<BiomeConflict> biomeConflicts) {
    public boolean clean() {
      return cellConflicts.isEmpty() && entityConflicts.isEmpty();
    }
  }

  private final OverlayStore store;
  private final Box filter;
  /** 範圍只在這個維度；null 表示所有維度。 */
  private final String filterDimension;
  private final List<CellConflict> cellConflicts = new ArrayList<>();
  private final List<EntityConflict> entityConflicts = new ArrayList<>();
  private final List<BiomeConflict> biomeConflicts = new ArrayList<>();

  private ThreeWayMerge(OverlayStore store, String filterDimension, Box filter) {
    this.store = store;
    this.filter = filter;
    this.filterDimension = filterDimension;
  }

  /**
   * @param filter 只合併這個範圍內的變更；null 表示全部
   */
  public static Result merge(OverlayStore store, ObjectId base, ObjectId ours, ObjectId theirs, Box filter)
      throws IOException {
    return merge(store, base, ours, theirs, null, filter);
  }

  /**
   * @param filterDimension 範圍所在的維度；其他維度一律保留 ours。null 表示範圍套用到所有維度
   */
  public static Result merge(OverlayStore store, ObjectId base, ObjectId ours, ObjectId theirs, String filterDimension, Box filter)
      throws IOException {
    var m = new ThreeWayMerge(store, filterDimension, filter);
    var tb = store.tree(base);
    var to = store.tree(ours);
    var tt = store.tree(theirs);
    var edits = new TreeMap<String, Tree.Entry>();
    for (String dim : union(tb, to, tt)) {
      var eb = tb.get(dim);
      var eo = to.get(dim);
      var et = tt.get(dim);
      if (filter != null && filterDimension != null && !dim.equals(filterDimension)) {
        if (eo != null) edits.put(dim, eo);
        continue;
      }
      var quick = m.quick(eb, eo, et, null);
      edits.put(dim, quick != null ? quick.entry : m.dimension(dim, eb, eo, et));
    }
    var tree = store.putRoot(OverlayEditor.apply(Tree.EMPTY, edits));
    return new Result(tree, List.copyOf(m.cellConflicts), List.copyOf(m.entityConflicts), List.copyOf(m.biomeConflicts));
  }

  /** 不必展開時的結果；需要展開時回傳 null。 */
  private record Quick(Tree.Entry entry) {}

  private Quick quick(Tree.Entry base, Tree.Entry ours, Tree.Entry theirs, Box bounds) {
    if (Objects.equals(ours, theirs)) return new Quick(ours);
    if (Objects.equals(theirs, base)) return new Quick(ours);
    if (filter != null && bounds != null && !filter.intersects(bounds)) return new Quick(ours);
    if (Objects.equals(ours, base) && (filter == null || (bounds != null && filter.contains(bounds))))
      return new Quick(theirs);
    return null;
  }

  private Tree.Entry dimension(String dim, Tree.Entry eb, Tree.Entry eo, Tree.Entry et) throws IOException {
    var b = store.tree(eb);
    var o = store.tree(eo);
    var t = store.tree(et);
    var edits = new TreeMap<String, Tree.Entry>();
    for (String name : union(b, o, t)) {
      boolean entities = name.equals(Tree.ENTITIES);
      Box bounds = null;
      if (!entities) {
        int[] r = Tree.coords(name);
        bounds = new Box(r[0] << 9, MIN_Y, r[1] << 9, (r[0] << 9) + 511, MAX_Y, (r[1] << 9) + 511);
      }
      var quick = quick(b.get(name), o.get(name), t.get(name), bounds);
      if (quick != null) edits.put(name, quick.entry);
      else if (entities) edits.put(name, entities(dim, b.get(name), o.get(name), t.get(name)));
      else edits.put(name, region(dim, b.get(name), o.get(name), t.get(name)));
    }
    return store.put(OverlayEditor.apply(Tree.EMPTY, edits));
  }

  private Tree.Entry region(String dim, Tree.Entry eb, Tree.Entry eo, Tree.Entry et) throws IOException {
    var b = store.tree(eb);
    var o = store.tree(eo);
    var t = store.tree(et);
    var edits = new TreeMap<String, Tree.Entry>();
    for (String name : union(b, o, t)) {
      int[] c = Tree.coords(name);
      var bounds = new Box(c[0] << 4, MIN_Y, c[1] << 4, (c[0] << 4) + 15, MAX_Y, (c[1] << 4) + 15);
      var quick = quick(b.get(name), o.get(name), t.get(name), bounds);
      edits.put(name, quick != null ? quick.entry : chunk(dim, c[0], c[1], b.get(name), o.get(name), t.get(name)));
    }
    return store.put(OverlayEditor.apply(Tree.EMPTY, edits));
  }

  private Tree.Entry chunk(String dim, int cx, int cz, Tree.Entry eb, Tree.Entry eo, Tree.Entry et)
      throws IOException {
    var b = store.tree(eb);
    var o = store.tree(eo);
    var t = store.tree(et);
    var edits = new TreeMap<String, Tree.Entry>();
    for (String name : union(b, o, t)) {
      int sy = Tree.sectionY(name);
      var bounds = new Box(cx << 4, sy << 4, cz << 4, (cx << 4) + 15, (sy << 4) + 15, (cz << 4) + 15);
      var quick = quick(b.get(name), o.get(name), t.get(name), bounds);
      edits.put(name, quick != null ? quick.entry
          : section(dim, cx, sy, cz, store.section(b.get(name)), store.section(o.get(name)), store.section(t.get(name))));
    }
    return store.put(OverlayEditor.apply(Tree.EMPTY, edits));
  }

  private Tree.Entry section(String dim, int cx, int sy, int cz, SectionOverlay b, SectionOverlay o, SectionOverlay t)
      throws IOException {
    var indices = new TreeSet<>(b.cells().keySet());
    indices.addAll(o.cells().keySet());
    indices.addAll(t.cells().keySet());
    var result = new TreeMap<Integer, Cell>();
    for (int i : indices) {
      var pos = BlockPos.fromSection(cx, sy, cz, i);
      Cell vb = b.get(i), vo = o.get(i), vt = t.get(i);
      Cell chosen;
      if (Objects.equals(vo, vt) || Objects.equals(vt, vb) || (filter != null && !filter.contains(pos))) chosen = vo;
      else if (Objects.equals(vo, vb)) chosen = vt;
      else {
        chosen = vo;
        cellConflicts.add(new CellConflict(dim, pos, vb, vo, vt));
      }
      if (chosen != null) result.put(i, chosen);
    }
    var biomes = new TreeMap<Integer, String>();
    if (!b.biomes().isEmpty() || !o.biomes().isEmpty() || !t.biomes().isEmpty()) {
      var key = new SectionKey(dim, cx, sy, cz);
      var all = new TreeSet<>(b.biomes().keySet());
      all.addAll(o.biomes().keySet());
      all.addAll(t.biomes().keySet());
      for (int i : all) {
        String vb = b.biome(i), vo = o.biome(i), vt = t.biome(i);
        String chosen = vo;
        if (Objects.equals(vo, vt) || Objects.equals(vt, vb) || filter != null && !filter.intersects(biomeBounds(key, i))) chosen = vo;
        else if (Objects.equals(vo, vb)) chosen = vt;
        else biomeConflicts.add(new BiomeConflict(key, i, vb, vo, vt));
        if (chosen != null) biomes.put(i, chosen);
      }
    }
    return store.put(new SectionOverlay(result, biomes));
  }

  static Box biomeBounds(SectionKey key, int index) {
    var c = key.pos(SectionOverlay.biomeCorner(index));
    return new Box(c.x(), c.y(), c.z(), c.x() + 3, c.y() + 3, c.z() + 3);
  }

  private Tree.Entry entities(String dim, Tree.Entry eb, Tree.Entry eo, Tree.Entry et) throws IOException {
    var b = store.tree(eb);
    var o = store.tree(eo);
    var t = store.tree(et);
    var edits = new TreeMap<String, Tree.Entry>();
    for (String shard : union(b, o, t)) {
      var quick = quick(b.get(shard), o.get(shard), t.get(shard), null);
      if (quick != null) {
        edits.put(shard, quick.entry);
        continue;
      }
      var mb = store.entities(b.get(shard)).entities();
      var mo = store.entities(o.get(shard)).entities();
      var mt = store.entities(t.get(shard)).entities();
      var ids = new TreeSet<>(mb.keySet());
      ids.addAll(mo.keySet());
      ids.addAll(mt.keySet());
      var result = new TreeMap<UUID, Optional<EntityState>>();
      for (var id : ids) {
        var vb = mb.get(id);
        var vo = mo.get(id);
        var vt = mt.get(id);
        Optional<EntityState> chosen;
        if (Objects.equals(vo, vt) || Objects.equals(vt, vb) || !inFilter(vb, vo, vt)) chosen = vo;
        else if (Objects.equals(vo, vb)) chosen = vt;
        else {
          chosen = vo;
          entityConflicts.add(new EntityConflict(dim, id, vb, vo, vt));
        }
        if (chosen != null) result.put(id, chosen);
      }
      edits.put(shard, store.put(new EntityOverlay(result)));
    }
    return store.put(OverlayEditor.apply(Tree.EMPTY, edits));
  }

  /** 實體以任一邊記錄的位置判斷是否在範圍內；三邊都沒有位置時視為範圍外。 */
  private boolean inFilter(Optional<EntityState> b, Optional<EntityState> o, Optional<EntityState> t) {
    if (filter == null) return true;
    for (var v : List.of(Optional.ofNullable(t), Optional.ofNullable(o), Optional.ofNullable(b))) {
      if (v.isPresent() && v.get().isPresent()) {
        double[] p = v.get().get().position();
        if (p != null) return filter.contains(p[0], p[1], p[2]);
      }
    }
    return false;
  }

  private static SortedSet<String> union(Tree a, Tree b, Tree c) {
    var names = TreeDiff.union(a, b);
    names.addAll(c.entries().keySet());
    return names;
  }
}
