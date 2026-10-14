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

  /** 名稱與屬性，{@link #format} 的反向。 */
  public record Parsed(String name, TreeMap<String, String> properties) {}

  public static Parsed parse(String state) {
    var props = new TreeMap<String, String>();
    int open = state.indexOf('[');
    if (open < 0) return new Parsed(state, props);
    if (!state.endsWith("]")) throw new IllegalArgumentException("方塊狀態格式錯誤：" + state);
    String body = state.substring(open + 1, state.length() - 1);
    if (!body.isEmpty())
      for (String pair : body.split(",")) {
        int eq = pair.indexOf('=');
        if (eq <= 0) throw new IllegalArgumentException("方塊狀態格式錯誤：" + state);
        props.put(pair.substring(0, eq), pair.substring(eq + 1));
      }
    return new Parsed(state.substring(0, open), props);
  }
}
