package dev.twme.buildgit.core.anvil;

import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.overlay.OverlayReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 合成分支世界的一個 region 檔：起點世界的 region 套上某個版本的覆寫表（設計 §4.2）。
 *
 * <p>一次合成整個 region（32×32 chunk），之後這個檔案就是分支世界自己的，伺服器照常讀寫。
 * 沒有覆寫的 chunk 直接複製位元組；起點世界沒有、只有覆寫的 chunk 不寫，交給產生器生成後再套用。
 * 執行緒安全：同時只合成一個檔案。
 */
public final class RegionSynthesizer {
  private final Path genesis;
  private final OverlayReader overlay;
  private final String dimension;
  private final int dataVersion;
  private Map<Long, List<EntityState>> placed;
  private Set<UUID> overridden;

  /**
   * @param genesis 起點世界中這個維度的目錄（含 {@code region/}、{@code entities/}）；沒有時為 null
   * @param dataVersion 新建實體 chunk 時使用的資料版本
   */
  public RegionSynthesizer(Path genesis, OverlayReader overlay, String dimension, int dataVersion) {
    this.genesis = genesis;
    this.overlay = overlay;
    this.dimension = dimension;
    this.dataVersion = dataVersion;
  }

  public OverlayReader overlay() {
    return overlay;
  }

  /**
   * 合成方塊 region 檔。
   *
   * @param target 目標檔案（{@code .../region/r.X.Z.mca}），必須不存在
   * @return 是否寫出檔案
   */
  public synchronized boolean blocks(Path target, int regionX, int regionZ) throws IOException {
    Path source = genesis == null ? null : genesis.resolve("region").resolve("r." + regionX + "." + regionZ + ".mca");
    if (source == null || !Files.isRegularFile(source)) return false;
    var patched = new HashSet<Integer>();
    for (var c : overlay.chunksInRegion(dimension, regionX, regionZ)) patched.add(RegionFile.index(c[0], c[1]));
    return write(target, (in, out) -> {
      for (int i = 0; i < 1024; i++) {
        if (!in.has(i)) continue;
        int cx = regionX * 32 + (i & 31), cz = regionZ * 32 + (i >>> 5);
        if (patched.contains(i)) {
          var chunk = ChunkPatcher.blocks(in.read(i), cx, cz, overlay.chunk(dimension, cx, cz));
          out.write(i, Nbt.write(chunk));
        } else {
          out.write(i, in.readBytes(i));
        }
      }
    }, source);
  }

  /** 合成實體 region 檔（{@code .../entities/r.X.Z.mca}）。 */
  public synchronized boolean entities(Path target, int regionX, int regionZ) throws IOException {
    indexEntities();
    Path source = genesis == null ? null : genesis.resolve("entities").resolve("r." + regionX + "." + regionZ + ".mca");
    boolean hasSource = source != null && Files.isRegularFile(source);
    var chunks = new TreeSet<Integer>();
    for (var key : placed.keySet()) {
      int cx = (int) (key >> 32), cz = (int) (long) key;
      if (cx >> 5 == regionX && cz >> 5 == regionZ) chunks.add(RegionFile.index(cx, cz));
    }
    if (!hasSource && chunks.isEmpty()) return false;
    return write(target, (in, out) -> {
      for (int i = 0; i < 1024; i++) {
        boolean inGenesis = in != null && in.has(i);
        if (!inGenesis && !chunks.contains(i)) continue;
        int cx = regionX * 32 + (i & 31), cz = regionZ * 32 + (i >>> 5);
        var here = placed.getOrDefault(key(cx, cz), List.of());
        if (inGenesis && here.isEmpty() && overridden.isEmpty()) {
          out.write(i, in.readBytes(i));
          continue;
        }
        var chunk = ChunkPatcher.entities(inGenesis ? in.read(i) : null, cx, cz, dataVersion, overridden, here);
        if (chunk != null) out.write(i, Nbt.write(chunk));
      }
    }, hasSource ? source : null);
  }

  private void indexEntities() throws IOException {
    if (placed != null) return;
    var all = overlay.entities(dimension);
    overridden = new HashSet<>(all.keySet());
    placed = new HashMap<>();
    for (var e : all.values()) {
      if (e.isEmpty()) continue;
      var pos = e.get().position();
      if (pos == null) continue;
      int cx = (int) Math.floor(pos[0]) >> 4, cz = (int) Math.floor(pos[2]) >> 4;
      placed.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(e.get());
    }
  }

  private static long key(int cx, int cz) {
    return (long) cx << 32 | cz & 0xFFFFFFFFL;
  }

  private interface Body {
    void run(RegionFile in, RegionWriter out) throws IOException;
  }

  /** 先寫到暫存目錄再搬過去：伺服器不會看到寫了一半的檔案。 */
  private static boolean write(Path target, Body body, Path source) throws IOException {
    if (Files.exists(target)) throw new IOException("region 檔已存在：" + target);
    Path tmpDir = target.resolveSibling(".buildgit-tmp");
    Files.createDirectories(tmpDir);
    Path tmp = tmpDir.resolve(target.getFileName());
    Files.deleteIfExists(tmp);
    try {
      try (var in = source == null ? null : new RegionFile(source);
          var out = new RegionWriter(tmp)) {
        body.run(in, out);
      }
      // 外部 .mcc 檔要比 region 先就位。
      try (var s = Files.list(tmpDir)) {
        for (var f : s.filter(p -> p.getFileName().toString().endsWith(".mcc")).toList())
          Files.move(f, target.resolveSibling(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      return true;
    } finally {
      Files.deleteIfExists(tmp);
      try {
        Files.deleteIfExists(tmpDir);
      } catch (DirectoryNotEmptyException ignored) {
        // 失敗留下的 .mcc；下次寫入時會被覆蓋
      }
    }
  }
}
