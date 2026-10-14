package dev.twme.buildgit.core.model;

import java.util.Map;
import java.util.TreeMap;

/** 方塊狀態字串的唯一格式：屬性依鍵名排序。存檔讀取與即時讀取都必須經過這裡，才能互相比較。 */
public final class BlockStates {
  private BlockStates() {}

  public static String format(String name, Map<String, String> properties) {
    if (properties.isEmpty()) return name;
    var sorted = properties instanceof TreeMap<String, String> t ? t : new TreeMap<>(properties);
    var b = new StringBuilder(name.length() + 16 * sorted.size()).append(name).append('[');
    boolean first = true;
    for (var e : sorted.entrySet()) {
      if (!first) b.append(',');
      first = false;
      b.append(e.getKey()).append('=').append(e.getValue());
    }
    return b.append(']').toString();
  }
}
