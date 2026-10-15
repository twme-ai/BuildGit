package dev.twme.buildgit.core.anvil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 寫入 Anvil region 檔（.mca），格式與原版相同，伺服器可以直接讀。
 *
 * <p>新資料一律附加在檔尾，寫完資料才更新標頭：寫到一半當機時，標頭仍指向舊資料（或沒有這個 chunk），
 * 不會指向寫了一半的內容。被取代的舊 sector 留在檔案裡不回收；原版會在下次改寫時整理。
 */
public final class RegionWriter implements Closeable {
  private static final int SECTOR = 4096;
  /** 超過這個 sector 數就改存外部 .mcc 檔，與原版相同。 */
  private static final int MAX_SECTORS = 255;

  private final Path path;
  private final FileChannel channel;
  private final int[] locations = new int[1024];

  public RegionWriter(Path path) throws IOException {
    this.path = path;
    if (!RegionFile.NAME.matcher(path.getFileName().toString()).matches()) throw new IOException("region 檔名無效：" + path);
    Files.createDirectories(path.getParent());
    channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
    try {
      if (channel.size() < 2 * SECTOR) {
        if (channel.size() != 0) throw new IOException("region 標頭截斷：" + path);
        writeFully(ByteBuffer.allocate(2 * SECTOR), 0);
      }
      var header = ByteBuffer.allocate(SECTOR);
      while (header.hasRemaining()) if (channel.read(header, header.position()) < 0) throw new EOFException(path.toString());
      header.flip();
      for (int i = 0; i < 1024; i++) locations[i] = header.getInt(i * 4);
    } catch (Throwable t) {
      channel.close();
      throw t;
    }
  }

  public boolean has(int index) {
    return locations[index] != 0;
  }

  /**
   * 寫入一個 chunk（未壓縮的 NBT，與 {@code NbtIo.write} 的輸出相同），以 zlib 壓縮。
   *
   * @param index {@link RegionFile#index}
   */
  public synchronized void write(int index, byte[] nbt) throws IOException {
    var compressed = new ByteArrayOutputStream(nbt.length / 4 + 64);
    var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    try (var out = new DeflaterOutputStream(compressed, deflater)) {
      out.write(nbt);
    } finally {
      deflater.end(); // 自己給的 Deflater 不會隨串流關閉釋放原生記憶體
    }
    byte[] body = compressed.toByteArray();
    int sectors = (body.length + 5 + SECTOR - 1) / SECTOR;
    ByteBuffer data;
    if (sectors > MAX_SECTORS) {
      String name = path.getFileName().toString();
      var m = RegionFile.NAME.matcher(name);
      if (!m.matches()) throw new IOException("region 檔名無效：" + path);
      int cx = Integer.parseInt(m.group(1)) * 32 + (index & 31), cz = Integer.parseInt(m.group(2)) * 32 + (index >>> 5);
      Path external = path.resolveSibling("c." + cx + "." + cz + ".mcc");
      Path tmp = external.resolveSibling(external.getFileName() + ".tmp");
      Files.write(tmp, body);
      Files.move(tmp, external, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      data = ByteBuffer.allocate(SECTOR).putInt(1).put((byte) (2 | 128));
      sectors = 1;
    } else {
      data = ByteBuffer.allocate(sectors * SECTOR).putInt(body.length + 1).put((byte) 2).put(body);
    }
    data.clear();
    long end = Math.max(2L * SECTOR, (channel.size() + SECTOR - 1) / SECTOR * SECTOR);
    int offset = (int) (end / SECTOR);
    if (offset > 0xFFFFFF) throw new IOException("region 檔太大：" + path);
    writeFully(data, end);
    channel.force(false);
    int location = offset << 8 | sectors;
    var entry = ByteBuffer.allocate(4).putInt(location);
    entry.flip();
    writeFully(entry, index * 4L);
    var time = ByteBuffer.allocate(4).putInt((int) (System.currentTimeMillis() / 1000));
    time.flip();
    writeFully(time, SECTOR + index * 4L);
    channel.force(false);
    locations[index] = location;
  }

  private void writeFully(ByteBuffer b, long offset) throws IOException {
    long start = offset - b.position();
    while (b.hasRemaining()) channel.write(b, start + b.position());
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
