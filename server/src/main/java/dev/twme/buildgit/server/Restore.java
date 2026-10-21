package dev.twme.buildgit.server;

import dev.twme.buildgit.core.anvil.RegionSynthesizer;
import dev.twme.buildgit.core.backup.BackupStore;
import dev.twme.buildgit.core.backup.ObjectFetch;
import dev.twme.buildgit.core.backup.ObjectWalk;
import dev.twme.buildgit.core.backup.S3;
import dev.twme.buildgit.core.overlay.OverlayReader;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.store.ObjectStore;
import dev.twme.buildgit.core.store.RefStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * 從備份還原（設計 §2.5、§2.6）。分兩步：
 * <ol>
 *   <li>{@link #download}：伺服器照常執行，把快照下載到 {@code buildgit-restore/}，主世界的 region 檔也先合成好，
 *       最後寫下 {@code restore.yml}（要換掉哪些資料夾）。
 *   <li>{@link #apply}：下次啟動、世界載入之前照 {@code restore.yml} 換上。原本的一律改名留著，不刪除。
 * </ol>
 */
final class Restore {
  static final String PLAN = "restore.yml";
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private Restore() {}

  /** 下載的結果：快照名稱、物件數、檔案數、要提醒管理員的事。 */
  record Result(String snapshot, int objects, int files, List<String> warnings) {}

  static Path staging(Path repoDir) {
    return repoDir.resolveSibling(repoDir.getFileName() + "-restore");
  }

  // ---- 下載（備份的背景執行緒） ----

  /** 還原版本紀錄。@param name 快照名稱；null 為最新的一份 */
  static Result download(BuildGit bg, Backup.Job job, ExecutorService pool, String name) throws Exception {
    var s3 = job.store;
    var prefix = job.prefix;
    var ws = bg.workspace();
    var platform = bg.platform();
    var upgrader = ws.upgrader();
    var warnings = new ArrayList<String>();

    var names = snapshots(s3, prefix);
    if (names.isEmpty()) throw new Workspace.Refused("遠端沒有快照");
    if (name == null) name = names.getLast();
    else if (!names.contains(name)) throw new Workspace.Refused("沒有快照 " + name);
    var snapshotBytes = s3.get(prefix + "snapshots/" + name + ".yml");
    if (snapshotBytes == null) throw new IOException("讀不到快照 " + name);
    var snapshot = Yaml.load(new ByteArrayInputStream(snapshotBytes));
    String dataVersion = snapshot.getString("data-version", "");
    checkVersion(dataVersion, upgrader.current());
    boolean sameVersion = snapshot.getInt("minecraft-data-version", -1) == upgrader.current();

    var repoDir = ws.dir();
    var staging = staging(repoDir);
    var genesisRoot = ws.genesisRoot();
    boolean genesisInside = genesisRoot.toAbsolutePath().normalize().equals(repoDir.resolve("genesis").toAbsolutePath().normalize());
    var genesisStaging = genesisInside ? staging.resolve("genesis") : genesisRoot.resolveSibling(genesisRoot.getFileName() + "-restore");
    delete(staging);
    delete(genesisStaging);
    Files.createDirectories(staging);
    var plan = new ArrayList<Map<String, Object>>();

    var refs = new RefStore(staging.resolve("refs").resolve("branches"));
    var heads = new ArrayList<ObjectId>();
    if (snapshot.get("refs") instanceof Map<?, ?> m)
      for (var e : m.entrySet()) {
        var id = ObjectId.parse(String.valueOf(e.getValue()));
        refs.compareAndSet(String.valueOf(e.getKey()), null, id);
        heads.add(id);
      }
    Files.writeString(staging.resolve("branches.yml"), snapshot.getString("branches", ""));
    Files.writeString(staging.resolve("data-version"), dataVersion);
    var pointer = repoDir.resolve("genesis-dir");
    if (Files.exists(pointer)) Files.copy(pointer, staging.resolve("genesis-dir"));

    // 物件：從快照的 refs 走訪，只下載用得到的，邊下載邊驗證雜湊
    var store = new ObjectStore(staging.resolve("objects"));
    int objects = ObjectFetch.fetch(store, heads, false, source(s3, prefix), pool);
    var repo = Repository.open(staging, upgrader);
    var walk = ObjectWalk.collect(repo, heads, Set.of());
    var main = repo.refs().get(Repository.MAIN).orElseThrow(() -> new IOException("快照沒有 main 分支"));
    var mainTree = repo.commit(main).tree();

    // 起點世界的檔案
    var state = staging.resolve("backup").resolve(job.name);
    Files.createDirectories(state);
    var index = new Properties();
    int files = 0;
    var root = platform.server().getServerDirectory().toAbsolutePath().normalize();
    var levelFiles = new LinkedHashMap<Path, String>(); // 目標 → 雜湊
    for (var entry : list(snapshot.get("dimensions"))) {
      String dim = String.valueOf(entry.get("id"));
      int colon = dim.indexOf(':');
      var genesisDir = genesisStaging.resolve(dim.substring(0, colon)).resolve(dim.substring(colon + 1));
      Files.createDirectories(genesisDir);
      var downloads = new ArrayList<Future<?>>();
      for (var line : strings(entry.get("files"))) {
        String hash = line.substring(0, line.indexOf(' ')), rel = line.substring(line.indexOf(' ') + 1);
        var target = genesisDir.resolve(rel);
        downloads.add(pool.submit(() -> {
          fetch(s3, prefix, hash, target);
          var attrs = Files.readAttributes(target, BasicFileAttributes.class);
          synchronized (index) {
            index.setProperty(dim + "/" + rel, attrs.size() + " " + attrs.lastModifiedTime().toMillis() + " " + hash);
          }
          return null;
        }));
      }
      for (var f : downloads) f.get();
      files += downloads.size();

      var levelDir = ws.mainLevelDirectory(dim);
      if (levelDir == null && sameVersion && entry.get("world") != null) levelDir = root.resolve(String.valueOf(entry.get("world")));
      if (Boolean.TRUE.equals(entry.get("lazy"))) {
        // 延遲複製：起點世界缺的 chunk 只在主世界存檔裡，世界資料夾不換，由管理員用同一時間的世界備份
        if (levelDir != null) Files.writeString(genesisDir.resolve("lazy"), levelDir.toAbsolutePath().normalize().toString());
        warnings.add(dim + " 用了延遲複製：世界資料夾不換，請用同一時間的世界備份");
        continue;
      }
      if (levelDir == null) {
        warnings.add(dim + " 的世界沒有載入，也找不到原本的位置：只還原版本，世界資料夾不換");
        continue;
      }

      // 主世界由「起點世界＋main 最新存檔」先合成好
      var out = staging.resolve("worlds").resolve(dim.replace(':', '_'));
      var synth = new RegionSynthesizer(genesisDir, null, new OverlayReader(repo.overlays(), mainTree), dim, upgrader);
      for (var name2 : mcaNames(genesisDir.resolve("region"))) {
        int[] r = regionPos(name2);
        Files.createDirectories(out.resolve("region"));
        synth.blocks(out.resolve("region").resolve(name2), r[0], r[1]);
      }
      var entityNames = new TreeSet<>(mcaNames(genesisDir.resolve("region")));
      entityNames.addAll(mcaNames(genesisDir.resolve("entities")));
      for (var name2 : entityNames) {
        int[] r = regionPos(name2);
        Files.createDirectories(out.resolve("entities"));
        synth.entities(out.resolve("entities").resolve(name2), r[0], r[1]);
      }
      levelDir = levelDir.toAbsolutePath().normalize();
      plan.add(move(levelDir.resolve("region"), out.resolve("region")));
      plan.add(move(levelDir.resolve("entities"), Files.isDirectory(out.resolve("entities")) ? out.resolve("entities") : null));
      plan.add(move(levelDir.resolve("poi"), null)); // 伺服器載入 chunk 時從方塊重建

      // level.dat 等：同一版本才換（不同版本的世界資料夾結構不同，換了可能觸發舊世界的升級流程）
      var seed = entry.get("seed");
      if (sameVersion) {
        for (var line : strings(entry.get("level-files")))
          levelFiles.put(root.resolve(line.substring(line.indexOf(' ') + 1)), line.substring(0, line.indexOf(' ')));
        if (entry.get("level-dat") != null) { // 第一階段的快照
          var line = String.valueOf(entry.get("level-dat"));
          levelFiles.put(root.resolve(line.substring(line.indexOf(' ') + 1)), line.substring(0, line.indexOf(' ')));
        }
      } else if (seed != null && ws.mainSeed(dim) != null && !String.valueOf(seed).equals(String.valueOf(ws.mainSeed(dim)))) {
        warnings.add(dim + " 的種子是 " + ws.mainSeed(dim) + "，備份是 " + seed + "：起點世界以外的地方會生成不同的地形；"
            + "要相同的話，在新世界產生前把 level-seed 設成 " + seed);
      }
    }
    int n = 0;
    for (var e : levelFiles.entrySet()) {
      var source = staging.resolve("level").resolve(String.valueOf(n++));
      fetch(s3, prefix, e.getValue(), source);
      plan.add(move(e.getKey().toAbsolutePath().normalize(), source));
    }
    if (!sameVersion && !levelFiles.isEmpty()) warnings.add("備份來自不同的 Minecraft 版本，level.dat 不換");

    // 草稿與檢視世界：由新的版本重建
    for (var world : platform.levelFolders())
      if (Workspace.ownWorldName(world) && !world.contains(".before-restore-")) plan.add(move(platform.levelFolder(world).toAbsolutePath().normalize(), null));
    if (!genesisInside) plan.add(move(genesisRoot.toAbsolutePath().normalize(), genesisStaging.toAbsolutePath().normalize()));

    // 本機的上傳紀錄：還原之後的第一次備份不必重傳、也不必重算雜湊
    var commits = new ArrayList<String>();
    for (var c : walk.commits()) commits.add(c.hex());
    Files.write(state.resolve("commits"), commits);
    try (var w = Files.newBufferedWriter(state.resolve("files.properties"))) {
      index.store(w, "BuildGit backup: genesis file -> size mtime sha256");
    }
    Files.write(state.resolve("last.yml"), snapshotBytes);

    plan.add(move(repoDir.toAbsolutePath().normalize(), staging.toAbsolutePath().normalize())); // 最後換版本庫
    var y = new Yaml();
    y.set("snapshot", name);
    y.set("replace", plan);
    y.save(staging.resolve(PLAN + ".tmp"));
    Files.move(staging.resolve(PLAN + ".tmp"), staging.resolve(PLAN), StandardCopyOption.ATOMIC_MOVE);
    return new Result(name, objects, files, warnings);
  }

  /**
   * 還原世界資料夾（設計 §2.6）：世界資料夾整個換成快照裡的，版本庫的分支指向、還沒存檔的標記也回到同一個時間點。
   * 版本庫沿用本機的（物件只增不減）；快照指向的存檔本機沒有時，從版本紀錄的備份補。
   *
   * @param history 版本紀錄的工作，補物件用；沒有時為 null
   */
  static Result downloadWorld(BuildGit bg, Backup.Job job, Backup.Job history, ExecutorService pool, String name) throws Exception {
    var store = job.store;
    var prefix = job.prefix;
    var ws = bg.workspace();
    var platform = bg.platform();
    var warnings = new ArrayList<String>();
    var names = snapshots(store, prefix);
    if (names.isEmpty()) throw new Workspace.Refused("遠端沒有快照");
    if (name == null) name = names.getLast();
    else if (!names.contains(name)) throw new Workspace.Refused("沒有快照 " + name);
    var bytes = store.get(prefix + "snapshots/" + name + ".yml");
    if (bytes == null) throw new IOException("讀不到快照 " + name);
    var manifest = Yaml.load(new ByteArrayInputStream(bytes));
    int version = manifest.getInt("minecraft-data-version", 0), current = ws.upgrader().current();
    if (version > current)
      throw new Workspace.Refused("這份備份來自較新的 Minecraft（資料版本 " + version + "），目前伺服器是 " + current + "；Minecraft 不能降級");
    if (version < current) warnings.add("備份來自較舊的 Minecraft（資料版本 " + version + "）：換上後由 Minecraft 照一般的流程升級世界");

    // 版本庫：分支指向的存檔要在本機
    var repoRel = manifest.getString("repo", null);
    var heads = new ArrayList<ObjectId>();
    if (manifest.get("refs") instanceof Map<?, ?> refs) for (var v : refs.values()) heads.add(ObjectId.parse(String.valueOf(v)));
    if (!heads.isEmpty()) {
      if (!ws.isOpen()) throw new Workspace.Refused("這份備份有版本紀錄，但這台伺服器還沒有版本庫：請先還原版本紀錄的備份、重新啟動，再還原世界資料夾");
      var objects = ws.repo().overlays().objects();
      var missing = heads.stream().filter(id -> !Files.exists(objects.file(id))).toList();
      if (!missing.isEmpty()) {
        if (history == null) throw new Workspace.Refused("本機沒有這份備份用到的存檔，也沒有設定版本紀錄的備份工作可以補");
        ObjectFetch.fetch(objects, missing, false, source(history.store, history.prefix), pool);
      }
    }

    var server = platform.server().getServerDirectory().toAbsolutePath().normalize();
    var repoDir = platform.repoDir().toAbsolutePath().normalize();
    var staging = staging(repoDir);
    delete(staging);
    var files = staging.resolve("files");
    Files.createDirectories(files);
    String storage = manifest.getString("storage", "hashed"), snapshot = name;
    var downloads = new ArrayList<Future<?>>();
    for (var e : WorldBackup.entries(manifest)) {
      var target = files.resolve(e.path()).normalize();
      if (!target.startsWith(files)) throw new IOException("快照裡的路徑不對：" + e.path());
      downloads.add(pool.submit(() -> {
        var key = WorldBackup.key(prefix, storage, snapshot, e);
        var data = e.version() != null && store instanceof S3 s3 ? s3.getVersion(key, e.version()) : store.get(key);
        if (data == null) throw new IOException("備份缺少檔案 " + e.path());
        if (!S3.hex(S3.sha256(data)).equals(e.sha())) throw new IOException("檔案雜湊不符：" + e.path());
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return null;
      }));
    }
    for (var f : downloads) f.get();

    var plan = new ArrayList<Map<String, Object>>();
    // 草稿與檢視世界：分支指向回到備份的時間，由版本重建
    for (var world : platform.levelFolders())
      if (Workspace.ownWorldName(world) && !world.contains(".before-restore-")) plan.add(move(platform.levelFolder(world).toAbsolutePath().normalize(), null));
    for (var world : manifest.getStringList("worlds")) {
      var source = files.resolve(world);
      if (Files.isDirectory(source)) plan.add(move(server.resolve(world).normalize(), source));
    }
    for (var skipped : manifest.getStringList("skipped")) warnings.add(skipped + " 從來沒有玩家進去過，沒有備份，不換");
    if (repoRel != null && Files.isDirectory(files.resolve(repoRel))) {
      var from = files.resolve(repoRel);
      for (var item : List.of("refs", "branches.yml", "data-version", "worktree", "players.yml"))
        if (Files.exists(from.resolve(item))) plan.add(move(repoDir.resolve(item), from.resolve(item)));
    }
    var y = new Yaml();
    y.set("snapshot", job.name + "/" + name);
    y.set("replace", plan);
    y.save(staging.resolve(PLAN + ".tmp"));
    Files.move(staging.resolve(PLAN + ".tmp"), staging.resolve(PLAN), StandardCopyOption.ATOMIC_MOVE);
    return new Result(name, 0, downloads.size(), warnings);
  }

  /** 取回分支的結果：用的快照、分支指向的提交、下載的物件數、分支在 branches.yml 的設定（來源、所屬世界）。 */
  record Fetched(String snapshot, ObjectId head, int objects, String source, String dimension) {}

  /**
   * 從最新一份有這個分支的快照取回它的物件（不重新啟動、不動其他分支）。物件寫進目前的版本庫；
   * 建立分支由呼叫端在主執行緒做。
   */
  static Fetched fetchBranch(BuildGit bg, Backup.Job job, ExecutorService pool, String branch) throws Exception {
    var s3 = job.store;
    var prefix = job.prefix;
    var ws = bg.workspace();
    if (!ws.isOpen()) throw new Workspace.Refused("還沒有開始版本控制");
    var names = snapshots(s3, prefix);
    for (int i = names.size() - 1; i >= 0; i--) {
      var bytes = s3.get(prefix + "snapshots/" + names.get(i) + ".yml");
      if (bytes == null) continue;
      var snapshot = Yaml.load(new ByteArrayInputStream(bytes));
      if (!(snapshot.get("refs") instanceof Map<?, ?> refs) || refs.get(branch) == null) continue;
      checkVersion(snapshot.getString("data-version", ""), ws.upgrader().current());
      var head = ObjectId.parse(String.valueOf(refs.get(branch)));
      // 上次補到一半中斷時，已有的物件底下可能還缺：這次全部檢查
      var marker = job.state.resolve("fetching");
      boolean check = Files.exists(marker);
      Files.createDirectories(marker.getParent());
      Files.writeString(marker, branch);
      int objects = ObjectFetch.fetch(ws.repo().overlays().objects(), List.of(head), check, source(s3, prefix), pool);
      Files.delete(marker);
      var meta = Yaml.load(new ByteArrayInputStream(snapshot.getString("branches", "").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return new Fetched(names.get(i), head, objects, meta.getString(branch + ".source", Repository.MAIN), meta.getString(branch + ".dimension", null));
    }
    throw new Workspace.Refused("遠端的快照裡都沒有分支 " + branch);
  }

  static List<String> snapshots(BackupStore s3, String prefix) throws IOException {
    var names = new ArrayList<String>();
    for (var k : s3.list(prefix + "snapshots/")) if (k.endsWith(".yml")) names.add(k.substring(k.lastIndexOf('/') + 1, k.length() - 4));
    Collections.sort(names);
    return names;
  }

  private static ObjectFetch.Source source(BackupStore s3, String prefix) {
    return id -> s3.get(prefix + "objects/" + id.hex().substring(0, 2) + "/" + id.hex().substring(2));
  }

  /** 跨版本：Minecraft 不能降級，和開啟版本庫的規則相同，在下載之前就檢查。 */
  private static void checkVersion(String dataVersion, int current) throws IOException {
    var dv = new Properties();
    dv.load(new StringReader(dataVersion));
    int newest = Integer.parseInt(dv.getProperty("newest", "0").strip());
    if (newest > current)
      throw new Workspace.Refused("這份備份曾由較新的 Minecraft（資料版本 " + newest + "）寫入，目前伺服器是 " + current + "；請用相同或更新的版本");
  }

  private static void fetch(BackupStore s3, String prefix, String hash, Path target) throws IOException {
    var bytes = s3.get(prefix + "files/" + hash);
    if (bytes == null) throw new IOException("備份缺少檔案 " + hash);
    if (!S3.hex(S3.sha256(bytes)).equals(hash)) throw new IOException("檔案雜湊不符：" + hash);
    Files.createDirectories(target.getParent());
    Files.write(target, bytes);
  }

  private static Map<String, Object> move(Path target, Path source) {
    var m = new LinkedHashMap<String, Object>();
    m.put("target", target.toString());
    if (source != null) m.put("source", source.toString());
    return m;
  }

  private static List<Map<?, ?>> list(Object o) {
    var result = new ArrayList<Map<?, ?>>();
    if (o instanceof List<?> l) for (var e : l) if (e instanceof Map<?, ?> m) result.add(m);
    return result;
  }

  private static List<String> strings(Object o) {
    var result = new ArrayList<String>();
    if (o instanceof List<?> l) for (var e : l) result.add(String.valueOf(e));
    return result;
  }

  private static List<String> mcaNames(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) return List.of();
    try (var s = Files.list(dir)) {
      return s.map(p -> p.getFileName().toString()).filter(f -> f.matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList();
    }
  }

  private static int[] regionPos(String name) {
    var parts = name.split("\\.");
    return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
  }

  // ---- 換上（啟動時、世界載入前） ----

  /**
   * 有下載好的還原時換上。Paper 的 {@code onLoad}、Fabric 的 {@code SERVER_STARTING} 呼叫。
   *
   * @throws IllegalStateException 換到一半失敗：不能帶著一半的狀態開服，呼叫端要停止啟動
   */
  static void apply(Path repoDir, Logger log) {
    var staging = staging(repoDir);
    var planFile = staging.resolve(PLAN);
    if (!Files.exists(planFile)) return;
    var y = Yaml.load(planFile);
    String suffix = ".before-restore-" + STAMP.format(Instant.now());
    log.info("從備份還原：" + y.getString("snapshot", "?"));
    try {
      // 中途當機時重來：已經換上的（來源不在了）略過
      for (var m : list(y.get("replace"))) {
        var target = Path.of(String.valueOf(m.get("target")));
        var source = m.get("source") == null ? null : Path.of(String.valueOf(m.get("source")));
        if (source != null && !Files.exists(source)) continue;
        if (Files.exists(target)) {
          var aside = target.resolveSibling(target.getFileName() + suffix);
          Files.move(target, aside);
          log.info("  原本的 " + target + " 改名為 " + aside.getFileName());
        }
        if (source != null) {
          Files.createDirectories(target.getParent());
          Files.move(source, target);
        }
      }
      if (Files.exists(repoDir.resolve(PLAN))) { // 換上了整個版本庫（版本紀錄的還原）
        Files.move(repoDir.resolve(PLAN), repoDir.resolve("restored.yml"), StandardCopyOption.REPLACE_EXISTING);
        delete(repoDir.resolve("worlds"));
        delete(repoDir.resolve("level"));
      } else { // 世界資料夾的還原：版本庫留著，只換了裡面的幾個檔案
        Files.createDirectories(repoDir);
        Files.move(planFile, repoDir.resolve("restored.yml"), StandardCopyOption.REPLACE_EXISTING);
        delete(staging);
      }
      log.info("還原完成。確認沒問題後，可以刪掉名稱含 " + suffix + " 的資料夾");
    } catch (IOException e) {
      throw new IllegalStateException("還原失敗：" + e.getMessage() + "；修正後重新啟動會從中斷的地方繼續", e);
    }
  }

  static void delete(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (var s = Files.walk(dir)) {
      for (var p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }
}
