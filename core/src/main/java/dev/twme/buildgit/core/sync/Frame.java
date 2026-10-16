package dev.twme.buildgit.core.sync;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 伺服器與 BuildGit 用戶端之間 {@code buildgit:sync} 頻道的一個封包（設計 §6.7）。
 * 範圍資料太大，切成多段依序傳送。
 *
 * @param index 第幾段（從 0 起）
 * @param total 總段數；沒有資料的封包為 0
 */
public record Frame(int type, String name, int index, int total, byte[] data) {
  public static final String CHANNEL = "buildgit:sync";
  /** 伺服器 → 用戶端：取出的範圍。 */
  public static final int BUNDLE = 1;
  /** 伺服器 → 用戶端：請上傳改過的範圍。 */
  public static final int PUSH = 2;
  /** 用戶端 → 伺服器：改過的範圍。 */
  public static final int UPLOAD = 3;
  /** 用戶端 → 伺服器：無法完成，{@code data} 是原因代碼（UTF-8）。 */
  public static final int ERROR = 4;

  /** 伺服器送出的一段（原版上限 1 MiB）。 */
  public static final int SERVER_PART = 512 * 1024;
  /** 用戶端送出的一段（原版上限 32767 位元組）。 */
  public static final int CLIENT_PART = 30_000;

  public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

  public byte[] encode() {
    var bytes = new ByteArrayOutputStream(data.length + 48);
    try (var out = new DataOutputStream(bytes)) {
      out.writeByte(type);
      out.writeUTF(name);
      out.writeInt(index);
      out.writeInt(total);
      out.write(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  public static Frame decode(byte[] bytes) throws IOException {
    var in = new DataInputStream(new ByteArrayInputStream(bytes));
    int type = in.readUnsignedByte();
    String name = in.readUTF();
    if (!NAME.matcher(name).matches()) throw new IOException("名稱不正確");
    int index = in.readInt(), total = in.readInt();
    return new Frame(type, name, index, total, in.readAllBytes());
  }

  /** 把資料切成多段。 */
  public static List<Frame> split(int type, String name, byte[] data, int part) {
    int total = Math.max(1, (data.length + part - 1) / part);
    var frames = new ArrayList<Frame>(total);
    for (int i = 0; i < total; i++) {
      int from = i * part, to = Math.min(data.length, from + part);
      frames.add(new Frame(type, name, i, total, java.util.Arrays.copyOfRange(data, from, to)));
    }
    return frames;
  }

  /** 依序接收多段資料。 */
  public static final class Assembler {
    private final String name;
    private final long limit;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int next, total = -1;

    public Assembler(String name, long limit) {
      this.name = name;
      this.limit = limit;
    }

    public String name() {
      return name;
    }

    /** @return 收齊時的完整資料；還沒收齊為 null */
    public byte[] add(Frame f) throws IOException {
      if (total < 0) {
        // 段數在第一段就固定，宣告的總量超過上限立刻拒絕
        if (f.total <= 0 || (long) f.total * f.data.length > limit + f.data.length) throw new IOException("資料超過上限");
        total = f.total;
      }
      if (!f.name.equals(name) || f.index != next || f.total != total || f.data.length == 0) throw new IOException("傳輸順序錯誤");
      if (out.size() + (long) f.data.length > limit) throw new IOException("資料超過上限");
      out.write(f.data);
      next++;
      return next == total ? out.toByteArray() : null;
    }
  }
}
