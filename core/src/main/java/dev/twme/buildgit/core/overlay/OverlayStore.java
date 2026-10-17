package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.Upgrader;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectStore;
import dev.twme.buildgit.core.store.ObjectType;
import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * 覆寫表物件的讀寫，附快取。物件不可變，所以快取永遠正確。
 *
 * <p>快取以「項目數」計重而不是物件數：一個整片被 FAWE 填滿的 section 有 4096 格，和只改一格的 section
 * 占的記憶體差上千倍，只限物件數的話，大編輯之後快取可能吃掉好幾百 MB。
 */
public final class OverlayStore {
  /** 快取裡所有物件的項目（格子、實體、目錄項目）總數上限，約數十 MB。 */
  private static final long MAX_WEIGHT = 400_000;
  private final ObjectStore objects;
  private final Upgrader upgrader;
  private final int legacy;
  private final LinkedHashMap<ObjectId, Object> cache = new LinkedHashMap<>(256, 0.75f, true);
  private long weight;

  public OverlayStore(ObjectStore objects) {
    this(objects, Upgrader.NONE, 0);
  }

  /**
   * 讀到的覆寫表一律升級成目前的格式（快取的也是升級後的內容），新寫的標上目前的資料版本。
   *
   * @param legacy 沒有記錄資料版本的舊物件所屬的資料版本
   */
  public OverlayStore(ObjectStore objects, Upgrader upgrader, int legacy) {
    this.objects = objects;
    this.upgrader = upgrader;
    this.legacy = legacy;
  }

  public ObjectStore objects() {
    return objects;
  }

  public Tree tree(ObjectId id) throws IOException {
    if (id == null) return Tree.EMPTY;
    if (cached(id) instanceof Tree t) return t;
    var t = Tree.decode(objects.get(id, ObjectType.TREE));
    remember(id, t);
    return t;
  }

  public Tree tree(Tree.Entry entry) throws IOException {
    return entry == null ? Tree.EMPTY : tree(entry.id());
  }

  public SectionOverlay section(Tree.Entry entry) throws IOException {
    if (entry == null) return SectionOverlay.EMPTY;
    if (cached(entry.id()) instanceof SectionOverlay s) return s;
    var s = SectionOverlay.decode(objects.get(entry.id(), ObjectType.SECTION), legacy, upgrader);
    remember(entry.id(), s);
    return s;
  }

  public EntityOverlay entities(Tree.Entry entry) throws IOException {
    if (entry == null) return EntityOverlay.EMPTY;
    if (cached(entry.id()) instanceof EntityOverlay e) return e;
    var e = EntityOverlay.decode(objects.get(entry.id(), ObjectType.ENTITIES), legacy, upgrader);
    remember(entry.id(), e);
    return e;
  }

  /** 空目錄回傳 null，讓父節點移除這個項目。 */
  public Tree.Entry put(Tree tree) throws IOException {
    if (tree.entries().isEmpty()) return null;
    var id = objects.put(ObjectType.TREE, tree.encode());
    remember(id, tree);
    return new Tree.Entry(ObjectType.TREE, id);
  }

  /** 根節點即使是空的也要存，提交永遠指向一個 tree。 */
  public ObjectId putRoot(Tree tree) throws IOException {
    var id = objects.put(ObjectType.TREE, tree.encode());
    remember(id, tree);
    return id;
  }

  public Tree.Entry put(SectionOverlay section) throws IOException {
    if (section.isEmpty()) return null;
    var id = objects.put(ObjectType.SECTION, section.encode(upgrader.current()));
    remember(id, section);
    return new Tree.Entry(ObjectType.SECTION, id);
  }

  public Tree.Entry put(EntityOverlay entities) throws IOException {
    if (entities.isEmpty()) return null;
    var id = objects.put(ObjectType.ENTITIES, entities.encode(upgrader.current()));
    remember(id, entities);
    return new Tree.Entry(ObjectType.ENTITIES, id);
  }

  private synchronized Object cached(ObjectId id) {
    return cache.get(id);
  }

  private synchronized void remember(ObjectId id, Object value) {
    var old = cache.put(id, value);
    if (old != null) weight -= weight(old);
    weight += weight(value);
    var it = cache.values().iterator();
    while (weight > MAX_WEIGHT && cache.size() > 1) {
      weight -= weight(it.next());
      it.remove();
    }
  }

  private static long weight(Object o) {
    return 1
        + switch (o) {
          case Tree t -> t.entries().size();
          case SectionOverlay s -> s.cells().size() + s.biomes().size();
          case EntityOverlay e -> e.entities().size();
          default -> 0;
        };
  }
}
