package dev.twme.buildgit.core.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.SectionKey;
import java.util.*;

/**
 * 取走的標記快照，交給提交使用。
 *
 * @param authors 作者表；{@link Section#authors()} 與 {@code entities} 的值是這張表的索引
 */
public record Marks(
    List<Author> authors, SortedMap<SectionKey, Section> sections, SortedMap<String, SortedMap<UUID, Integer>> entities) {
  public static final Marks EMPTY = new Marks(List.of(), new TreeMap<>(), new TreeMap<>());

  /** 一個 section 的標記：哪些格子、各是誰。 */
  public record Section(BitSet cells, short[] authors) {
    public Author author(Marks marks, int index) {
      return marks.authors.get(authors[index]);
    }
  }

  public Marks {
    authors = List.copyOf(authors);
    sections = Collections.unmodifiableSortedMap(sections);
    entities = Collections.unmodifiableSortedMap(entities);
  }

  public boolean isEmpty() {
    return sections.isEmpty() && entities.isEmpty();
  }

  public int cellCount() {
    int n = 0;
    for (var s : sections.values()) n += s.cells.cardinality();
    return n;
  }

  public int entityCount() {
    int n = 0;
    for (var m : entities.values()) n += m.size();
    return n;
  }
}
