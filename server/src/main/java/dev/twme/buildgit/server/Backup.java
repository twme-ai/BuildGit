package dev.twme.buildgit.server;

import dev.twme.buildgit.core.backup.ObjectWalk;
import dev.twme.buildgit.core.backup.S3;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * 異地備份（設計 §2.5）：每隔一段時間檢查，有變化時把版本庫增量上傳到 S3 相容的儲存，寫一份快照。
 * 設定在 {@code s3.yml}（有金鑰，和會跟著外掛更新的 config.yml 分開）。本機的上傳紀錄在 {@code buildgit/backup/}。
 */
final class Backup {
  static final String FILE = "s3.yml";
  private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final int PARALLEL = 8;

  record Config(boolean enabled, S3.Config s3, String prefix, long intervalMinutes, int keep) {
    /** 讀取 {@code dir/s3.yml}；不存在時寫入內建的範本。之後不再改動這個檔案。 */
    static Config load(Path dir, java.util.logging.Logger log) {
      var file = dir.resolve(FILE);
      if (!Files.exists(file))
        try (InputStream in = Backup.class.getResourceAsStream("/" + FILE)) {
          Files.createDirectories(dir);
          if (in != null) Files.write(file, in.readAllBytes());
        } catch (IOException e) {
          log.warning("無法寫入 " + file + "：" + e.getMessage());
        }
      var y = Yaml.load(file);
      var prefix = y.getString("prefix", "buildgit").replaceAll("^/+|/+$", "");
      return new Config(y.getBoolean("enabled", false),
          new S3.Config(y.getString("endpoint", ""), y.getString("region", "us-east-1"), y.getString("bucket", ""),
              y.getString("access-key", ""), y.getString("secret-key", ""), y.getBoolean("path-style", false)),
          prefix.isEmpty() ? "" : prefix + "/", Math.max(1, y.getLong("interval-minutes", 60)), Math.max(1, y.getInt("keep", 48)));
    }

    /** 設定不完整時的說明；完整時為 null。 */
    String problem() {
      if (s3.endpoint().isBlank()) return "endpoint";
      if (s3.bucket().isBlank()) return "bucket";
      if (s3.accessKey().isBlank() || s3.secretKey().isBlank()) return "access-key / secret-key";
      return null;
    }

    String target() {
      return s3.endpoint() + " " + s3.bucket() + "/" + prefix;
    }
  }

  /** 一次備份的結果：快照名稱（沒有變化時為 null）、上傳的物件數、上傳的檔案數。 */
  record Result(String snapshot, int objects, int files) {}

  private final BuildGit bg;
  private Config config;
  private S3 s3;
  private ScheduledExecutorService worker;
  private ExecutorService uploads;
  private volatile String lastError;
  /** 連續失敗的次數；成功時歸零。 */
  private int failures;
  private volatile List<String> incomplete = List.of();

  Backup(BuildGit bg) {
    this.bg = bg;
  }

  Config config() {
    return config;
  }

  String lastError() {
    return lastError;
  }

  List<String> incomplete() {
    return incomplete;
  }

  /** 讀設定；啟用時排定定期檢查（啟動 5 分鐘後第一次）。{@code /bg reload} 也呼叫。 */
  synchronized void start() {
    stop();
    var log = bg.platform().log();
    config = Config.load(bg.platform().configDir(), log);
    if (!config.enabled()) return;
    if (config.problem() != null) {
      log.warning(FILE + " 啟用了異地備份，但沒有填 " + config.problem() + "；不備份");
      return;
    }
    s3 = new S3(config.s3());
    worker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "BuildGit-backup"));
    uploads = Executors.newFixedThreadPool(PARALLEL, r -> daemon(r, "BuildGit-backup-upload"));
    worker.scheduleWithFixedDelay(() -> {
      try {
        run();
      } catch (Exception e) {
        // run 已經記下
      }
    }, 5 * 60, config.intervalMinutes() * 60, TimeUnit.SECONDS);
  }

  synchronized void stop() {
    if (worker != null) worker.shutdownNow();
    if (uploads != null) uploads.shutdownNow();
    worker = null;
    uploads = null;
    s3 = null;
  }

  boolean running() {
    return worker != null;
  }

  private static Thread daemon(Runnable r, String name) {
    var t = new Thread(r, name);
    t.setDaemon(true);
    return t;
  }

  /** 排在背景立刻檢查一次。 */
  synchronized CompletableFuture<Result> now() {
    var result = new CompletableFuture<Result>();
    if (worker == null) result.completeExceptionally(new Workspace.Refused("異地備份沒有啟用"));
    else worker.execute(() -> {
      try {
        result.complete(run());
      } catch (Exception e) {
        result.completeExceptionally(e);
      }
    });
    return result;
  }

  /** 遠端的快照名稱，舊的在前。 */
  synchronized CompletableFuture<List<String>> snapshots() {
    var s = s3;
    if (s == null) return CompletableFuture.failedFuture(new Workspace.Refused("異地備份沒有啟用"));
    return CompletableFuture.supplyAsync(() -> {
      try {
        return names(s.list(config.prefix() + "snapshots/"));
      } catch (IOException e) {
        throw new CompletionException(e);
      }
    }, uploads);
  }

  private List<String> names(List<String> keys) {
    var result = new ArrayList<String>();
    for (var k : keys) if (k.endsWith(".yml")) result.add(k.substring(k.lastIndexOf('/') + 1, k.length() - 4));
    Collections.sort(result);
    return result;
  }

  /** 在背景下載快照準備還原（{@link Restore}）；和備份排在同一個執行緒，不會同時進行。 */
  synchronized CompletableFuture<Restore.Result> restore(String name) {
    var result = new CompletableFuture<Restore.Result>();
    if (worker == null) result.completeExceptionally(new Workspace.Refused("異地備份沒有啟用"));
    else {
      var s = s3;
      var prefix = config.prefix();
      var pool = uploads;
      worker.execute(() -> {
        try {
          result.complete(Restore.download(bg, s, prefix, pool, name));
        } catch (Exception e) {
          bg.platform().log().log(Level.WARNING, "下載備份失敗：" + Workspace.unwrap(e).getMessage(), e instanceof IOException ? null : e);
          result.completeExceptionally(e);
        }
      });
    }
    return result;
  }

  /** 在背景從備份取回一個分支的物件（{@link Restore#fetchBranch}）。 */
  synchronized CompletableFuture<Restore.Fetched> fetch(String branch) {
    var result = new CompletableFuture<Restore.Fetched>();
    if (worker == null) result.completeExceptionally(new Workspace.Refused("異地備份沒有啟用"));
    else {
      var s = s3;
      var prefix = config.prefix();
      var pool = uploads;
      worker.execute(() -> {
        try {
          result.complete(Restore.fetchBranch(bg, s, prefix, pool, branch));
        } catch (Exception e) {
          result.completeExceptionally(e);
        }
      });
    }
    return result;
  }

  /** 上次成功備份的時間（毫秒）；沒有時為 0。 */
  long lastTime() {
    var ws = bg.workspace();
    if (!ws.isOpen()) return 0;
    return Yaml.load(ws.dir().resolve("backup").resolve("last.yml")).getLong("time", 0);
  }

  // ---- 一次備份（只在 worker 執行緒） ----

  private Result run() throws Exception {
    try {
      var result = backup();
      lastError = null;
      failures = 0;
      if (result.snapshot() != null)
        bg.platform().log().info("異地備份完成：" + result.snapshot() + "（" + result.objects() + " 個物件、" + result.files() + " 個檔案）");
      return result;
    } catch (Exception e) {
      lastError = String.valueOf(Workspace.unwrap(e).getMessage());
      bg.platform().log().log(Level.WARNING, "異地備份失敗：" + lastError, e instanceof IOException ? null : e);
      // 連續失敗 3 次起，每次都提醒線上的管理員（只看主控台的話不會發現）
      if (++failures >= 3) {
        int count = failures;
        String error = lastError;
        bg.platform().sync(() -> {
          for (var p : bg.platform().server().getPlayerList().getPlayers())
            if (bg.platform().hasPermission(p, "buildgit.backup")) bg.messages().send(bg.platform().sender(p), "backup-failing", "count", count, "error", error);
        });
      }
      throw e;
    }
  }

  private Result backup() throws Exception {
    var ws = bg.workspace();
    if (!ws.isOpen()) throw new Workspace.Refused("還沒有開始版本控制");
    var repo = ws.repo();
    var state = ws.dir().resolve("backup");
    Files.createDirectories(state);
    var last = Yaml.load(state.resolve("last.yml"));
    String prefix = config.prefix();

    // 1. 先記下 refs：之後讀到的起點世界只會比它新
    var refs = repo.refs().list();
    var refMap = new TreeMap<String, Object>();
    refs.forEach((k, v) -> refMap.put(k, v.hex()));
    String branches = read(ws.dir().resolve("branches.yml")), dataVersion = read(ws.dir().resolve("data-version"));

    // 2. 起點世界的檔案
    var index = new Properties();
    var indexFile = state.resolve("files.properties");
    if (Files.exists(indexFile)) try (var in = Files.newBufferedReader(indexFile)) {
      index.load(in);
    }
    var remoteFiles = new HashSet<>(s3.list(prefix + "files/"));
    var dims = new ArrayList<Map<String, Object>>();
    var missing = new ArrayList<String>();
    int files = 0;
    var tmp = state.resolve("upload.tmp");
    for (var dim : ws.trackedDimensions()) {
      var dir = ws.genesisDir(dim);
      if (ws.copying(dim) || GenesisCopy.unfinished(dir)) {
        missing.add(dim);
        continue;
      }
      boolean lazy = ws.lazySource(dim) != null;
      if (lazy) missing.add(dim);
      var list = new ArrayList<String>();
      for (var sub : List.of("region", "entities")) {
        var d = dir.resolve(sub);
        if (!Files.isDirectory(d)) continue;
        List<Path> mca;
        try (var s = Files.list(d)) {
          mca = s.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().toList();
        }
        for (var file : mca) {
          String key = dim + "/" + sub + "/" + file.getFileName();
          // 在寫入執行緒上：沒變就沿用記下的雜湊（{大小 時間, 雜湊}），有變就複製一份（雜湊為 null）
          String[] seen = ws.onGenesisThread(() -> {
            var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            String stamp = attrs.size() + " " + attrs.lastModifiedTime().toMillis();
            var v = index.getProperty(key);
            if (v != null && v.startsWith(stamp + " ")) return new String[] {stamp, v.substring(stamp.length() + 1)};
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            return new String[] {stamp, null};
          }).get();
          String known = seen[1];
          if (known == null) {
            var bytes = Files.readAllBytes(tmp);
            known = S3.hex(S3.sha256(bytes));
            if (remoteFiles.add(prefix + "files/" + known)) {
              s3.put(prefix + "files/" + known, bytes);
              files++;
            }
            index.setProperty(key, seen[0] + " " + known);
          }
          list.add(known + " " + sub + "/" + file.getFileName());
        }
      }
      var entry = new LinkedHashMap<String, Object>();
      entry.put("id", dim);
      entry.put("lazy", lazy);
      entry.put("files", list);
      dims.add(entry);
    }
    Files.deleteIfExists(tmp);
    saveIndex(index, indexFile);
    incomplete = List.copyOf(missing);

    // 3. 物件
    var commitsFile = state.resolve("commits");
    var uploaded = new HashSet<ObjectId>();
    if (Files.exists(commitsFile)) for (var line : Files.readAllLines(commitsFile)) if (!line.isBlank()) uploaded.add(ObjectId.parse(line.strip()));
    var snapshots = names(s3.list(prefix + "snapshots/"));
    // 本機的紀錄遺失、遠端卻已有備份，或上次上傳到一半中斷：先列出遠端已有的物件，不重傳
    var uploading = state.resolve("uploading");
    var remote = (!Files.exists(commitsFile) && !snapshots.isEmpty()) || Files.exists(uploading)
        ? new HashSet<>(s3.list(prefix + "objects/")) : Set.<String>of();
    var walk = ObjectWalk.collect(repo, refs.values(), uploaded);

    boolean changed = !walk.commits().isEmpty() || !refMap.equals(last.get("refs")) || !branches.equals(last.getString("branches", ""))
        || !dataVersion.equals(last.getString("data-version", "")) || !content(dims).equals(content(last.get("dimensions")));
    if (!changed) return new Result(null, 0, 0);

    Files.writeString(uploading, "");
    var objects = repo.overlays().objects();
    var futures = new ArrayList<Future<Boolean>>();
    for (var id : walk.objects()) {
      String hex = id.hex(), key = prefix + "objects/" + hex.substring(0, 2) + "/" + hex.substring(2);
      futures.add(uploads.submit(() -> {
        if (remote.contains(key)) return false;
        s3.put(key, Files.readAllBytes(objects.file(id)));
        return true;
      }));
    }
    int sent = 0;
    for (var f : futures) if (f.get()) sent++;

    // level.dat 與種子設定：一直在變（時間、天氣），只在有其他變化時才帶上
    var root = bg.platform().server().getServerDirectory().toAbsolutePath().normalize();
    for (var entry : dims) {
      String dim = (String) entry.get("id");
      var levelDir = ws.mainLevelDirectory(dim);
      if (levelDir != null) {
        var list = new ArrayList<String>();
        for (var f : levelFiles(levelDir)) {
          var bytes = Files.readAllBytes(f);
          String hash = S3.hex(S3.sha256(bytes));
          if (remoteFiles.add(prefix + "files/" + hash)) {
            s3.put(prefix + "files/" + hash, bytes);
            files++;
          }
          list.add(hash + " " + relative(root, f));
        }
        entry.put("world", relative(root, levelDir));
        entry.put("seed", ws.mainSeed(dim));
        entry.put("level-files", list);
      } else {
        // 世界沒載入：沿用上一份快照記的
        if (last.get("dimensions") instanceof List<?> old)
          for (var o : old)
            if (o instanceof Map<?, ?> m && dim.equals(m.get("id")))
              for (var k : List.of("world", "seed", "level-files", "level-dat")) if (m.get(k) != null) entry.put(k, m.get(k));
      }
    }

    // 4. 最後寫快照
    long now = System.currentTimeMillis();
    String name = NAME.format(Instant.ofEpochMilli(now));
    var snapshot = new Yaml();
    snapshot.set("time", now);
    snapshot.set("minecraft-data-version", bg.workspace().upgrader().current());
    snapshot.set("refs", refMap);
    snapshot.set("branches", branches);
    snapshot.set("data-version", dataVersion);
    snapshot.set("dimensions", dims);
    var file = state.resolve("snapshot.tmp");
    snapshot.save(file);
    s3.put(prefix + "snapshots/" + name + ".yml", Files.readAllBytes(file));
    Files.move(file, state.resolve("last.yml"), StandardCopyOption.REPLACE_EXISTING);
    var lines = new ArrayList<String>();
    for (var id : walk.commits()) lines.add(id.hex());
    Files.write(commitsFile, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    Files.delete(uploading);

    snapshots.add(name);
    prune(snapshots);
    return new Result(name, sent, files);
  }

  /** 只留最近 keep 份快照；之後刪掉沒有快照用到的檔案。物件不刪。 */
  private void prune(List<String> snapshots) throws IOException {
    if (snapshots.size() <= config.keep()) return;
    String prefix = config.prefix();
    for (var old : snapshots.subList(0, snapshots.size() - config.keep())) s3.delete(prefix + "snapshots/" + old + ".yml");
    var used = new HashSet<String>();
    for (var name : snapshots.subList(snapshots.size() - config.keep(), snapshots.size())) {
      var bytes = s3.get(prefix + "snapshots/" + name + ".yml");
      if (bytes == null) throw new IOException("讀不到快照 " + name + "，這次不清理檔案");
      var y = Yaml.load(new java.io.ByteArrayInputStream(bytes));
      if (y.get("dimensions") instanceof List<?> dims)
        for (var d : dims)
          if (d instanceof Map<?, ?> m) {
            if (m.get("files") instanceof List<?> files) for (var f : files) used.add(String.valueOf(f).split(" ")[0]);
            if (m.get("level-files") instanceof List<?> files) for (var f : files) used.add(String.valueOf(f).split(" ")[0]);
            if (m.get("level-dat") != null) used.add(String.valueOf(m.get("level-dat")).split(" ")[0]);
          }
    }
    for (var key : s3.list(prefix + "files/"))
      if (!used.contains(key.substring(key.lastIndexOf('/') + 1))) s3.delete(key);
  }

  /** 判斷有沒有變化用的部分：維度與起點世界的檔案（level.dat 一直在變，不算）。 */
  private static List<Object> content(Object dims) {
    var result = new ArrayList<Object>();
    if (dims instanceof List<?> list)
      for (var d : list) if (d instanceof Map<?, ?> m) result.add(List.of(String.valueOf(m.get("id")), String.valueOf(m.get("files"))));
    return result;
  }

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  private static void saveIndex(Properties index, Path file) throws IOException {
    var tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try (var out = Files.newBufferedWriter(tmp)) {
      index.store(out, "BuildGit backup: genesis file -> size mtime sha256");
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
  }

  private static String relative(Path root, Path p) {
    return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  /**
   * 世界的 level.dat 與種子設定：從存檔目錄往上找到 level.dat 為止（地獄、終界的存檔目錄在世界資料夾裡面）。
   * 26.x 起種子另外存在 {@code data/minecraft/world_gen_settings.dat}（原版在世界資料夾，Paper 在每個維度的目錄）。
   */
  private static List<Path> levelFiles(Path dir) {
    var result = new ArrayList<Path>();
    var d = dir;
    for (int i = 0; i < 4 && d != null; i++, d = d.getParent()) {
      var gen = d.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
      if (Files.isRegularFile(gen)) result.add(gen);
      if (Files.isRegularFile(d.resolve("level.dat"))) {
        result.add(d.resolve("level.dat"));
        break;
      }
    }
    return result;
  }
}
