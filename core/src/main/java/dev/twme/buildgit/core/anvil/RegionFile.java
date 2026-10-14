package dev.twme.buildgit.core.anvil;

import dev.twme.buildgit.core.nbt.Nbt;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** 唯讀的 Anvil region 檔（.mca）。改寫自 WorldGit 的離線讀取；損毀資料必須報錯，不能靜默當成空氣。 */
public final class RegionFile implements Closeable {
  static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

  private final Path path;
  private final FileChannel channel;
  private final int[] locations = new int[1024];

  public RegionFile(Path path) throws IOException {
    this.path = path;
    if (!NAME.matcher(path.getFileName().toString()).matches()) throw new IOException("region 檔名無效：" + path);
    channel = FileChannel.open(path, StandardOpenOption.READ);
    try {
      // 第一次寫入前會留下 0 byte 的 .mca：視為沒有任何 chunk。
      if (channel.size() == 0) return;
      if (channel.size() < 8192) throw new IOException("region 標頭截斷：" + path);
      var header = ByteBuffer.allocate(4096);
      readFully(header, 0);
      header.flip();
      for (int i = 0; i < 1024; i++) {
        locations[i] = header.getInt(i * 4);
        if (locations[i] == 0) continue;
        int offset = locations[i] >>> 8, count = locations[i] & 255;
        if (offset < 2 || count == 0 || (long) offset * 4096 + 5 > channel.size())
          throw new IOException("region sector 無效：" + path + " index=" + i);
      }
    } catch (Throwable t) {
      channel.close();
      throw t;
    }
  }

  /** chunk 在 region 內的索引。 */
  public static int index(int chunkX, int chunkZ) {
    return (chunkX & 31) | (chunkZ & 31) << 5;
  }

  public static String fileName(int chunkX, int chunkZ) {
    return "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
  }

  private void readFully(ByteBuffer b, long offset) throws IOException {
    while (b.hasRemaining()) if (channel.read(b, offset + b.position()) < 0) throw new EOFException("region 截斷：" + path);
  }

  public boolean has(int index) {
    return locations[index] != 0;
  }

  /** @return chunk 的 NBT；不存在時回傳 null */
  public Nbt.Compound read(int index) throws IOException {
    var bytes = readBytes(index);
    return bytes == null ? null : Nbt.read(bytes);
  }

  /** @return chunk 未壓縮的 NBT 位元組；不存在時回傳 null */
  public synchronized byte[] readBytes(int index) throws IOException {
    if (!has(index)) return null;
    int location = locations[index];
    long offset = (long) (location >>> 8) * 4096;
    var head = ByteBuffer.allocate(5);
    readFully(head, offset);
    head.flip();
    int length = head.getInt();
    int compression = head.get() & 255;
    if (length < 1 || length > (location & 255) * 4096 - 4) throw new IOException("chunk 長度無效：" + path + " index=" + index);
    byte[] bytes;
    if ((compression & 128) != 0) {
      String name = path.getFileName().toString();
      var m = NAME.matcher(name);
      if (!m.matches()) throw new IOException("region 檔名無效：" + path);
      int cx = Integer.parseInt(m.group(1)) * 32 + (index & 31), cz = Integer.parseInt(m.group(2)) * 32 + (index >>> 5);
      Path external = path.resolveSibling("c." + cx + "." + cz + ".mcc");
      if (Files.size(external) > Nbt.MAX_BYTES) throw new IOException("外部 chunk 太大：" + external);
      bytes = Files.readAllBytes(external);
    } else {
      var body = ByteBuffer.allocate(length - 1);
      readFully(body, offset + 5);
      bytes = body.array();
    }
    return decode(compression & 127, bytes);
  }

  static byte[] decode(int compression, byte[] bytes) throws IOException {
    InputStream raw = new ByteArrayInputStream(bytes);
    try (InputStream in =
        switch (compression) {
          case 1 -> new GZIPInputStream(raw);
          case 2 -> new InflaterInputStream(raw);
          case 3 -> raw;
          // LZ4 需要額外函式庫；伺服器預設是 deflate，起點世界由 BuildGit 自己複製，所以這裡直接說明原因。
          case 4 -> throw new IOException("不支援 LZ4 壓縮的 region；請把 region-file-compression 設為 deflate");
          default -> throw new IOException("未知的 Anvil 壓縮：" + compression);
        }) {
      byte[] b = in.readNBytes(Nbt.MAX_BYTES + 1);
      if (b.length > Nbt.MAX_BYTES) throw new IOException("chunk 解壓後超過限制");
      return b;
    }
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
