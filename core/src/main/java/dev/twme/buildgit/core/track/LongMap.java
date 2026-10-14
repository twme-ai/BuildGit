package dev.twme.buildgit.core.track;

import java.util.Arrays;
import java.util.function.LongFunction;

/** 以 long 為鍵的開放定址雜湊表；標記的熱路徑不能為每次查詢配置 Long 物件。不支援刪除單一鍵。 */
final class LongMap<V> {
  private static final long EMPTY = Long.MIN_VALUE;

  private long[] keys = new long[64];
  private Object[] values = new Object[64];
  private int size;

  LongMap() {
    Arrays.fill(keys, EMPTY);
  }

  private static int mix(long key) {
    key ^= key >>> 33;
    key *= 0xff51afd7ed558ccdL;
    key ^= key >>> 33;
    return (int) key;
  }

  @SuppressWarnings("unchecked")
  V get(long key) {
    int mask = keys.length - 1;
    for (int i = mix(key) & mask; ; i = (i + 1) & mask) {
      long k = keys[i];
      if (k == key) return (V) values[i];
      if (k == EMPTY) return null;
    }
  }

  @SuppressWarnings("unchecked")
  V computeIfAbsent(long key, LongFunction<V> factory) {
    if (key == EMPTY) throw new IllegalArgumentException("保留鍵");
    int mask = keys.length - 1;
    for (int i = mix(key) & mask; ; i = (i + 1) & mask) {
      long k = keys[i];
      if (k == key) return (V) values[i];
      if (k == EMPTY) {
        V v = factory.apply(key);
        keys[i] = key;
        values[i] = v;
        if (++size * 3 > keys.length * 2) grow();
        return v;
      }
    }
  }

  private void grow() {
    long[] oldKeys = keys;
    Object[] oldValues = values;
    keys = new long[oldKeys.length * 2];
    values = new Object[keys.length];
    Arrays.fill(keys, EMPTY);
    int mask = keys.length - 1;
    for (int j = 0; j < oldKeys.length; j++) {
      if (oldKeys[j] == EMPTY) continue;
      int i = mix(oldKeys[j]) & mask;
      while (keys[i] != EMPTY) i = (i + 1) & mask;
      keys[i] = oldKeys[j];
      values[i] = oldValues[j];
    }
  }

  int size() {
    return size;
  }

  boolean isEmpty() {
    return size == 0;
  }

  interface Visitor<V> {
    void visit(long key, V value);
  }

  @SuppressWarnings("unchecked")
  void forEach(Visitor<V> visitor) {
    for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY) visitor.visit(keys[i], (V) values[i]);
  }
}
