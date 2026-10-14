package dev.twme.buildgit.core.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 標記的附加寫入日誌（設計 §3.6）。檔名 {@code marks-<序號>.journal}；{@link #rotate()} 開新檔，
 * 提交成功後刪除舊檔。重啟時依序號重播全部檔案。
 *
 * <p>記錄格式：類型位元組加內容。作者與維度第一次出現時先寫定義記錄，之後以索引引用。
 * 結尾不完整的記錄（當機時寫到一半）在重播時略過。
 */
public final class MarkJournal implements MarkTable.Listener, Closeable {
  private static final int AUTHOR = 1, DIMENSION = 2, CELL = 3, ENTITY = 4;

  private final Path dir;
  private long sequence;
  private DataOutputStream out;
  private Path current;
  private final Map<Author, Integer> authors = new HashMap<>();
  private final Map<String, Integer> dimensions = new HashMap<>();
  private IOException failure;

  public MarkJournal(Path dir) throws IOException {
    this.dir = dir;
    Files.createDirectories(dir);
    sequence = files().stream().mapToLong(MarkJournal::sequence).max().orElse(0);
    open();
  }

  private void open() throws IOException {
    sequence++;
    current = dir.resolve("marks-" + sequence + ".journal");
    out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(current, StandardOpenOption.CREATE_NEW)));
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

  /** 開新檔並回傳之前的所有檔案；提交成功後交給 {@link #delete} 刪除。 */
  public synchronized List<Path> rotate() throws IOException {
    flush();
    out.close();
    var previous = files();
    open();
    return previous.stream().filter(p -> !p.equals(current)).toList();
  }

  public void delete(List<Path> files) throws IOException {
    for (var f : files) Files.deleteIfExists(f);
  }

  public synchronized void flush() throws IOException {
    if (failure != null) throw failure;
    out.flush();
  }

  @Override
  public synchronized void cell(String dimension, BlockPos pos, Author author) {
    write(() -> {
      int a = authorRef(author), d = dimensionRef(dimension);
      out.writeByte(CELL);
      out.writeInt(d);
      out.writeInt(pos.x());
      out.writeInt(pos.y());
      out.writeInt(pos.z());
      out.writeInt(a);
    });
  }

  @Override
  public synchronized void entity(String dimension, UUID uuid, Author author) {
    write(() -> {
      int a = authorRef(author), d = dimensionRef(dimension);
      out.writeByte(ENTITY);
      out.writeInt(d);
      out.writeLong(uuid.getMostSignificantBits());
      out.writeLong(uuid.getLeastSignificantBits());
      out.writeInt(a);
    });
  }

  private interface Write {
    void run() throws IOException;
  }

  /** 遊戲執行緒不能因寫檔失敗而中斷；錯誤保留到下一次 flush 再丟出。 */
  private void write(Write w) {
    if (failure != null) return;
    try {
      w.run();
    } catch (IOException e) {
      failure = e;
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

  /** 把所有日誌重播進標記表。重播期間不會再寫回日誌。 */
  public static void replay(List<Path> files, MarkTable table) throws IOException {
    for (var file : files) {
      var authors = new ArrayList<Author>();
      var dims = new ArrayList<String>();
      try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
        while (true) {
          int type = in.read();
          if (type < 0) break;
          try {
            switch (type) {
              case AUTHOR -> authors.add(new Author(new UUID(in.readLong(), in.readLong()), readString(in)));
              case DIMENSION -> dims.add(readString(in));
              case CELL -> {
                String d = dims.get(in.readInt());
                var pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
                table.markCell(d, pos, authors.get(in.readInt()));
              }
              case ENTITY -> {
                String d = dims.get(in.readInt());
                var uuid = new UUID(in.readLong(), in.readLong());
                table.markEntity(d, uuid, authors.get(in.readInt()));
              }
              default -> throw new IOException("標記日誌損毀：" + file);
            }
          } catch (EOFException e) {
            break; // 當機時寫到一半的最後一筆
          }
        }
      }
    }
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
