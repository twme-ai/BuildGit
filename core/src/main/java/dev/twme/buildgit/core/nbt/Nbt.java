package dev.twme.buildgit.core.nbt;

import java.io.*;
import java.util.*;

/** NBT 讀寫。compound 依鍵名排序輸出，所以同樣內容一定得到同樣位元組。 */
public final class Nbt {
  private Nbt() {}

  public static final int MAX_BYTES = 32 * 1024 * 1024;
  private static final int MAX_DEPTH = 64;
  private static final int MAX_NODES = 1_000_000;

  public static final class Compound extends TreeMap<String, Object> {
    public Compound with(String key, Object value) {
      type(value);
      put(key, value);
      return this;
    }

    public Compound compound(String key) {
      return get(key) instanceof Compound c ? c : new Compound();
    }

    public ListTag list(String key) {
      return get(key) instanceof ListTag l ? l : new ListTag(0, List.of());
    }

    public String string(String key) {
      return get(key) instanceof String s ? s : "";
    }
  }

  public record ListTag(int type, List<Object> values) {
    public ListTag {
      values = List.copyOf(values);
      if (type < 0 || type > 12 || (type == 0 && !values.isEmpty()))
        throw new IllegalArgumentException("NBT list 型別無效");
      for (Object value : values)
        if (Nbt.type(value) != type) throw new IllegalArgumentException("NBT list 混合型別");
    }
  }

  public static int type(Object value) {
    return switch (value) {
      case Byte v -> 1;
      case Short v -> 2;
      case Integer v -> 3;
      case Long v -> 4;
      case Float v -> 5;
      case Double v -> 6;
      case byte[] v -> 7;
      case String v -> 8;
      case ListTag v -> 9;
      case Compound v -> 10;
      case int[] v -> 11;
      case long[] v -> 12;
      default -> throw new IllegalArgumentException("不支援的 NBT 型別：" + value);
    };
  }

  public static Compound read(byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("NBT 超過 32 MiB");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readUnsignedByte() != 10) throw new IOException("NBT 根節點必須是 compound");
      in.readUTF();
      var result = (Compound) read(in, 10, 0, new int[1]);
      if (in.available() != 0) throw new IOException("NBT 有多餘位元組");
      return result;
    }
  }

  private static int length(DataInputStream in, int width) throws IOException {
    int n = in.readInt();
    if (n < 0 || (long) n * width > in.available()) throw new IOException("NBT 長度無效：" + n);
    return n;
  }

  private static Object read(DataInputStream in, int type, int depth, int[] nodes)
      throws IOException {
    if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) throw new IOException("NBT 深度或節點數超過限制");
    return switch (type) {
      case 1 -> in.readByte();
      case 2 -> in.readShort();
      case 3 -> in.readInt();
      case 4 -> in.readLong();
      case 5 -> in.readFloat();
      case 6 -> in.readDouble();
      case 8 -> in.readUTF();
      case 7 -> {
        byte[] a = new byte[length(in, 1)];
        in.readFully(a);
        yield a;
      }
      case 11 -> {
        int[] a = new int[length(in, 4)];
        for (int i = 0; i < a.length; i++) a[i] = in.readInt();
        yield a;
      }
      case 12 -> {
        long[] a = new long[length(in, 8)];
        for (int i = 0; i < a.length; i++) a[i] = in.readLong();
        yield a;
      }
      case 9 -> {
        int element = in.readUnsignedByte(), n = in.readInt();
        if (element > 12 || n < 0 || n > in.available() || (element == 0 && n != 0))
          throw new IOException("NBT list 長度或型別無效");
        var values = new ArrayList<Object>(n);
        for (int i = 0; i < n; i++) values.add(read(in, element, depth + 1, nodes));
        yield new ListTag(element, values);
      }
      case 10 -> {
        var c = new Compound();
        int t;
        while ((t = in.readUnsignedByte()) != 0) {
          String key = in.readUTF();
          if (c.put(key, read(in, t, depth + 1, nodes)) != null)
            throw new IOException("NBT 重複鍵：" + key);
        }
        yield c;
      }
      default -> throw new IOException("NBT tag 無效：" + type);
    };
  }

  public static byte[] write(Compound compound) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeByte(10);
      out.writeUTF("");
      write(out, compound);
      out.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(DataOutput out, Object value) throws IOException {
    switch (value) {
      case Byte v -> out.writeByte(v);
      case Short v -> out.writeShort(v);
      case Integer v -> out.writeInt(v);
      case Long v -> out.writeLong(v);
      case Float v -> out.writeFloat(v);
      case Double v -> out.writeDouble(v);
      case String v -> out.writeUTF(v);
      case byte[] v -> {
        out.writeInt(v.length);
        out.write(v);
      }
      case int[] v -> {
        out.writeInt(v.length);
        for (int x : v) out.writeInt(x);
      }
      case long[] v -> {
        out.writeInt(v.length);
        for (long x : v) out.writeLong(x);
      }
      case ListTag v -> {
        out.writeByte(v.values.isEmpty() ? 0 : v.type);
        out.writeInt(v.values.size());
        for (Object x : v.values) write(out, x);
      }
      case Compound v -> {
        for (var e : v.entrySet()) {
          out.writeByte(type(e.getValue()));
          out.writeUTF(e.getKey());
          write(out, e.getValue());
        }
        out.writeByte(0);
      }
      default -> throw new IOException("NBT 型別無效");
    }
  }

  public static Object copy(Object value) {
    return switch (value) {
      case Compound c -> {
        var r = new Compound();
        c.forEach((k, v) -> r.put(k, copy(v)));
        yield r;
      }
      case ListTag l -> new ListTag(l.type, l.values.stream().map(Nbt::copy).toList());
      case byte[] a -> a.clone();
      case int[] a -> a.clone();
      case long[] a -> a.clone();
      default -> value;
    };
  }

  public static Compound copy(Compound value) {
    return (Compound) copy((Object) value);
  }
}
