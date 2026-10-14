package dev.twme.buildgit.core.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.SectionKey;
import java.util.*;

/**
 * 取走的標記快照，交給提交使用。
 *
 * @param authors 作者表；{@link Section} 與 {@code entities} 的值是這張表的索引
 */
public record Marks(
    List<Author> authors, SortedMap<SectionKey, Section> sections, SortedMap<String, SortedMap<UUID, Integer>> entities) {
  public static final Marks EMPTY = new Marks(List.of(), new TreeMap<>(), new TreeMap<>());

  /**
   * 一個 section 的標記：哪些格子、各是誰。
   *
   * @param authors 每格的作者索引；整個 section 只有一位作者時為 null，作者是 {@code uniformAuthor}
   */
  public record Section(BitSet cells, short[] authors, short uniformAuthor) {
    public int authorIndex(int cell) {
      return authors == null ? uniformAuthor : authors[cell];
    }

    public Author author(Marks marks, int cell) {
      return marks.authors.get(authorIndex(cell));
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
