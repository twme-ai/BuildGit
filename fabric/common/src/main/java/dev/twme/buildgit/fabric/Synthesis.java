package dev.twme.buildgit.fabric;

import dev.twme.buildgit.server.Platform.Synthesizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 分支世界的合成讀取（設計 §4.2）：原版開啟 region 檔之前（{@code RegionFileStorage.getRegionFile}，IO 執行緒），
 * 檔案不存在就先合成。資料夾在世界建立之前登記，所以第一次讀取就會經過這裡。
 */
public final class Synthesis {
  private Synthesis() {}

  /** region／entities 資料夾（絕對路徑）→ 合成器。 */
  private static final Map<Path, Synthesizer> FOLDERS = new ConcurrentHashMap<>();
  /** 已處理過的 region 檔（不論有沒有寫出檔案），同一個不重試。 */
  private static final Set<Path> DONE = ConcurrentHashMap.newKeySet();
  static volatile Logger log = Logger.getLogger("BuildGit");

  static void register(Path folder, Synthesizer synth) {
    FOLDERS.put(key(folder), synth);
  }

  static void unregister(Path folder) {
    var k = key(folder);
    FOLDERS.remove(k);
    DONE.removeIf(p -> p.startsWith(k));
  }

  private static Path key(Path folder) {
    return folder.toAbsolutePath().normalize();
  }

  /** mixin 呼叫：這個 region 檔即將第一次開啟。 */
  public static void beforeOpen(Path folder, int regionX, int regionZ) {
    if (FOLDERS.isEmpty()) return;
    var k = key(folder);
    var synth = FOLDERS.get(k);
    if (synth == null) return;
    var target = k.resolve("r." + regionX + "." + regionZ + ".mca");
    if (!DONE.add(target) || Files.exists(target)) return;
    try {
      Files.createDirectories(k);
      synth.synthesize(target, regionX, regionZ);
    } catch (IOException | RuntimeException e) {
      // 讓伺服器照常處理（生成新的 chunk）並清楚記錄；同一個 region 不重試，避免洗版。
      log.log(Level.SEVERE, "無法合成 " + target + "；這個 region 會以產生器生成", e);
    }
  }
}
