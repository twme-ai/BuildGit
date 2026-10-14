package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.util.*;

/**
 * 比較兩個覆寫表。雜湊相同的子樹直接略過，所以成本只跟變動量有關。
 * 格子與實體的值 null 表示「與起點相同」。
 */
public final class TreeDiff {
  private TreeDiff() {}

  public interface Visitor {
    void cell(String dimension, BlockPos pos, Cell before, Cell after) throws IOException;

    void entity(String dimension, UUID uuid, Optional<EntityState> before, Optional<EntityState> after)
        throws IOException;
  }

  public record CellChange(String dimension, BlockPos pos, Cell before, Cell after) {}

  public record EntityChange(String dimension, UUID uuid, Optional<EntityState> before, Optional<EntityState> after) {}

  public record Result(List<CellChange> cells, List<EntityChange> entities) {
    public boolean isEmpty() {
      return cells.isEmpty() && entities.isEmpty();
    }
  }

  public static Result collect(OverlayStore store, ObjectId a, ObjectId b) throws IOException {
    var cells = new ArrayList<CellChange>();
    var entities = new ArrayList<EntityChange>();
    diff(store, a, b, new Visitor() {
      @Override
      public void cell(String dimension, BlockPos pos, Cell before, Cell after) {
        cells.add(new CellChange(dimension, pos, before, after));
      }

      @Override
      public void entity(String dimension, UUID uuid, Optional<EntityState> before, Optional<EntityState> after) {
        entities.add(new EntityChange(dimension, uuid, before, after));
      }
    });
    return new Result(cells, entities);
  }

  public static void diff(OverlayStore store, ObjectId a, ObjectId b, Visitor visitor) throws IOException {
    if (Objects.equals(a, b)) return;
    var ta = store.tree(a);
    var tb = store.tree(b);
    for (String dim : union(ta, tb)) {
      var ea = ta.get(dim);
      var eb = tb.get(dim);
      if (!Objects.equals(ea, eb)) dimension(store, dim, store.tree(ea), store.tree(eb), visitor);
    }
  }

  private static void dimension(OverlayStore store, String dim, Tree a, Tree b, Visitor visitor) throws IOException {
    for (String name : union(a, b)) {
      var ea = a.get(name);
      var eb = b.get(name);
      if (Objects.equals(ea, eb)) continue;
      if (name.equals(Tree.ENTITIES)) entities(store, dim, store.tree(ea), store.tree(eb), visitor);
      else region(store, dim, store.tree(ea), store.tree(eb), visitor);
    }
  }

  private static void region(OverlayStore store, String dim, Tree a, Tree b, Visitor visitor) throws IOException {
    for (String name : union(a, b)) {
      var ea = a.get(name);
      var eb = b.get(name);
      if (Objects.equals(ea, eb)) continue;
      int[] c = Tree.coords(name);
      var ca = store.tree(ea);
      var cb = store.tree(eb);
      for (String s : union(ca, cb)) {
        var sa = ca.get(s);
        var sb = cb.get(s);
        if (!Objects.equals(sa, sb))
          section(dim, c[0], Tree.sectionY(s), c[1], store.section(sa), store.section(sb), visitor);
      }
    }
  }

  private static void section(
      String dim, int cx, int sy, int cz, SectionOverlay a, SectionOverlay b, Visitor visitor) throws IOException {
    var indices = new TreeSet<>(a.cells().keySet());
    indices.addAll(b.cells().keySet());
    for (int i : indices) {
      var ca = a.get(i);
      var cb = b.get(i);
      if (!Objects.equals(ca, cb)) visitor.cell(dim, BlockPos.fromSection(cx, sy, cz, i), ca, cb);
    }
  }

  private static void entities(OverlayStore store, String dim, Tree a, Tree b, Visitor visitor) throws IOException {
    for (String shard : union(a, b)) {
      var ea = a.get(shard);
      var eb = b.get(shard);
      if (Objects.equals(ea, eb)) continue;
      var ma = store.entities(ea).entities();
      var mb = store.entities(eb).entities();
      var ids = new TreeSet<>(ma.keySet());
      ids.addAll(mb.keySet());
      for (var id : ids) {
        var va = ma.get(id);
        var vb = mb.get(id);
        if (!Objects.equals(va, vb)) visitor.entity(dim, id, va, vb);
      }
    }
  }

  static SortedSet<String> union(Tree a, Tree b) {
    var names = new TreeSet<>(a.entries().keySet());
    names.addAll(b.entries().keySet());
    return names;
  }
}
