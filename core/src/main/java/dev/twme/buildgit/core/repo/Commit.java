package dev.twme.buildgit.core.repo;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.store.Bin;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.util.*;

/**
 * 提交。
 *
 * @param tree 覆寫表根節點
 * @param parents 父提交；合併提交有兩個，第一個是目標分支
 * @param changeset 相對於第一個父提交的變更清單
 * @param flags {@link #AUTO} 等旗標
 * @param trailers 額外資訊，例如部分併回的來源草稿
 */
public record Commit(
    ObjectId tree,
    List<ObjectId> parents,
    ObjectId changeset,
    Author author,
    long time,
    String message,
    int flags,
    SortedMap<String, String> trailers) {
  private static final int VERSION = 1;

  /** 自動存檔；沒有這個旗標的提交是里程碑。 */
  public static final int AUTO = 1;

  public static final String SOURCE_DRAFT = "Source-Draft";

  public Commit {
    parents = List.copyOf(parents);
    trailers = Collections.unmodifiableSortedMap(new TreeMap<>(trailers));
  }

  public boolean auto() {
    return (flags & AUTO) != 0;
  }

  public boolean merge() {
    return parents.size() > 1;
  }

  public byte[] encode() {
    var out = new Bin.Out().u8(VERSION).id(tree).i32(parents.size());
    parents.forEach(out::id);
    out.id(changeset).uuid(author.uuid()).str(author.name()).i64(time).str(message).i32(flags);
    out.i32(trailers.size());
    trailers.forEach((k, v) -> out.str(k).str(v));
    return out.toByteArray();
  }

  public static Commit decode(byte[] data) throws IOException {
    var in = new Bin.In(data);
    if (in.u8() != VERSION) throw new IOException("不支援的提交版本");
    var tree = in.id();
    int n = in.count();
    var parents = new ArrayList<ObjectId>(n);
    for (int i = 0; i < n; i++) parents.add(in.id());
    var changeset = in.id();
    var author = new Author(in.uuid(), in.str());
    long time = in.i64();
    String message = in.str();
    int flags = in.i32();
    int t = in.count();
    var trailers = new TreeMap<String, String>();
    for (int i = 0; i < t; i++) trailers.put(in.str(), in.str());
    in.end();
    return new Commit(tree, parents, changeset, author, time, message, flags, trailers);
  }
}
