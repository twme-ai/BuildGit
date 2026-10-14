package dev.twme.buildgit.core.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.SectionKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 標記的附加寫入日誌（設計 §3.6）。檔名 {@code marks-<序號>.journal}。
 *
 * <p>日誌不逐格記錄，而是定期把「變動過的 section」整份寫出：一次 FAWE 編輯可能標記上百萬格，
 * 逐格寫會讓日誌比世界還大。重播時依序合併，後寫的作者覆蓋先寫的；標記在提交前只會增加，所以合併結果正確。
 *
 * <p>提交時以 {@link #drainForCommit} 原子地輪替日誌並取走標記；提交成功後刪除舊檔，失敗時放回標記並寫進新檔。
 * 結尾不完整的記錄（當機時寫到一半）在重播時略過。
 */
public final class MarkJournal implements Closeable {
  private static final int AUTHOR = 1, DIMENSION = 2, ENTITY = 4, SECTION = 5;

  private final Path dir;
  private long sequence;
  private DataOutputStream out;
  private FileOutputStream file;
  private Path current;
  private final Map<Author, Integer> authors = new HashMap<>();
  private final Map<String, Integer> dimensions = new HashMap<>();

  public MarkJournal(Path dir) throws IOException {
    this.dir = dir;
    Files.createDirectories(dir);
    sequence = files().stream().mapToLong(MarkJournal::sequence).max().orElse(0);
    open();
  }

  private void open() throws IOException {
    sequence++;
    current = dir.resolve("marks-" + sequence + ".journal");
    file = new FileOutputStream(current.toFile(), false);
    out = new DataOutputStream(new BufferedOutputStream(file, 1 << 16));
    authors.clear();
    dimensions.clear();
  }

  /** 依序號排序的日誌檔。 */
  public List<Path> files() throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.getFileName().toString().matches("marks-\\d+\\.journal"))
          .sorted(Comparator.comparingLong(MarkJournal::sequence))
          .toList();
    }
  }

  private static long sequence(Path p) {
    String n = p.getFileName().toString();
    return Long.parseLong(n.substring(6, n.length() - 8));
  }

  /** 把標記表裡變動過的部分寫進日誌並寫到磁碟。平台每秒與世界存檔時呼叫。 */
  public synchronized void flush(MarkTable table) throws IOException {
    write(table.takeDirty());
    out.flush();
    file.getFD().sync();
  }

  public record Drained(Marks marks, List<Path> previousFiles) {}

  /**
   * 提交開始：寫出尚未寫的標記、開新日誌檔、取走全部標記。三步在標記表的鎖內完成，期間的新標記不會遺失。
   */
  public Drained drainForCommit(MarkTable table) throws IOException {
    synchronized (table) {
      synchronized (this) {
        write(table.takeDirty());
        out.flush();
        out.close();
        var previous = files();
        open();
        return new Drained(table.drain(), previous.stream().filter(p -> !p.equals(current)).toList());
      }
    }
  }

  /** 提交失敗：把標記放回並寫進目前的日誌，然後才能刪除舊檔。 */
  public void restore(MarkTable table, Drained drained) throws IOException {
    table.restore(drained.marks());
    flush(table);
    delete(drained.previousFiles());
  }

  public void delete(List<Path> files) throws IOException {
    for (var f : files) Files.deleteIfExists(f);
  }

  private void write(Marks marks) throws IOException {
    for (var e : marks.sections().entrySet()) {
      var key = e.getKey();
      var s = e.getValue();
      int d = dimensionRef(key.dimension());
      int uniform = s.authors() == null ? authorRef(marks.authors().get(s.uniformAuthor())) : -1;
      int[] perCell = null;
      if (s.authors() != null) {
        perCell = new int[s.cells().cardinality()];
        int n = 0;
        for (int i = s.cells().nextSetBit(0); i >= 0; i = s.cells().nextSetBit(i + 1))
          perCell[n++] = authorRef(marks.authors().get(s.authors()[i]));
      }
      out.writeByte(SECTION);
      out.writeInt(d);
      out.writeInt(key.chunkX());
      out.writeInt(key.sectionY());
      out.writeInt(key.chunkZ());
      long[] bits = s.cells().toLongArray();
      out.writeShort(bits.length);
      for (long b : bits) out.writeLong(b);
      if (perCell == null) {
        out.writeByte(0);
        out.writeInt(uniform);
      } else {
        out.writeByte(1);
        for (int a : perCell) out.writeInt(a);
      }
    }
    for (var e : marks.entities().entrySet()) {
      int d = dimensionRef(e.getKey());
      for (var m : e.getValue().entrySet()) {
        int a = authorRef(marks.authors().get(m.getValue()));
        out.writeByte(ENTITY);
        out.writeInt(d);
        out.writeLong(m.getKey().getMostSignificantBits());
        out.writeLong(m.getKey().getLeastSignificantBits());
        out.writeInt(a);
      }
    }
  }

  private int authorRef(Author author) throws IOException {
    var index = authors.get(author);
    if (index != null) return index;
    int i = authors.size();
    out.writeByte(AUTHOR);
    out.writeLong(author.uuid().getMostSignificantBits());
    out.writeLong(author.uuid().getLeastSignificantBits());
    writeString(author.name());
    authors.put(author, i);
    return i;
  }

  private int dimensionRef(String dimension) throws IOException {
    var index = dimensions.get(dimension);
    if (index != null) return index;
    int i = dimensions.size();
    out.writeByte(DIMENSION);
    writeString(dimension);
    dimensions.put(dimension, i);
    return i;
  }

  private void writeString(String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    out.writeInt(b.length);
    out.write(b);
  }

  /** 把所有日誌依序合併進標記表。 */
  public static void replay(List<Path> files, MarkTable table) throws IOException {
    for (var file : files) {
      var authors = new ArrayList<Author>();
      var dims = new ArrayList<String>();
      var sections = new TreeMap<SectionKey, Marks.Section>();
      var entities = new TreeMap<String, SortedMap<UUID, Integer>>();
      try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
        while (true) {
          int type = in.read();
          if (type < 0) break;
          try {
            switch (type) {
              case AUTHOR -> authors.add(new Author(new UUID(in.readLong(), in.readLong()), readString(in)));
              case DIMENSION -> dims.add(readString(in));
              case SECTION -> {
                var key = new SectionKey(dims.get(in.readInt()), in.readInt(), in.readInt(), in.readInt());
                int n = in.readUnsignedShort();
                if (n > 64) throw new IOException("標記日誌損毀：" + file);
                long[] bits = new long[n];
                for (int i = 0; i < n; i++) bits[i] = in.readLong();
                var cells = BitSet.valueOf(bits);
                Marks.Section section;
                if (in.readByte() == 0) {
                  section = new Marks.Section(cells, null, check(in.readInt(), authors));
                } else {
                  var perCell = new short[4096];
                  for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1))
                    perCell[i] = check(in.readInt(), authors);
                  section = new Marks.Section(cells, perCell, (short) 0);
                }
                sections.put(key, merge(sections.get(key), section));
              }
              case ENTITY -> {
                String d = dims.get(in.readInt());
                var uuid = new UUID(in.readLong(), in.readLong());
                entities.computeIfAbsent(d, k -> new TreeMap<>()).put(uuid, (int) check(in.readInt(), authors));
              }
              default -> throw new IOException("標記日誌損毀：" + file);
            }
          } catch (EOFException e) {
            break; // 當機時寫到一半的最後一筆
          } catch (IndexOutOfBoundsException e) {
            throw new IOException("標記日誌損毀：" + file, e);
          }
        }
      }
      table.merge(new Marks(authors, sections, entities), true);
    }
  }

  private static short check(int author, List<Author> authors) throws IOException {
    if (author < 0 || author >= authors.size()) throw new IOException("標記日誌的作者索引無效");
    return (short) author;
  }

  /** 同一檔案裡同一 section 的後一份快照覆蓋前一份的作者，並保留前一份才有的格子。 */
  private static Marks.Section merge(Marks.Section older, Marks.Section newer) {
    if (older == null) return newer;
    var cells = (BitSet) older.cells().clone();
    cells.or(newer.cells());
    var perCell = new short[4096];
    for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1))
      perCell[i] = (short) (newer.cells().get(i) ? newer.authorIndex(i) : older.authorIndex(i));
    return new Marks.Section(cells, perCell, (short) 0);
  }

  private static String readString(DataInputStream in) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > 65536) throw new IOException("標記日誌字串長度無效");
    byte[] b = new byte[n];
    in.readFully(b);
    return new String(b, StandardCharsets.UTF_8);
  }

  @Override
  public synchronized void close() throws IOException {
    out.close();
  }
}
