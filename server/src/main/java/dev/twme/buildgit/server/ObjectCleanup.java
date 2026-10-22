package dev.twme.buildgit.server;

import dev.twme.buildgit.core.repo.ObjectGc;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 回收沒人用的物件（{@code /bg disk clean}）：刪掉的建築世界、草稿留下的版本紀錄。
 *
 * <p>起點是所有版本庫的分支、進行中的整合、取出的範圍，版本庫包括伺服器世界、所有建築世界、回收區裡的世界、
 * fork 到一半的世界、還原前留下的 {@code *.before-restore-*} 與還沒換上的還原。好幾個版本庫可能共用一個物件目錄，
 * 所以每個物件目錄都用全部的起點標記（不在這個目錄的起點略過）。修改時間在一天內的物件不刪，保護正在寫入的存檔。
 */
final class ObjectCleanup {
  /** 寬限期：比 {@link dev.twme.buildgit.core.store.ObjectStore#TOUCH_MILLIS} 長很多。 */
  static final long GRACE_MILLIS = 24 * 3600_000L;
  /** 整合（{@code merge/*.yml}）與取出的範圍（{@code sync/**.yml}）記下的提交。 */
  private static final Pattern RECORDED = Pattern.compile("(?m)^\\s*(?:base|ours|theirs|start):\\s*['\"]?([0-9a-f]{64})");

  record Result(int dirs, long deleted, long bytes) {}

  private ObjectCleanup() {}

  /** 要回收的物件目錄與所有版本庫（主執行緒收集，之後在背景執行 {@link #run}）。 */
  record Plan(Set<Path> objectDirs, Set<Path> repos) {}

  static Plan plan(BuildWorlds worlds) {
    var objectDirs = new LinkedHashSet<Path>();
    var repos = new LinkedHashSet<Path>();
    for (var ws : worlds.all()) add(ws.dir(), ws.objectsDir(), objectDirs, repos);
    for (var e : worlds.reserved()) {
      var at = worlds.locations(e);
      add(at[0], at[1], objectDirs, repos);
    }
    for (var d : worlds.trash()) {
      var at = worlds.locations(d.entry());
      for (var m : d.moved())
        if (abs(m.from()).equals(abs(at[0])))
          add(m.to(), abs(at[1]).startsWith(abs(at[0])) ? m.to().resolve(abs(at[0]).relativize(abs(at[1])).toString()) : at[1], objectDirs, repos);
    }
    return new Plan(objectDirs, repos);
  }

  private static void add(Path repo, Path objects, Set<Path> objectDirs, Set<Path> repos) {
    repo = abs(repo);
    objects = abs(objects);
    repos.add(repo);
    objectDirs.add(objects);
    // 還原前留下的與還沒換上的版本庫；物件在版本庫裡面時跟著改名
    var parent = repo.getParent();
    if (parent == null || !Files.isDirectory(parent)) return;
    String name = repo.getFileName().toString();
    try (Stream<Path> s = Files.list(parent)) {
      for (var p : (Iterable<Path>) s::iterator) {
        String n = p.getFileName().toString();
        if (!n.startsWith(name + ".before-restore-") && !n.equals(name + "-restore")) continue;
        repos.add(p);
        if (objects.startsWith(repo)) objectDirs.add(p.resolve(repo.relativize(objects).toString()));
      }
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static Path abs(Path p) {
    return p.toAbsolutePath().normalize();
  }

  /** 背景執行：標記與刪除。{@code log} 收到每個目錄的結果。 */
  static Result run(Plan plan, Consumer<String> log) throws IOException {
    var roots = new LinkedHashSet<ObjectId>();
    for (var repo : plan.repos()) roots(repo, roots);
    long cutoff = System.currentTimeMillis() - GRACE_MILLIS;
    int dirs = 0;
    long deleted = 0, bytes = 0;
    for (var dir : plan.objectDirs()) {
      if (!Files.isDirectory(dir)) continue;
      var store = new dev.twme.buildgit.core.store.ObjectStore(dir);
      var marked = ObjectGc.mark(store, roots);
      var r = ObjectGc.sweep(dir, marked, cutoff);
      log.accept(dir + "：保留 " + r.kept() + " 個，刪除 " + r.deleted() + " 個（" + r.bytes() / 1024 + " KB）");
      dirs++;
      deleted += r.deleted();
      bytes += r.bytes();
    }
    return new Result(dirs, deleted, bytes);
  }

  /** 版本庫記下的提交：分支（含伺服器世界還原前留下的 {@code refs.before-restore-*}），以及整合、取出的範圍記錄。 */
  private static void roots(Path repo, Set<ObjectId> out) throws IOException {
    if (!Files.isDirectory(repo)) return;
    List<Path> refs;
    try (Stream<Path> s = Files.list(repo)) {
      refs = s.filter(p -> p.getFileName().toString().equals("refs") || p.getFileName().toString().startsWith("refs.before-restore-")).toList();
    }
    for (var r : refs) {
      var branches = r.resolve("branches");
      if (!Files.isDirectory(branches)) continue;
      try (Stream<Path> s = Files.list(branches)) {
        for (var f : (Iterable<Path>) s::iterator) {
          String hex = Files.readString(f).strip();
          if (hex.matches("[0-9a-f]{64}")) out.add(ObjectId.parse(hex));
        }
      }
    }
    for (var sub : List.of("merge", "sync")) {
      var dir = repo.resolve(sub);
      if (!Files.isDirectory(dir)) continue;
      try (Stream<Path> s = Files.walk(dir)) {
        for (var f : (Iterable<Path>) s::iterator) {
          if (!f.toString().endsWith(".yml") || !Files.isRegularFile(f)) continue;
          var m = RECORDED.matcher(Files.readString(f));
          while (m.find()) out.add(ObjectId.parse(m.group(1)));
        }
      }
    }
  }
}
