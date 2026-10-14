package dev.twme.buildgit.core.store;

import java.io.*;
import java.util.UUID;

/** 物件序列化用的小工具。所有物件格式都以版本位元組開頭。 */
public final class Bin {
  private Bin() {}

  public static final class Out {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    public Out u8(int v) {
      try {
        out.writeByte(v);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return this;
    }

    public Out i32(int v) {
      try {
        out.writeInt(v);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return this;
    }

    public Out i64(long v) {
      try {
        out.writeLong(v);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return this;
    }

    public Out str(String v) {
      byte[] b = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      i32(b.length);
      return raw(b);
    }

    public Out bytes(byte[] v) {
      i32(v.length);
      return raw(v);
    }

    /** 可為 null 的位元組陣列：長度 -1 表示 null。 */
    public Out nullableBytes(byte[] v) {
      return v == null ? i32(-1) : bytes(v);
    }

    public Out raw(byte[] v) {
      try {
        out.write(v);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return this;
    }

    public Out id(ObjectId id) {
      return raw(id.bytes());
    }

    public Out uuid(UUID id) {
      return i64(id.getMostSignificantBits()).i64(id.getLeastSignificantBits());
    }

    public byte[] toByteArray() {
      return bytes.toByteArray();
    }
  }

  public static final class In {
    private final DataInputStream in;
    private final int length;

    public In(byte[] data) {
      in = new DataInputStream(new ByteArrayInputStream(data));
      length = data.length;
    }

    public int u8() throws IOException {
      return in.readUnsignedByte();
    }

    public int i32() throws IOException {
      return in.readInt();
    }

    public long i64() throws IOException {
      return in.readLong();
    }

    public int count() throws IOException {
      int n = in.readInt();
      if (n < 0 || n > length) throw new IOException("物件內的數量無效：" + n);
      return n;
    }

    public String str() throws IOException {
      return new String(bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] bytes() throws IOException {
      byte[] b = new byte[count()];
      in.readFully(b);
      return b;
    }

    public byte[] nullableBytes() throws IOException {
      int n = in.readInt();
      if (n == -1) return null;
      if (n < 0 || n > length) throw new IOException("物件內的長度無效：" + n);
      byte[] b = new byte[n];
      in.readFully(b);
      return b;
    }

    public ObjectId id() throws IOException {
      byte[] b = new byte[ObjectId.LENGTH];
      in.readFully(b);
      return ObjectId.of(b);
    }

    public UUID uuid() throws IOException {
      return new UUID(in.readLong(), in.readLong());
    }

    public void end() throws IOException {
      if (in.available() != 0) throw new IOException("物件結尾有多餘位元組");
    }
  }
}
