package dev.twme.buildgit.core.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.SectionKey;
import java.util.*;

/**
 * 一個工作世界的標記：自上次提交後被玩家動過的格子與實體，以及最後動它的作者（設計 §3.6）。
 * 標記只會增加，提交時以 {@link #drain()} 一次取走。執行緒安全。
 */
public final class MarkTable {
  /** 新標記或作者改變時通知，供日誌寫入。重複標記同一格同一作者不通知。 */
  public interface Listener {
    void cell(String dimension, BlockPos pos, Author author);

    void entity(String dimension, UUID uuid, Author author);
  }

  private static final class SectionMarks {
    final BitSet cells = new BitSet(4096);
    final short[] authors = new short[4096];
  }

  private final List<Author> authors = new ArrayList<>();
  private final Map<Author, Integer> authorIndex = new HashMap<>();
  private Map<SectionKey, SectionMarks> sections = new HashMap<>();
  private Map<String, Map<UUID, Integer>> entities = new HashMap<>();
  private Listener listener;

  private SectionKey lastKey;
  private SectionMarks lastSection;

  public synchronized void listener(Listener listener) {
    this.listener = listener;
  }

  private int author(Author author) {
    var index = authorIndex.get(author);
    if (index != null) return index;
    if (authors.size() >= Short.MAX_VALUE) throw new IllegalStateException("作者數量超過上限");
    authors.add(author);
    authorIndex.put(author, authors.size() - 1);
    return authors.size() - 1;
  }

  /** @return 是否為新標記或作者改變 */
  public synchronized boolean markCell(String dimension, BlockPos pos, Author author) {
    var key = SectionKey.of(dimension, pos);
    SectionMarks section;
    if (key.equals(lastKey)) section = lastSection;
    else {
      section = sections.computeIfAbsent(key, k -> new SectionMarks());
      lastKey = key;
      lastSection = section;
    }
    int index = pos.sectionIndex();
    short a = (short) author(author);
    if (section.cells.get(index) && section.authors[index] == a) return false;
    section.cells.set(index);
    section.authors[index] = a;
    if (listener != null) listener.cell(dimension, pos, author);
    return true;
  }

  public synchronized boolean markEntity(String dimension, UUID uuid, Author author) {
    int a = author(author);
    var previous = entities.computeIfAbsent(dimension, k -> new HashMap<>()).put(uuid, a);
    if (previous != null && previous == a) return false;
    if (listener != null) listener.entity(dimension, uuid, author);
    return true;
  }

  public synchronized boolean isMarked(String dimension, BlockPos pos) {
    var s = sections.get(SectionKey.of(dimension, pos));
    return s != null && s.cells.get(pos.sectionIndex());
  }

  /** 尚未提交的標記作者；沒有標記時回傳 null。供檢查工具使用。 */
  public synchronized Author authorAt(String dimension, BlockPos pos) {
    var s = sections.get(SectionKey.of(dimension, pos));
    if (s == null || !s.cells.get(pos.sectionIndex())) return null;
    return authors.get(s.authors[pos.sectionIndex()]);
  }

  public synchronized Author entityAuthor(String dimension, UUID uuid) {
    var index = entities.getOrDefault(dimension, Map.of()).get(uuid);
    return index == null ? null : authors.get(index);
  }

  public synchronized boolean isEmpty() {
    return sections.isEmpty() && entities.isEmpty();
  }

  public synchronized int cellCount() {
    int n = 0;
    for (var s : sections.values()) n += s.cells.cardinality();
    return n;
  }

  public synchronized int entityCount() {
    int n = 0;
    for (var m : entities.values()) n += m.size();
    return n;
  }

  /** 每位作者標記的格子數，供 status 與 bossbar 使用。 */
  public synchronized Map<Author, Integer> cellsByAuthor() {
    var result = new LinkedHashMap<Author, Integer>();
    for (var s : sections.values())
      for (int i = s.cells.nextSetBit(0); i >= 0; i = s.cells.nextSetBit(i + 1))
        result.merge(authors.get(s.authors[i]), 1, Integer::sum);
    return result;
  }

  /** 取走目前全部標記，換上空表。提交失敗時用 {@link #restore} 放回。 */
  public synchronized Marks drain() {
    var sectionSnapshot = new TreeMap<SectionKey, Marks.Section>();
    sections.forEach((k, s) -> sectionSnapshot.put(k, new Marks.Section(s.cells, s.authors)));
    var entitySnapshot = new TreeMap<String, SortedMap<UUID, Integer>>();
    entities.forEach((d, m) -> entitySnapshot.put(d, new TreeMap<>(m)));
    var marks = new Marks(authors, sectionSnapshot, entitySnapshot);
    sections = new HashMap<>();
    entities = new HashMap<>();
    lastKey = null;
    lastSection = null;
    return marks;
  }

  /** 把取走的標記放回。取走之後又被標記的格子，保留較新的作者。 */
  public synchronized void restore(Marks marks) {
    var saved = listener;
    listener = null;
    try {
      marks.sections().forEach((key, s) -> {
        var target = sections.computeIfAbsent(key, k -> new SectionMarks());
        for (int i = s.cells().nextSetBit(0); i >= 0; i = s.cells().nextSetBit(i + 1)) {
          if (target.cells.get(i)) continue;
          target.cells.set(i);
          target.authors[i] = (short) author(marks.authors().get(s.authors()[i]));
        }
      });
      marks.entities().forEach((dim, m) -> {
        var target = entities.computeIfAbsent(dim, k -> new HashMap<>());
        m.forEach((uuid, a) -> target.putIfAbsent(uuid, author(marks.authors().get(a))));
      });
    } finally {
      listener = saved;
    }
  }
}
