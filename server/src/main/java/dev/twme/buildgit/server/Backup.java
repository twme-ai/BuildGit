package dev.twme.buildgit.server;

import dev.twme.buildgit.core.backup.BackupStore;
import dev.twme.buildgit.core.backup.Cron;
import dev.twme.buildgit.core.backup.LocalStore;
import dev.twme.buildgit.core.backup.ObjectWalk;
import dev.twme.buildgit.core.backup.S3;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;

/**
 * 備份工作（設計 §2.5～§2.7）：每個工作自己決定備份什麼（版本紀錄或世界資料夾）、送到哪裡（S3 或本機資料夾）、
 * 何時（cron、伺服器閒置）與留幾份。連線在 {@code s3.yml}（有金鑰），工作在 {@code backup.yml}。
 * 所有工作排在同一個背景執行緒依序執行。每個工作的本機紀錄在 {@code buildgit/backup/<工作>/}。
 */
final class Backup {
  static final String S3_FILE = "s3.yml", FILE = "backup.yml";
  static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final int PARALLEL = 8;

  enum What { HISTORY, WORLD }

  /** 一個備份工作的設定與執行狀態。 */
  static final class Job {
    final String name, target, prefix, storage;
    final What what;
    final Cron schedule;
    final int idleMinutes, keep;
    final boolean announce;
    final List<String> exclude;
    /** 目的地；沒設定好時為 null，原因在 {@link #problem}。 */
    final BackupStore store;
    final String problem;
    final Path state;
    volatile String lastError;
    volatile List<String> incomplete = List.of();
    int failures;
    volatile boolean queued;

    Job(String name, What what, String target, String prefix, Cron schedule, int idleMinutes, int keep, String storage, boolean announce,
        List<String> exclude, BackupStore store, String problem, Path state) {
      this.name = name;
      this.what = what;
      this.target = target;
      this.prefix = prefix;
      this.schedule = schedule;
      this.idleMinutes = idleMinutes;
      this.keep = keep;
      this.storage = storage;
      this.announce = announce;
      this.exclude = exclude;
      this.store = store;
      this.problem = problem;
      this.state = state;
    }

    /** 上次開始執行的時間（毫秒）；沒有時為 0。 */
    long lastRun() {
      return Yaml.load(state.resolve("job.yml")).getLong("last-run", 0);
    }

    /** 上次成功的時間（毫秒）；沒有時為 0。 */
    long lastSuccess() {
      return Yaml.load(state.resolve("last.yml")).getLong("time", 0);
    }
  }

  /** 一次執行的結果：快照名稱（沒有變化時為 null）、上傳的物件數、上傳的檔案數、沿用的檔案數、略過的世界。 */
  record Result(Job job, String snapshot, int objects, int files, int kept, List<String> skipped) {}

  private final BuildGit bg;
  private List<Job> jobs = List.of();
  private ZoneId zone = ZoneId.systemDefault();
  private ExecutorService worker, uploads;
  private ScheduledExecutorService ticker;
  /** 每個世界資料夾（相對伺服器根目錄）最後有玩家的時間。主執行緒寫、計時執行緒讀。 */
  private final Map<String, Long> visits = new ConcurrentHashMap<>();
  private final Map<ServerLevel, String> roots = new WeakHashMap<>();
  private volatile boolean visitsChanged;
  /** 最後看到有玩家的時間；伺服器沒人之後開始的時間（有人時為 0）。 */
  private volatile long lastOnline, emptySince;
  private long lastMinute;

  Backup(BuildGit bg) {
    this.bg = bg;
  }

  List<Job> jobs() {
    return jobs;
  }

  Job job(String name) {
    for (var j : jobs) if (j.name.equals(name)) return j;
    return null;
  }

  // ---- 設定 ----

  /** 讀 {@code s3.yml} 與 {@code backup.yml}；不存在時寫入範本（第一階段的 s3.yml 轉成工作）。之後不再改動這兩個檔案。 */
  private void load() {
    var platform = bg.platform();
    var log = platform.log();
    var dir = platform.configDir();
    var s3File = dir.resolve(S3_FILE);
    writeTemplate(s3File, log);
    var s3 = Yaml.load(s3File);
    // 第一階段的格式：連線直接寫在最上層
    boolean legacy = !s3.contains("connections");
    var backupFile = dir.resolve(FILE);
    if (!Files.exists(backupFile)) {
      try (InputStream in = Backup.class.getResourceAsStream("/" + FILE)) {
        Files.createDirectories(dir);
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (legacy && s3.contains("enabled")) {
          var values = new HashMap<String, Object>();
          boolean enabled = s3.getBoolean("enabled", false);
          values.put("jobs.history-s3.path", s3.getString("prefix", "buildgit").replaceAll("^/+|/+$", ""));
          values.put("jobs.history-s3.schedule", cron(Math.max(1, s3.getLong("interval-minutes", 60))));
          values.put("jobs.history-s3.keep", Math.max(1, s3.getInt("keep", 48)));
          values.put("jobs.history-s3.enabled", enabled);
          values.put("jobs.world-s3.enabled", enabled);
          text = Yaml.fill(text, values::get);
          log.info("已依 " + S3_FILE + " 原本的設定建立 " + FILE + "；" + S3_FILE + " 裡的 enabled、prefix、interval-minutes、keep 之後可以刪掉");
        }
        Files.writeString(backupFile, text);
      } catch (IOException e) {
        log.warning("無法寫入 " + backupFile + "：" + e.getMessage());
      }
    }
    var y = Yaml.load(backupFile);
    var tz = y.getString("timezone", "");
    try {
      zone = tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
    } catch (Exception e) {
      log.warning(FILE + " 的 timezone 不對（" + tz + "）；用伺服器的時區");
      zone = ZoneId.systemDefault();
    }
    var root = platform.server().getServerDirectory().toAbsolutePath().normalize();
    var result = new ArrayList<Job>();
    if (y.get("jobs") instanceof Map<?, ?> map)
      for (var e : map.entrySet()) {
        String name = String.valueOf(e.getKey()), at = "jobs." + name + ".";
        if (!name.matches("[A-Za-z0-9_-]+")) {
          log.warning(FILE + "：工作名稱只能用英數、底線、連字號：" + name);
          continue;
        }
        if (!y.getBoolean(at + "enabled", true)) continue;
        try {
          var what = switch (y.getString(at + "what", "")) {
            case "history" -> What.HISTORY;
            case "world" -> What.WORLD;
            default -> throw new IllegalArgumentException("what 要是 history 或 world");
          };
          String scheduleText = y.getString(at + "schedule", "");
          var schedule = scheduleText.isBlank() ? null : Cron.parse(scheduleText);
          int idle = Math.max(0, y.getInt(at + "idle-minutes", 0));
          if (schedule == null && idle == 0) throw new IllegalArgumentException("schedule 與 idle-minutes 至少要設一個");
          String storage = y.getString(at + "storage", "auto");
          if (!List.of("auto", "folders", "versioned", "hashed").contains(storage)) throw new IllegalArgumentException("storage 要是 auto、folders、versioned 或 hashed");
          String target = y.getString(at + "target", "");
          var path = y.getString(at + "path", name).replaceAll("^/+|/+$", "");
          BackupStore store = null;
          String problem = null;
          if (target.startsWith("s3:")) {
            var c = connection(s3, legacy, target.substring(3));
            problem = c == null ? "s3.yml 沒有連線 " + target.substring(3) : missing(c);
            if (problem == null) store = new S3(c);
          } else if (target.startsWith("local:") && target.length() > 6) {
            store = new LocalStore(root.resolve(target.substring(6)));
            if (storage.equals("versioned")) throw new IllegalArgumentException("本機的目的地不能用 versioned");
          } else {
            throw new IllegalArgumentException("target 要是 s3:<連線名稱> 或 local:<資料夾>");
          }
          result.add(new Job(name, what, target, path.isEmpty() ? "" : path + "/", schedule, idle, Math.max(1, y.getInt(at + "keep", 7)), storage,
              y.getBoolean(at + "announce", true), y.getStringList(at + "exclude"), store, problem,
              platform.repoDir().resolve("backup").resolve(name)));
        } catch (IllegalArgumentException ex) {
          log.warning(FILE + " 的工作 " + name + " 設定不對，不執行：" + ex.getMessage());
        }
      }
    // 只有部分填好的連線才警告；完全沒填的（範本）安靜地顯示「未設定」
    for (var j : result)
      if (j.problem != null && !j.problem.startsWith("endpoint")) log.warning("備份工作 " + j.name + "：" + j.problem + "；不執行");
    jobs = List.copyOf(result);
  }

  private static void writeTemplate(Path file, Logger log) {
    if (Files.exists(file)) return;
    try (InputStream in = Backup.class.getResourceAsStream("/" + file.getFileName())) {
      Files.createDirectories(file.getParent());
      if (in != null) Files.write(file, in.readAllBytes());
    } catch (IOException e) {
      log.warning("無法寫入 " + file + "：" + e.getMessage());
    }
  }

  private static S3.Config connection(Yaml y, boolean legacy, String name) {
    String at;
    if (legacy) {
      if (!name.equals("default")) return null;
      at = "";
    } else {
      at = "connections." + name + ".";
      if (!(y.get("connections." + name) instanceof Map)) return null;
    }
    return new S3.Config(y.getString(at + "endpoint", ""), y.getString(at + "region", "us-east-1"), y.getString(at + "bucket", ""),
        y.getString(at + "access-key", ""), y.getString(at + "secret-key", ""), y.getBoolean(at + "path-style", false));
  }

  private static String missing(S3.Config c) {
    if (c.endpoint().isBlank()) return "endpoint 還沒填";
    if (c.bucket().isBlank()) return "bucket 還沒填";
    if (c.accessKey().isBlank() || c.secretKey().isBlank()) return "access-key / secret-key 還沒填";
    return null;
  }

  /** 第一階段的「每幾分鐘」換成 cron。 */
  private static String cron(long minutes) {
    if (minutes < 60) return "*/" + minutes + " * * * *";
    if (minutes == 60) return "0 * * * *";
    if (minutes < 24 * 60 && minutes % 60 == 0) return "0 */" + minutes / 60 + " * * *";
    return "0 0 * * *";
  }

  // ---- 執行 ----

  /** 讀設定、排定檢查。{@code /bg reload} 也呼叫。 */
  synchronized void start() {
    stop();
    load();
    loadVisits();
    var first = history();
    if (first != null)
      try {
        migrateState(first);
      } catch (IOException e) {
        bg.platform().log().warning("無法搬移備份的本機紀錄：" + e.getMessage());
      }
    worker = Executors.newSingleThreadExecutor(r -> daemon(r, "BuildGit-backup"));
    uploads = Executors.newFixedThreadPool(PARALLEL, r -> daemon(r, "BuildGit-backup-upload"));
    ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "BuildGit-backup-timer"));
    lastMinute = System.currentTimeMillis() / 60000;
    ticker.scheduleWithFixedDelay(this::tick, 20, 20, TimeUnit.SECONDS);
  }

  synchronized void stop() {
    for (var e : new ExecutorService[] {ticker, worker, uploads}) if (e != null) e.shutdownNow();
    ticker = null;
    worker = null;
    uploads = null;
  }

  private static Thread daemon(Runnable r, String name) {
    var t = new Thread(r, name);
    t.setDaemon(true);
    return t;
  }

  /** 每 20 秒（計時執行緒）：記下玩家在哪裡，排定到期的工作。 */
  private void tick() {
    try {
      bg.platform().sync(this::observe);
      long now = System.currentTimeMillis(), minute = now / 60000;
      for (var job : jobs) {
        if (job.store == null) continue;
        boolean due = false;
        if (job.schedule != null)
          for (long m = Math.max(lastMinute + 1, minute - 5); m <= minute; m++)
            if (job.schedule.matches(Instant.ofEpochSecond(m * 60).atZone(zone))) due = true;
        if (job.idleMinutes > 0 && emptySince > 0 && now - emptySince >= job.idleMinutes * 60000L && lastOnline > job.lastRun()) due = true;
        if (due) enqueue(job, null);
      }
      lastMinute = minute;
      if (visitsChanged) saveVisits();
    } catch (Exception e) {
      bg.platform().log().log(Level.WARNING, "備份排程檢查失敗", e);
    }
  }

  /** 主執行緒：有沒有玩家、玩家在哪些世界資料夾。 */
  void observe() {
    var players = bg.platform().server().getPlayerList().getPlayers();
    long now = System.currentTimeMillis();
    if (players.isEmpty()) {
      if (emptySince == 0) emptySince = now;
      return;
    }
    emptySince = 0;
    lastOnline = now;
    for (var p : players) {
      var root = root(p.level());
      if (root != null) {
        visits.put(root, now);
        visitsChanged = true;
      }
    }
  }

  /** 世界所在的世界資料夾（有 level.dat 的那一層，相對伺服器根目錄）；BuildGit 的草稿與檢視世界為 null。 */
  String root(ServerLevel level) {
    return roots.computeIfAbsent(level, l -> {
      var platform = bg.platform();
      if (Workspace.ownWorldName(platform.levelName(l)) || bg.worlds().isDraftFolder(platform.levelName(l))) return null;
      var dir = platform.levelDirectory(l).toAbsolutePath().normalize();
      for (var d = dir; d != null; d = d.getParent()) {
        if (Files.isRegularFile(d.resolve("level.dat"))) {
          dir = d;
          break;
        }
      }
      var server = platform.server().getServerDirectory().toAbsolutePath().normalize();
      return dir.startsWith(server) ? server.relativize(dir).toString().replace('\\', '/') : null;
    });
  }

  Map<String, Long> visits() {
    return visits;
  }

  private Path visitsFile() {
    return bg.platform().repoDir().resolve("backup").resolve("visits.yml");
  }

  private void loadVisits() {
    visits.clear();
    if (Yaml.load(visitsFile()).get("worlds") instanceof Map<?, ?> m)
      m.forEach((k, v) -> {
        if (v instanceof Number n) visits.put(String.valueOf(k), n.longValue());
      });
  }

  private void saveVisits() throws IOException {
    visitsChanged = false;
    var y = new Yaml();
    y.set("worlds", new TreeMap<>(visits));
    Files.createDirectories(visitsFile().getParent());
    var tmp = visitsFile().resolveSibling("visits.yml.tmp");
    y.save(tmp);
    Files.move(tmp, visitsFile(), StandardCopyOption.REPLACE_EXISTING);
  }

  /** 排進背景執行緒；同一個工作已經在排隊或執行中時不重複排。 */
  private synchronized CompletableFuture<Result> enqueue(Job job, CompletableFuture<Result> result) {
    var w = worker;
    if (w == null || job.queued) {
      if (result != null) result.completeExceptionally(new Workspace.Refused(w == null ? "備份沒有啟用" : "工作 " + job.name + " 已經在進行"));
      return result;
    }
    job.queued = true;
    w.execute(() -> {
      try {
        var r = run(job);
        if (result != null) result.complete(r);
      } catch (Exception e) {
        if (result != null) result.completeExceptionally(e);
      } finally {
        job.queued = false;
      }
    });
    return result;
  }

  /** 立刻執行（{@code /bg backup now}）。 */
  CompletableFuture<Result> now(Job job) {
    if (job.store == null) return CompletableFuture.failedFuture(new Workspace.Refused("工作 " + job.name + " 的目的地還沒設定：" + job.problem));
    return enqueue(job, new CompletableFuture<>());
  }

  /** 在背景執行一件事（和備份排在同一個執行緒，不會同時進行）。 */
  private <T> CompletableFuture<T> submit(Job job, Callable<T> task) {
    var result = new CompletableFuture<T>();
    var w = worker;
    if (w == null) result.completeExceptionally(new Workspace.Refused("備份沒有啟用"));
    else if (job.store == null) result.completeExceptionally(new Workspace.Refused("工作 " + job.name + " 的目的地還沒設定：" + job.problem));
    else w.execute(() -> {
      try {
        result.complete(task.call());
      } catch (Exception e) {
        result.completeExceptionally(e);
      }
    });
    return result;
  }

  /** 遠端的快照名稱，舊的在前。 */
  CompletableFuture<List<String>> snapshots(Job job) {
    var pool = uploads;
    if (pool == null || job.store == null) return CompletableFuture.failedFuture(new Workspace.Refused("工作 " + job.name + " 的目的地還沒設定"));
    return CompletableFuture.supplyAsync(() -> {
      try {
        return Restore.snapshots(job.store, job.prefix);
      } catch (IOException e) {
        throw new CompletionException(e);
      }
    }, pool);
  }

  /** 下載快照準備還原（{@link Restore}）。 */
  CompletableFuture<Restore.Result> restore(Job job, String name) {
    var pool = uploads;
    return submit(job, () -> {
      try {
        return job.what == What.HISTORY ? Restore.download(bg, job, pool, name) : Restore.downloadWorld(bg, job, history(), pool, name);
      } catch (Exception e) {
        bg.platform().log().log(Level.WARNING, "下載備份失敗：" + Workspace.unwrap(e).getMessage(), e instanceof IOException ? null : e);
        throw e;
      }
    });
  }

  /** 第一個設定好的版本紀錄工作；沒有時為 null。 */
  Job history() {
    for (var j : jobs) if (j.what == What.HISTORY && j.store != null) return j;
    return null;
  }

  /** 從版本紀錄的備份取回一個分支的物件（{@link Restore#fetchBranch}）。 */
  CompletableFuture<Restore.Fetched> fetch(String branch) {
    var job = history();
    if (job == null) return CompletableFuture.failedFuture(new Workspace.Refused("沒有設定好的版本紀錄備份工作"));
    var pool = uploads;
    return submit(job, () -> Restore.fetchBranch(bg, job, pool, branch));
  }

  // ---- 一次執行（只在 worker 執行緒） ----

  private Result run(Job job) throws Exception {
    var log = bg.platform().log();
    try {
      Files.createDirectories(job.state);
      long previous = job.lastRun();
      var y = new Yaml();
      y.set("last-run", System.currentTimeMillis());
      y.save(job.state.resolve("job.yml"));
      var result = job.what == What.HISTORY ? history(job) : WorldBackup.run(bg, this, job, previous, uploads);
      job.lastError = null;
      job.failures = 0;
      if (result.snapshot() != null)
        log.info("備份 " + job.name + " 完成：" + result.snapshot() + "（上傳 " + (job.what == What.HISTORY ? result.objects() + " 個物件、" : "")
            + result.files() + " 個檔案" + (job.what == What.WORLD ? "、沿用 " + result.kept() + " 個" : "") + "）");
      return result;
    } catch (Exception e) {
      job.lastError = String.valueOf(Workspace.unwrap(e).getMessage());
      log.log(Level.WARNING, "備份 " + job.name + " 失敗：" + job.lastError, e instanceof IOException ? null : e);
      // 連續失敗 3 次起，每次都提醒線上的管理員（只看主控台的話不會發現）
      if (++job.failures >= 3) {
        int count = job.failures;
        String error = job.lastError;
        bg.platform().sync(() -> {
          for (var p : bg.platform().server().getPlayerList().getPlayers())
            if (bg.platform().hasPermission(p, "buildgit.backup"))
              bg.messages().send(bg.platform().sender(p), "backup-failing", "job", job.name, "count", count, "error", error);
        });
      }
      throw e;
    }
  }

  /** 第一階段的本機紀錄直接放在 {@code buildgit/backup/}：搬進第一個版本紀錄工作的資料夾。 */
  private void migrateState(Job job) throws IOException {
    var dir = bg.platform().repoDir().resolve("backup");
    if (job != history() || !Files.exists(dir.resolve("last.yml")) && !Files.exists(dir.resolve("commits"))) return;
    Files.createDirectories(job.state);
    for (var f : List.of("commits", "files.properties", "last.yml", "uploading", "fetching"))
      if (Files.exists(dir.resolve(f)) && !Files.exists(job.state.resolve(f))) Files.move(dir.resolve(f), job.state.resolve(f));
    Files.deleteIfExists(dir.resolve("last.yml"));
    Files.deleteIfExists(dir.resolve("commits"));
  }

  /** 版本紀錄的備份（設計 §2.5）：有變化時把版本庫增量上傳，寫一份快照。 */
  private Result history(Job job) throws Exception {
    var ws = bg.workspace();
    if (!ws.isOpen()) throw new Workspace.Refused("還沒有開始版本控制");
    var store = job.store;
    var repo = ws.repo();
    var state = job.state;
    Files.createDirectories(state);
    var last = Yaml.load(state.resolve("last.yml"));
    String prefix = job.prefix;

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
    var remoteFiles = new HashSet<>(store.list(prefix + "files/"));
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
            known = S3.hex(S3.sha256(tmp));
            if (remoteFiles.add(prefix + "files/" + known)) {
              store.putFile(prefix + "files/" + known, tmp);
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
    saveIndex(index, indexFile, "BuildGit backup: genesis file -> size mtime sha256");
    job.incomplete = List.copyOf(missing);

    // 3. 物件
    var commitsFile = state.resolve("commits");
    var uploaded = new HashSet<ObjectId>();
    if (Files.exists(commitsFile)) for (var line : Files.readAllLines(commitsFile)) if (!line.isBlank()) uploaded.add(ObjectId.parse(line.strip()));
    var snapshots = Restore.snapshots(store, prefix);
    // 本機的紀錄遺失、遠端卻已有備份，或上次上傳到一半中斷：先列出遠端已有的物件，不重傳
    var uploading = state.resolve("uploading");
    var remote = (!Files.exists(commitsFile) && !snapshots.isEmpty()) || Files.exists(uploading)
        ? new HashSet<>(store.list(prefix + "objects/")) : Set.<String>of();
    var walk = ObjectWalk.collect(repo, refs.values(), uploaded);

    boolean changed = !walk.commits().isEmpty() || !refMap.equals(last.get("refs")) || !branches.equals(last.getString("branches", ""))
        || !dataVersion.equals(last.getString("data-version", "")) || !content(dims).equals(content(last.get("dimensions")));
    if (!changed) return new Result(job, null, 0, 0, 0, List.of());

    Files.writeString(uploading, "");
    var objects = repo.overlays().objects();
    var futures = new ArrayList<Future<Boolean>>();
    for (var id : walk.objects()) {
      String hex = id.hex(), key = prefix + "objects/" + hex.substring(0, 2) + "/" + hex.substring(2);
      futures.add(uploads.submit(() -> {
        if (remote.contains(key)) return false;
        store.putFile(key, objects.file(id));
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
            store.put(prefix + "files/" + hash, bytes);
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
    store.putFile(prefix + "snapshots/" + name + ".yml", file);
    Files.move(file, state.resolve("last.yml"), StandardCopyOption.REPLACE_EXISTING);
    var lines = new ArrayList<String>();
    for (var id : walk.commits()) lines.add(id.hex());
    Files.write(commitsFile, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    Files.delete(uploading);

    snapshots.add(name);
    prune(job, snapshots);
    return new Result(job, name, sent, files, 0, List.of());
  }

  /** 只留最近 keep 份快照；之後刪掉沒有快照用到的檔案。物件不刪。 */
  private static void prune(Job job, List<String> snapshots) throws IOException {
    if (snapshots.size() <= job.keep) return;
    var store = job.store;
    String prefix = job.prefix;
    for (var old : snapshots.subList(0, snapshots.size() - job.keep)) purge(store, prefix + "snapshots/" + old + ".yml");
    var used = new HashSet<String>();
    for (var name : snapshots.subList(snapshots.size() - job.keep, snapshots.size())) {
      var bytes = store.get(prefix + "snapshots/" + name + ".yml");
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
    for (var key : store.list(prefix + "files/"))
      if (!used.contains(key.substring(key.lastIndexOf('/') + 1))) purge(store, key);
  }

  /** 刪掉不再需要的檔案；bucket 開了版本控制時連舊版本一起刪，不然空間不會釋放。 */
  static void purge(BackupStore store, String key) throws IOException {
    if (store instanceof S3 s3) s3.purge(key);
    else store.delete(key);
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

  static void saveIndex(Properties index, Path file, String comment) throws IOException {
    var tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try (var out = Files.newBufferedWriter(tmp)) {
      index.store(out, comment);
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
