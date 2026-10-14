package dev.twme.buildgit.core.store;

/** 物件種類；數值寫在物件開頭並參與雜湊，不可更改。 */
public enum ObjectType {
  COMMIT(1),
  TREE(2),
  SECTION(3),
  ENTITIES(4),
  CHANGESET(5);

  public final int code;

  ObjectType(int code) {
    this.code = code;
  }

  public static ObjectType of(int code) {
    for (var t : values()) if (t.code == code) return t;
    throw new IllegalArgumentException("未知的物件種類：" + code);
  }
}
