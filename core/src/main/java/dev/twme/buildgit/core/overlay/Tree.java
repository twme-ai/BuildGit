package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.store.Bin;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectType;
import java.io.IOException;
import java.util.*;

/**
 * 覆寫表的目錄節點。路徑：
 *
 * <pre>
 * 根 / 維度 / r.X.Z / c.X.Z / s.Y        → SECTION
 * 根 / 維度 / entities / e.hh            → ENTITIES（依 UUID 第一個位元組分片）
 * </pre>
 *
 * 沒有覆寫的路徑不出現；空的子目錄會從父節點移除。
 */
public record Tree(SortedMap<String, Entry> entries) {
  private static final int VERSION = 1;
  public static final Tree EMPTY = new Tree(new TreeMap<>());
  public static final String ENTITIES = "entities";

  public record Entry(ObjectType type, ObjectId id) {}

  public Tree {
    entries = Collections.unmodifiableSortedMap(new TreeMap<>(entries));
  }

  public Entry get(String name) {
    return entries.get(name);
  }

  public byte[] encode() {
    var out = new Bin.Out().u8(VERSION).i32(entries.size());
    entries.forEach((name, e) -> out.str(name).u8(e.type().code).id(e.id()));
    return out.toByteArray();
  }

  public static Tree decode(byte[] data) throws IOException {
    var in = new Bin.In(data);
    if (in.u8() != VERSION) throw new IOException("不支援的 tree 版本");
    int n = in.count();
    var map = new TreeMap<String, Entry>();
    for (int i = 0; i < n; i++) map.put(in.str(), new Entry(ObjectType.of(in.u8()), in.id()));
    in.end();
    return new Tree(map);
  }

  public static String region(int chunkX, int chunkZ) {
    return "r." + (chunkX >> 5) + "." + (chunkZ >> 5);
  }

  public static String chunk(int chunkX, int chunkZ) {
    return "c." + chunkX + "." + chunkZ;
  }

  public static String section(int sectionY) {
    return "s." + sectionY;
  }

  public static String entityShard(UUID uuid) {
    return String.format("e.%02x", (int) (uuid.getMostSignificantBits() >>> 56));
  }

  /** 解析 {@code r.X.Z}、{@code c.X.Z} 的兩個整數。 */
  static int[] coords(String name) {
    String[] parts = name.split("\\.");
    return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
  }

  static int sectionY(String name) {
    return Integer.parseInt(name.substring(2));
  }
}
