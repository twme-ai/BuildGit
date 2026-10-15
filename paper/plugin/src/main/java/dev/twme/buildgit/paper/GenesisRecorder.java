package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.anvil.AnvilSource;
import dev.twme.buildgit.core.anvil.RegionFile;
import dev.twme.buildgit.core.anvil.RegionWriter;
import dev.twme.buildgit.paper.nms.LiveReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Chunk;

/**
 * 把 init 之後才生成的 chunk 補進起點世界（設計 §2.3）。
 *
 * <p>新 chunk 的「原本樣子」就是剛生成時的內容。生成完成（{@code ChunkLoadEvent#isNewChunk}）時玩家還沒碰過它，
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

  GenesisRecorder(Function<String, Path> genesisDir, AnvilSource genesis, Logger log) {
    this.genesisDir = genesisDir;
    this.genesis = genesis;
    this.log = log;
  }

  /** 主執行緒呼叫。 */
  void record(String dimension, Chunk chunk) {
    int x = chunk.getX(), z = chunk.getZ();
    var snapshot = LiveReader.snapshot(chunk);
    io.execute(() -> {
      try {
        var file = genesisDir.apply(dimension).resolve("region").resolve(RegionFile.fileName(x, z));
        var writer = writers.get(file);
        if (writer == null) {
          writer = new RegionWriter(file);
          writers.put(file, writer);
        }
        int index = RegionFile.index(x, z);
        if (writer.has(index)) return;
        writer.write(index, snapshot.call());
        genesis.invalidate(dimension, x, z);
      } catch (Exception e) {
        log.log(Level.WARNING, "無法把新生成的 chunk " + x + "," + z + " 寫進起點世界；那裡的變更會被當成新增", e);
      }
    });
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
