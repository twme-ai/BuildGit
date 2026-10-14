package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectStore;
import dev.twme.buildgit.core.store.ObjectType;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 覆寫表物件的讀寫，附小型快取。物件不可變，所以快取永遠正確。 */
public final class OverlayStore {
  private static final int CACHE = 4096;
  private final ObjectStore objects;
  private final Map<ObjectId, Object> cache =
      Collections.synchronizedMap(
          new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ObjectId, Object> eldest) {
              return size() > CACHE;
            }
          });

  public OverlayStore(ObjectStore objects) {
    this.objects = objects;
  }

  public ObjectStore objects() {
    return objects;
  }

  public Tree tree(ObjectId id) throws IOException {
    if (id == null) return Tree.EMPTY;
    if (cache.get(id) instanceof Tree t) return t;
    var t = Tree.decode(objects.get(id, ObjectType.TREE));
    cache.put(id, t);
    return t;
  }

  public Tree tree(Tree.Entry entry) throws IOException {
    return entry == null ? Tree.EMPTY : tree(entry.id());
  }

  public SectionOverlay section(Tree.Entry entry) throws IOException {
    if (entry == null) return SectionOverlay.EMPTY;
    if (cache.get(entry.id()) instanceof SectionOverlay s) return s;
    var s = SectionOverlay.decode(objects.get(entry.id(), ObjectType.SECTION));
    cache.put(entry.id(), s);
    return s;
  }

  public EntityOverlay entities(Tree.Entry entry) throws IOException {
    if (entry == null) return EntityOverlay.EMPTY;
    if (cache.get(entry.id()) instanceof EntityOverlay e) return e;
    var e = EntityOverlay.decode(objects.get(entry.id(), ObjectType.ENTITIES));
    cache.put(entry.id(), e);
    return e;
  }

  /** 空目錄回傳 null，讓父節點移除這個項目。 */
  public Tree.Entry put(Tree tree) throws IOException {
    if (tree.entries().isEmpty()) return null;
    var id = objects.put(ObjectType.TREE, tree.encode());
    cache.put(id, tree);
    return new Tree.Entry(ObjectType.TREE, id);
  }

  /** 根節點即使是空的也要存，提交永遠指向一個 tree。 */
  public ObjectId putRoot(Tree tree) throws IOException {
    var id = objects.put(ObjectType.TREE, tree.encode());
    cache.put(id, tree);
    return id;
  }

  public Tree.Entry put(SectionOverlay section) throws IOException {
    if (section.isEmpty()) return null;
    var id = objects.put(ObjectType.SECTION, section.encode());
    cache.put(id, section);
    return new Tree.Entry(ObjectType.SECTION, id);
  }

  public Tree.Entry put(EntityOverlay entities) throws IOException {
    if (entities.isEmpty()) return null;
    var id = objects.put(ObjectType.ENTITIES, entities.encode());
    cache.put(id, entities);
    return new Tree.Entry(ObjectType.ENTITIES, id);
  }
}
