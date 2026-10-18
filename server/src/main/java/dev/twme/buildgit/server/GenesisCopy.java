package dev.twme.buildgit.server;

import dev.twme.buildgit.core.anvil.RegionFile;
import dev.twme.buildgit.core.anvil.RegionWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 在背景把一個世界逐 chunk 複製成起點世界（{@code /bg init}，設計 §4.3），伺服器不必暫停。
 *
 * <p>init 時先存檔，此後磁碟上每個 chunk 都是 init 當下的樣子，直到伺服器再寫它一次；而伺服器只會寫載入過的 chunk。
 * 所以 init 時已載入的 chunk 與複製期間才載入的 chunk 插隊先複製（在它被改過、存回磁碟之前），其餘照順序複製。
 * 複製期間新生成的 chunk 以快照寫入。已寫進起點世界的 chunk 永不覆寫。
 *
 * <p>目錄裡的 {@link #MARKER} 表示還沒複製完；伺服器在複製途中關閉時，下次啟動從頭補完（已有的 chunk 跳過）。
 */
final class GenesisCopy {
  static final String MARKER = "copying";
  private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

  private final String dimension;
  private final Path from;
  private final Path to;
  private final Logger log;
  private final ArrayDeque<Runnable> urgent = new ArrayDeque<>();
  private boolean finished;
  /** 只在複製執行緒使用。 */
  private final Map<Path, RegionWriter> writers = new HashMap<>();
  final CompletableFuture<Long> done = new CompletableFuture<>();
  /** 以 region 檔計。 */
  final Progress progress = new Progress();

  GenesisCopy(String dimension, Path from, Path to, Logger log) throws IOException {
    this.dimension = dimension;
    this.from = from;
    this.to = to;
    this.log = log;
    Files.createDirectories(to.resolve("region"));
    Files.createDirectories(to.resolve("entities"));
    if (!Files.exists(to.resolve(MARKER))) Files.createFile(to.resolve(MARKER));
  }

  static boolean unfinished(Path genesisDir) {
    return Files.exists(genesisDir.resolve(MARKER));
  }

  /**
   * 開始複製；{@code loaded} 是現在已載入的 chunk（{@code x << 32 | z}），先複製。
   * {@code flush} 在複製執行緒上先執行，等存檔寫進 region 檔。
   */
  void start(long[] loaded, Runnable flush) {
    for (long key : loaded) chunkLoaded((int) (key >> 32), (int) key);
    var thread = new Thread(() -> run(flush), "BuildGit-init-" + dimension);
    thread.setDaemon(true);
    thread.start();
  }

  /** chunk 載入了（主執行緒）：在它被改過存回之前先複製。回傳 false 表示已複製完。 */
  boolean chunkLoaded(int x, int z) {
    return offer(() -> {
      copy("region", x, z);
      copy("entities", x, z);
    });
  }

  /** 複製期間新生成的 chunk（主執行緒取的快照）。回傳 false 表示已複製完，改由一般流程記錄。 */
  boolean generated(int x, int z, Callable<byte[]> snapshot) {
    return offer(() -> {
      try {
        var w = writer("region", x, z);
        int index = RegionFile.index(x, z);
        if (!w.has(index)) w.write(index, snapshot.call());
      } catch (Exception e) {
        log.log(Level.WARNING, "無法把新生成的 chunk " + x + "," + z + " 寫進起點世界", e);
      }
    });
  }

  private synchronized boolean offer(Runnable task) {
    if (finished) return false;
    urgent.add(task);
    return true;
  }

  private synchronized Runnable poll() {
    return urgent.poll();
  }

  private void drain() {
    for (Runnable r; (r = poll()) != null; ) r.run();
  }

  private void run(Runnable flush) {
    try {
      flush.run();
      var files = new LinkedHashMap<String, List<Path>>();
      for (var sub : List.of("region", "entities")) {
        try (Stream<Path> s = Files.list(from.resolve(sub))) {
          files.put(sub, s.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().toList());
        } catch (java.nio.file.NoSuchFileException e) {
          files.put(sub, List.of());
        }
      }
      progress.reset(files.values().stream().mapToLong(List::size).sum());
      for (var e : files.entrySet())
        for (var file : e.getValue()) {
          var m = NAME.matcher(file.getFileName().toString());
          if (m.matches()) copyFile(e.getKey(), file, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
          closeWriters(); // 大世界有上千個 region 檔，不要一直開著
          progress.step();
        }
      synchronized (this) {
        finished = true;
      }
      drain();
      closeWriters();
      Files.deleteIfExists(to.resolve(MARKER));
      done.complete(size());
    } catch (Throwable t) {
      synchronized (this) {
        finished = true;
      }
      closeWriters();
      done.completeExceptionally(t);
    }
  }

  private void copyFile(String sub, Path file, int rx, int rz) {
    try (var r = new RegionFile(file)) {
      for (int i = 0; i < 1024; i++) {
        drain();
        if (!r.has(i)) continue;
        var w = writer(sub, rx * 32 + (i & 31), rz * 32 + (i >>> 5));
        if (w.has(i)) continue;
        try {
          var bytes = r.readBytes(i);
          if (bytes != null) w.write(i, bytes);
        } catch (IOException e) {
          log.log(Level.WARNING, "複製起點世界時讀不到 " + file + " 的第 " + i + " 個 chunk", e);
        }
      }
    } catch (IOException e) {
      log.log(Level.WARNING, "複製起點世界時讀不到 " + file, e);
    }
  }

  /** 從世界的 region 檔讀出一個 chunk，起點世界還沒有時寫入。讀不到（檔案不存在、正被改寫）就跳過並記錄。 */
  private void copy(String sub, int x, int z) {
    var source = from.resolve(sub).resolve(RegionFile.fileName(x, z));
    if (!Files.isRegularFile(source)) return;
    int index = RegionFile.index(x, z);
    try {
      var w = writer(sub, x, z);
      if (w.has(index)) return;
      byte[] bytes;
      try (var r = new RegionFile(source)) {
        bytes = r.readBytes(index);
      }
      if (bytes != null) w.write(index, bytes);
    } catch (IOException e) {
      log.log(Level.WARNING, "複製起點世界時讀不到 " + dimension + " 的 chunk " + x + "," + z + "（" + sub + "）", e);
    }
  }

  private RegionWriter writer(String sub, int x, int z) throws IOException {
    var file = to.resolve(sub).resolve(RegionFile.fileName(x, z));
    var w = writers.get(file);
    if (w == null) {
      w = new RegionWriter(file);
      writers.put(file, w);
    }
    return w;
  }

  private void closeWriters() {
    for (var w : writers.values())
      try {
        w.close();
      } catch (IOException ignored) {
        // 每次寫入都已 force
      }
    writers.clear();
  }

  private long size() throws IOException {
    try (Stream<Path> s = Files.walk(to)) {
      return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
    }
  }

  void whenDone(Consumer<Long> ok, Consumer<Throwable> failed) {
    done.whenComplete((size, error) -> {
      if (error != null) failed.accept(error);
      else ok.accept(size);
    });
  }
}
