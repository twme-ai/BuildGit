package dev.twme.buildgit.server;

import dev.twme.buildgit.core.backup.S3;
import dev.twme.buildgit.core.repo.Repository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.level.ServerLevel;

/**
 * 世界資料夾的備份（設計 §2.6）：暫停自動存檔、把世界寫回磁碟、在本機複製有變動的檔案、恢復存檔，再於背景上傳。
 * 只處理上次之後有玩家進去過的世界資料夾；其他沿用上一份快照。
 *
 * <p>快照清單 {@code snapshots/<時間>.yml} 的 {@code files} 每行是「SHA-256 大小 版本 路徑」（版本只有 versioned 才有，
 * 其他為 {@code -}；路徑相對伺服器根目錄，可能有空白所以放最後）。檔案放在哪裡由 {@code storage} 決定：
 * folders 是 {@code <時間>/<路徑>}、hashed 是 {@code files/<雜湊>}、versioned 是 {@code current/<路徑>} 的那個版本。
 */
final class WorldBackup {
  private WorldBackup() {}

  /** 一個檔案：雜湊、大小、版本（沒有時為 null）、路徑。 */
  record Entry(String sha, long size, String version, String path) {
    static Entry parse(String line) {
      var p = line.split(" ", 4);
      return new Entry(p[0], Long.parseLong(p[1]), p[2].equals("-") ? null : p[2], p[3]);
    }

    @Override
    public String toString() {
      return sha + " " + size + " " + (version == null ? "-" : version) + " " + path;
    }
  }

  static List<Entry> entries(Yaml manifest) {
    var result = new ArrayList<Entry>();
    for (var line : manifest.getStringList("files")) result.add(Entry.parse(line));
    return result;
  }

  /** 檔案在目的地的鍵。 */
  static String key(String prefix, String storage, String snapshot, Entry e) {
    return switch (storage) {
      case "folders" -> prefix + snapshot + "/" + e.path();
      case "versioned" -> prefix + "current/" + e.path();
      default -> prefix + "files/" + e.sha();
    };
  }

  /** {@code auto} 依目的地決定：本機用 folders；S3 的 bucket 有開版本控制時用 versioned，沒有時用 hashed。 */
  static String storage(Backup.Job job) throws IOException {
    if (job.storage.equals("auto")) return job.store instanceof S3 s3 ? (s3.versioning() ? "versioned" : "hashed") : "folders";
    if (job.storage.equals("versioned") && !(job.store instanceof S3 s3 && s3.versioning()))
      throw new Workspace.Refused("storage 是 versioned，但 bucket 沒有開啟版本控制");
    return job.storage;
  }

  /** 準備時在主執行緒取得的：要備份的世界資料夾、要排除的資料夾、原本的自動存檔設定、是否提示了玩家。 */
  private record Prep(Set<String> roots, Set<Path> excluded, Map<ServerLevel, Boolean> noSave, boolean announced) {}

  static Backup.Result run(BuildGit bg, Backup backup, Backup.Job job, long previousRun, ExecutorService pool) throws Exception {
    var platform = bg.platform();
    var server = platform.server().getServerDirectory().toAbsolutePath().normalize();
    var state = job.state;
    var staging = state.resolve("staging");
    Restore.delete(staging);
    Files.createDirectories(staging);
    var prev = Yaml.load(state.resolve("last.yml"));
    String prevName = prev.getString("name", null), prevStorage = prev.getString("storage", "");
    var prevEntries = new LinkedHashMap<String, Entry>();
    for (var e : entries(prev)) prevEntries.put(e.path(), e);
    var index = new Properties();
    var indexFile = state.resolve("files.properties");
    if (Files.exists(indexFile)) try (var in = Files.newBufferedReader(indexFile)) {
      index.load(in);
    }
    String storage = storage(job);
    boolean sameStorage = storage.equals(prevStorage);
    var matchers = new ArrayList<PathMatcher>();
    for (var g : job.exclude) matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g));

    // 1. 主執行緒：找出世界資料夾、提示玩家、暫停自動存檔並寫回磁碟
    var prepared = onMain(bg, () -> {
      backup.observe(); // 現在有玩家的世界也算進去過
      var roots = new TreeSet<String>();
      for (var level : platform.server().getAllLevels()) {
        var r = backup.root(level);
        if (r != null) roots.add(r);
      }
      var excluded = new HashSet<Path>();
      for (var name : platform.levelFolders()) if (Workspace.ownWorldName(name)) excluded.add(platform.levelFolder(name).toAbsolutePath().normalize());
      excluded.add(Restore.staging(platform.repoDir()).toAbsolutePath().normalize());
      boolean announce = job.announce && !platform.server().getPlayerList().getPlayers().isEmpty();
      if (announce) broadcast(bg, "backup-world-started");
      if (bg.workspace().isOpen()) bg.workspace().flushJournal();
      var noSave = new HashMap<ServerLevel, Boolean>();
      for (var level : platform.server().getAllLevels()) {
        noSave.put(level, level.noSave);
        level.noSave = true;
      }
      platform.server().saveEverything(true, true, true);
      return new Prep(roots, excluded, noSave, announce);
    });
    Prep prep;
    try {
      prep = prepared.get(10, TimeUnit.MINUTES);
    } catch (ExecutionException e) {
      throw e.getCause() instanceof Exception x ? x : e;
    } catch (InterruptedException | TimeoutException e) {
      // 被中斷（例如 /bg reload）時主執行緒的準備可能還是會做完：之後一定要恢復自動存檔
      prepared.thenAccept(p -> platform.sync(() -> p.noSave().forEach((level, v) -> level.noSave = v)));
      throw e;
    }

    // 2. 背景：有變動的檔案複製到暫存區（這段期間不會自動存檔）
    var kept = new ArrayList<Entry>();
    var changed = new ArrayList<String>(); // 路徑
    var skipped = new ArrayList<String>();
    var worlds = new TreeSet<String>(prep.roots());
    for (var w : prev.getStringList("worlds")) if (!worlds.contains(w) && Files.isDirectory(server.resolve(w))) worlds.add(w);
    try {
      for (var root : worlds) {
        Long visited = backup.visits().get(root);
        var before = prevEntries.values().stream().filter(e -> e.path().startsWith(root + "/")).toList();
        if (visited == null && before.isEmpty()) {
          skipped.add(root);
          continue;
        }
        if ((visited == null || visited <= previousRun) && !before.isEmpty() && sameStorage) {
          kept.addAll(before);
          continue;
        }
        scan(server, server.resolve(root), p -> true, prep.excluded(), matchers, index, prevEntries, sameStorage, staging, kept, changed);
      }
      // 版本庫的狀態（分支指向、還沒存檔的標記）：還原時世界和版本紀錄對得上
      var repoDir = platform.repoDir().toAbsolutePath().normalize();
      if (bg.workspace().isOpen() && repoDir.startsWith(server)) {
        var main = repoDir.resolve("worktree").resolve(Repository.MAIN);
        var refs = repoDir.resolve("refs");
        scan(server, repoDir, p -> p.startsWith(refs) || p.startsWith(main) || p.getParent().equals(repoDir)
            && List.of("branches.yml", "data-version", "players.yml").contains(p.getFileName().toString()),
            Set.of(), List.of(), index, prevEntries, sameStorage, staging, kept, changed);
      }
    } finally {
      // 不等主執行緒：這裡被中斷時也要排得進去
      platform.sync(() -> prep.noSave().forEach((level, v) -> level.noSave = v));
    }

    // 3. 背景：上傳
    var store = job.store;
    var prefix = job.prefix;
    var paths = new TreeSet<String>(changed);
    kept.forEach(e -> paths.add(e.path()));
    if (changed.isEmpty() && prevName != null && paths.equals(prevEntries.keySet())) {
      if (prep.announced()) platform.sync(() -> broadcast(bg, "backup-world-finished"));
      return new Backup.Result(job, null, 0, 0, kept.size(), skipped);
    }
    String name = Backup.NAME.format(Instant.now());
    var remote = storage.equals("hashed") ? Collections.synchronizedSet(new HashSet<>(store.list(prefix + "files/"))) : Set.<String>of();
    var futures = new ArrayList<Future<Entry>>();
    for (var path : changed)
      futures.add(pool.submit(() -> {
        var file = staging.resolve(path);
        var e = new Entry(S3.hex(S3.sha256(file)), Files.size(file), null, path);
        var key = key(prefix, storage, name, e);
        String version = null;
        if (!storage.equals("hashed") || remote.add(key)) version = store.putFile(key, file);
        return new Entry(e.sha(), e.size(), storage.equals("versioned") ? version : null, path);
      }));
    if (storage.equals("folders"))
      for (var e : kept) futures.add(pool.submit(() -> {
        store.copy(key(prefix, storage, prevName, e), key(prefix, storage, name, e));
        return e;
      }));
    var files = new TreeMap<String, Entry>();
    for (var e : kept) files.put(e.path(), e);
    for (var f : futures) {
      var e = f.get();
      files.put(e.path(), e);
    }
    if (storage.equals("versioned") && sameStorage)
      for (var gone : prevEntries.keySet()) if (!files.containsKey(gone)) store.delete(prefix + "current/" + gone);

    // 4. 清單：最後寫，有清單的快照才算完整
    var manifest = new Yaml();
    manifest.set("name", name);
    manifest.set("time", System.currentTimeMillis());
    manifest.set("minecraft-data-version", bg.workspace().upgrader().current());
    manifest.set("storage", storage);
    manifest.set("worlds", new ArrayList<>(worlds));
    manifest.set("skipped", skipped);
    if (bg.workspace().isOpen()) {
      var refs = new TreeMap<String, Object>();
      bg.workspace().repo().refs().list().forEach((k, v) -> refs.put(k, v.hex()));
      manifest.set("refs", refs);
      manifest.set("repo", server.relativize(platform.repoDir().toAbsolutePath().normalize()).toString().replace('\\', '/'));
    }
    manifest.set("files", files.values().stream().map(Entry::toString).toList());
    var tmp = state.resolve("last.yml.tmp");
    manifest.save(tmp);
    store.putFile(prefix + "snapshots/" + name + ".yml", tmp);
    Files.move(tmp, state.resolve("last.yml"), StandardCopyOption.REPLACE_EXISTING);
    Backup.saveIndex(index, indexFile, "BuildGit world backup: path -> size mtime");
    Restore.delete(staging);

    prune(job, storage);
    if (prep.announced()) platform.sync(() -> broadcast(bg, "backup-world-finished"));
    return new Backup.Result(job, name, 0, changed.size(), kept.size(), skipped);
  }

  /** 走訪一個資料夾：和上次相同（大小、修改時間）的沿用，有變動的複製到暫存區。 */
  private static void scan(Path server, Path base, java.util.function.Predicate<Path> include, Set<Path> excluded, List<PathMatcher> matchers,
      Properties index, Map<String, Entry> prevEntries, boolean sameStorage, Path staging, List<Entry> kept, List<String> changed) throws IOException {
    if (!Files.isDirectory(base)) return;
    Files.walkFileTree(base, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        // 還原時改名留著的舊資料（設計 §2.5）不備份
        return excluded.contains(dir.toAbsolutePath().normalize()) || dir.getFileName().toString().contains(".before-restore-")
            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        var abs = file.toAbsolutePath().normalize();
        String rel = server.relativize(abs).toString().replace('\\', '/');
        String fileName = file.getFileName().toString();
        if (!attrs.isRegularFile() || fileName.equals("session.lock") || fileName.contains(".before-restore-") || !include.test(abs))
          return FileVisitResult.CONTINUE;
        for (var m : matchers) if (m.matches(Path.of(rel))) return FileVisitResult.CONTINUE;
        String stamp = stamp(attrs);
        var before = prevEntries.get(rel);
        if (sameStorage && before != null && stamp.equals(index.getProperty(rel))) {
          kept.add(before);
          return FileVisitResult.CONTINUE;
        }
        // 暫停存檔時伺服器仍可能寫檔（例如卸載 chunk）：複製前後不同就重來
        var target = staging.resolve(rel);
        Files.createDirectories(target.getParent());
        for (int i = 0; i < 3; i++) {
          Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
          String after = stamp(Files.readAttributes(file, BasicFileAttributes.class));
          if (after.equals(stamp)) break;
          stamp = after;
        }
        index.setProperty(rel, stamp);
        changed.add(rel);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) {
        return FileVisitResult.CONTINUE; // 例如複製途中被刪掉的檔案
      }
    });
  }

  private static String stamp(BasicFileAttributes attrs) {
    return attrs.size() + " " + attrs.lastModifiedTime().toMillis();
  }

  /** 只留最近 keep 份快照，刪掉只有被刪的快照用到的檔案。 */
  private static void prune(Backup.Job job, String storage) throws IOException {
    var store = job.store;
    var prefix = job.prefix;
    var names = Restore.snapshots(store, prefix);
    var manifests = new HashMap<String, Yaml>();
    for (var n : names) {
      var bytes = store.get(prefix + "snapshots/" + n + ".yml");
      if (bytes == null) throw new IOException("讀不到快照 " + n + "，這次不清理");
      manifests.put(n, Yaml.load(new ByteArrayInputStream(bytes)));
    }
    var drop = names.size() > job.keep ? names.subList(0, names.size() - job.keep) : List.<String>of();
    var keep = names.subList(drop.size(), names.size());
    var usedHashes = new HashSet<String>();
    var usedVersions = new HashSet<String>();
    for (var n : keep)
      for (var e : entries(manifests.get(n))) {
        usedHashes.add(e.sha());
        if (e.version() != null) usedVersions.add(e.version());
      }
    for (var n : drop) Backup.purge(store, prefix + "snapshots/" + n + ".yml");
    // versioned：被刪的快照用到、留下的快照沒用到的版本
    if (store instanceof S3 s3)
      for (var n : drop)
        for (var e : entries(manifests.get(n)))
          if (e.version() != null && !usedVersions.contains(e.version())) s3.deleteVersion(prefix + "current/" + e.path(), e.version());
    // hashed：沒有快照用到的檔案
    for (var key : store.list(prefix + "files/"))
      if (!usedHashes.contains(key.substring(key.lastIndexOf('/') + 1))) Backup.purge(store, key);
    // folders：沒有清單的快照資料夾（被刪的，或中途失敗的）
    if (storage.equals("folders")) {
      var kept = new HashSet<>(keep);
      for (var key : store.list(prefix)) {
        var first = key.substring(prefix.length()).split("/", 2)[0];
        if (first.matches("\\d{8}-\\d{6}Z") && !kept.contains(first)) Backup.purge(store, key);
      }
    }
  }

  private static void broadcast(BuildGit bg, String key) {
    for (var p : bg.platform().server().getPlayerList().getPlayers()) bg.messages().send(bg.platform().sender(p), key);
  }

  /** 排到主執行緒執行。 */
  private static <T> CompletableFuture<T> onMain(BuildGit bg, Callable<T> task) {
    var f = new CompletableFuture<T>();
    bg.platform().sync(() -> {
      try {
        f.complete(task.call());
      } catch (Throwable t) {
        f.completeExceptionally(t);
      }
    });
    return f;
  }
}
