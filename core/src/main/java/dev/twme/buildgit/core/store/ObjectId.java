package dev.twme.buildgit.core.store;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** 物件的 SHA-256 雜湊。 */
public final class ObjectId implements Comparable<ObjectId> {
  public static final int LENGTH = 32;
  private final byte[] bytes;

  private ObjectId(byte[] bytes) {
    if (bytes.length != LENGTH) throw new IllegalArgumentException("ObjectId 長度必須是 32 位元組");
    this.bytes = bytes;
  }

  public static ObjectId of(byte[] bytes) {
    return new ObjectId(bytes.clone());
  }

  public static ObjectId parse(String hex) {
    return new ObjectId(HexFormat.of().parseHex(hex));
  }

  static ObjectId hash(byte[] data) {
    try {
      return new ObjectId(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public byte[] bytes() {
    return bytes.clone();
  }

  public String hex() {
    return HexFormat.of().formatHex(bytes);
  }

  /** 顯示用的短雜湊。 */
  public String abbrev() {
    return hex().substring(0, 10);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ObjectId id && Arrays.equals(bytes, id.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public int compareTo(ObjectId o) {
    return Arrays.compareUnsigned(bytes, o.bytes);
  }

  @Override
  public String toString() {
    return hex();
  }
}
