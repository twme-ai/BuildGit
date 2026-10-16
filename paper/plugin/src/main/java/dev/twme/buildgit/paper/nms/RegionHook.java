package dev.twme.buildgit.paper.nms;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import dev.twme.buildgit.server.Platform.Synthesizer;

/**
 * 分支世界的合成讀取（設計 §4.2）：region 檔第一次被開啟前，若不存在就先合成。
 *
 * <p>Paper 的 chunk 系統開啟 region 檔一定先查 {@code RegionFileStorage.regionCache}（在儲存物件的鎖內），
 * 查不到才檢查檔案是否存在。把這個快取換成子類別，就能在檢查之前寫好檔案；
 * 讀取方法本身是 final，不必也不能改。方塊與實體各有一個儲存物件，分別掛上。
 */
public final class RegionHook {
  private static final Field CACHE;
  private static final Field FOLDER;

  static {
    try {
      CACHE = RegionFileStorage.class.getDeclaredField("regionCache");
      CACHE.setAccessible(true);
      FOLDER = RegionFileStorage.class.getDeclaredField("folder");
      FOLDER.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private RegionHook() {}

  /** 世界初始化時（讀取任何 chunk 之前）呼叫。 */
  public static void install(ServerLevel level, String name, Synthesizer blocks, Synthesizer entities, Logger log)
      throws ReflectiveOperationException {
    var chunkStorage = ((ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemSimpleRegionStorage) level.getChunkSource().chunkMap)
        .moonrise$getRegionStorage();
    hook(chunkStorage, blocks, log, name + " region");
    hook(level.moonrise$getEntityChunkDataController().getCache(), entities, log, name + " entities");
  }

  @SuppressWarnings("unchecked")
  private static void hook(RegionFileStorage storage, Synthesizer synth, Logger log, String name) throws ReflectiveOperationException {
    synchronized (storage) {
      var current = (Long2ObjectLinkedOpenHashMap<RegionFile>) CACHE.get(storage);
      if (current instanceof SynthCache) return;
      var replacement = new SynthCache((Path) FOLDER.get(storage), synth, log, name);
      replacement.putAll(current);
      CACHE.set(storage, replacement);
    }
  }

  private static final class SynthCache extends Long2ObjectLinkedOpenHashMap<RegionFile> {
    private final Path folder;
    private final Synthesizer synth;
    private final Logger log;
    private final String name;
    /** 已處理過的 region（不論有沒有寫出檔案）。 */
    private final LongOpenHashSet done = new LongOpenHashSet();

    SynthCache(Path folder, Synthesizer synth, Logger log, String name) {
      this.folder = folder;
      this.synth = synth;
      this.log = log;
      this.name = name;
    }

    @Override
    public RegionFile getAndMoveToFirst(long key) {
      var r = super.getAndMoveToFirst(key);
      if (r != null || !done.add(key)) return r;
      int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
      Path target = folder.resolve("r." + x + "." + z + ".mca");
      if (Files.exists(target)) return null;
      try {
        synth.synthesize(target, x, z);
      } catch (IOException | RuntimeException e) {
        // 讓伺服器照常處理（生成新的 chunk）並清楚記錄；同一個 region 不重試，避免洗版。
        log.log(Level.SEVERE, "無法合成 " + name + " " + target.getFileName() + "；這個 region 會以產生器生成", e);
      }
      return null;
    }
  }
}
