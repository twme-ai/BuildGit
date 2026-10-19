package dev.twme.buildgit.server;

import dev.twme.buildgit.core.anvil.AnvilSource;
import dev.twme.buildgit.core.anvil.RegionFile;
import dev.twme.buildgit.core.anvil.RegionWriter;
import dev.twme.buildgit.server.nms.LiveReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 把 init 之後才生成的 chunk 補進起點世界（設計 §2.3），以及延遲複製時把剛載入的 chunk 補進起點世界（§4.3）。
 *
 * <p>新 chunk 的「原本樣子」就是剛生成時的內容。生成完成（第一次以完整狀態載入）時玩家還沒碰過它，
 * 在主執行緒取快照，序列化與寫檔交給專用執行緒。起點世界已有的 chunk 永不覆寫。
 */
final class GenesisRecorder {
  private final Function<String, Path> genesisDir;
  private final AnvilSource genesis;
  private final Logger log;
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "BuildGit-genesis");
        t.setDaemon(true);
        return t;
      });
  /** 只在 io 執行緒使用。只開著最近用過的幾個，玩家一路探索時不會累積上百個開著的檔案。 */
  private final Map<Path, RegionWriter> writers =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, RegionWriter> eldest) {
          if (size() <= 16) return false;
          try {
            eldest.getValue().close();
          } catch (IOException ignored) {
            // 每次寫入都已 force
          }
          return true;
        }
      };

  /** 延遲複製：已經排進複製的 chunk（{@code x << 32 | z}），每個維度一份。 */
  private final Map<String, Set<Long>> copied = new ConcurrentHashMap<>();

  GenesisRecorder(Function<String, Path> genesisDir, AnvilSource genesis, Logger log) {
    this.genesisDir = genesisDir;
    this.genesis = genesis;
    this.log = log;
  }

  /** 主執行緒呼叫。 */
  void record(String dimension, ServerLevel level, LevelChunk chunk) {
    int x = chunk.getPos().getMinBlockX() >> 4, z = chunk.getPos().getMinBlockZ() >> 4;
    var snapshot = LiveReader.snapshot(level, chunk);
    io.execute(() -> {
      try {
        var writer = writer(dimension, "region", x, z);
        int index = RegionFile.index(x, z);
        if (writer.has(index)) return;
        writer.write(index, snapshot.call());
        genesis.invalidate(dimension, x, z);
      } catch (Exception e) {
        log.log(Level.WARNING, "無法把新生成的 chunk " + x + "," + z + " 寫進起點世界；那裡的變更會被當成新增", e);
      }
    });
  }

  /**
   * 延遲複製：主世界的 chunk 載入了（主執行緒）。在它被改過、存回之前，把 {@code levelDir} 磁碟上的樣子複製進起點世界。
   * 先寫實體再寫方塊：起點世界有某個 chunk 的方塊時，它的實體就以起點世界為準。
   */
  void copy(String dimension, Path levelDir, int x, int z) {
    if (!copied.computeIfAbsent(dimension, d -> ConcurrentHashMap.newKeySet()).add((long) x << 32 | z & 0xFFFFFFFFL)) return;
    io.execute(() -> {
      try {
        int index = RegionFile.index(x, z);
        var blocks = writer(dimension, "region", x, z);
        if (blocks.has(index)) return;
        var region = read(levelDir.resolve("region"), x, z);
        if (region == null) return; // 還沒存過的新 chunk：由 record 補進
        var entities = read(levelDir.resolve("entities"), x, z);
        if (entities != null) writer(dimension, "entities", x, z).write(index, entities);
        blocks.write(index, region);
        genesis.invalidate(dimension, x, z);
      } catch (Exception e) {
        log.log(Level.WARNING, "無法把 chunk " + x + "," + z + " 複製進起點世界；之後改過又存回的話，起點會變成改過的樣子", e);
      }
    });
  }

  /** 延遲複製：init 或啟動時已載入的 chunk。{@code flush} 先在寫入執行緒上執行（等存檔寫進 region 檔）。 */
  void copyLoaded(String dimension, Path levelDir, long[] loaded, Runnable flush) {
    io.execute(flush);
    for (long key : loaded) copy(dimension, levelDir, (int) (key >> 32), (int) key);
  }

  private static byte[] read(Path dir, int x, int z) throws IOException {
    var file = dir.resolve(RegionFile.fileName(x, z));
    if (!Files.isRegularFile(file)) return null;
    try (var r = new RegionFile(file)) {
      return r.readBytes(RegionFile.index(x, z));
    }
  }

  /** 在寫入執行緒上執行（和補進起點世界的寫入依序進行）。 */
  void execute(Runnable task) {
    io.execute(task);
  }

  /** 只在 io 執行緒使用：檔案要被換掉了，關掉開著的寫入。 */
  void forget(Path file) {
    var writer = writers.remove(file);
    if (writer != null)
      try {
        writer.close();
      } catch (IOException ignored) {
        // 每次寫入都已 force
      }
  }

  /** 只在 io 執行緒使用。 */
  private RegionWriter writer(String dimension, String sub, int x, int z) throws IOException {
    var file = genesisDir.apply(dimension).resolve(sub).resolve(RegionFile.fileName(x, z));
    var writer = writers.get(file);
    if (writer == null) {
      writer = new RegionWriter(file);
      writers.put(file, writer);
    }
    return writer;
  }

  /** 等先前排入的寫入完成。存檔讀取起點世界之前呼叫（背景執行緒）。 */
  void awaitIdle() {
    try {
      io.submit(() -> {}).get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.log(Level.WARNING, "等待起點世界寫入逾時", e);
    }
  }

  void close() {
    io.shutdown();
    try {
      if (!io.awaitTermination(30, TimeUnit.SECONDS)) log.warning("起點世界寫入尚未完成就關閉");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    for (var w : writers.values())
      try {
        w.close();
      } catch (IOException ignored) {
        // 每次寫入都已 force
      }
    writers.clear();
  }
}
