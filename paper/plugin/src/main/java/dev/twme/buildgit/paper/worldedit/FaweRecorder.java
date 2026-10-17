package dev.twme.buildgit.paper.worldedit;

import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.paper.track.BukkitTracker;
import java.util.BitSet;
import java.util.UUID;
import org.bukkit.World;

/**
 * FAWE 佇列模式的記錄器：FAWE 把整個 chunk 的寫入批次送到這裡，我們只掃描每個 section 的
 * 「有設定」位元，整批標記。不讀世界、不逐格呼叫，所以對 FAWE 的速度幾乎沒有影響。
 *
 * <p>作為 {@link ProcessorScope#READING_BLOCKS} 執行：在遮罩、範圍限制等會改動寫入內容的處理器之後，
 * 看到的就是真正要寫入的格子。同一個 chunk 可能被處理多次，標記是冪等的。
 */
final class FaweRecorder implements IBatchProcessor {
  private final BukkitTracker tracker;
  private final World world;
  private final MarkTable.Dimension dimension;
  private final Author author;

  FaweRecorder(BukkitTracker tracker, World world, MarkTable.Dimension dimension, Author author) {
    this.tracker = tracker;
    this.world = world;
    this.dimension = dimension;
    this.author = author;
  }

  @Override
  public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
    int cx = chunk.getX(), cz = chunk.getZ();
    var bits = new BitSet(4096);
    for (int layer = set.getMinSectionPosition(); layer <= set.getMaxSectionPosition(); layer++) {
      char[] blocks = set.loadIfPresent(layer);
      if (blocks == null) continue;
      bits.clear();
      // 0 是 FAWE 的「未設定」；其他值（包括空氣）都是寫入。
      for (int i = 0; i < blocks.length; i++) if (blocks[i] != 0) bits.set(i);
      dimension.markSection(cx, layer, cz, bits, author);
    }
    // 生態域：每個有寫入的 section 記一格，存檔時會比較整個 section 的生態域。
    if (set.hasBiomes())
      for (int layer = set.getMinSectionPosition(); layer <= set.getMaxSectionPosition(); layer++)
        if (set.hasBiomes(layer)) dimension.markCell(cx << 4, layer << 4, cz << 4, author);
    for (BlockVector3 v : set.tiles().keySet()) dimension.markCell(v.x(), v.y(), v.z(), author);
    for (UUID removed : set.getEntityRemoves()) dimension.markEntityRemoved(removed, author);
    if (hasEntities(set)) tracker.expectSpawns(world, cx, cz, author);
    return set;
  }

  private static boolean hasEntities(IChunkSet set) {
    try {
      return !set.entities().isEmpty();
    } catch (LinkageError e) {
      return true; // FAWE 版本的簽章不同：保守地當作有實體
    }
  }

  @Override
  public Extent construct(Extent child) {
    return child;
  }

  @Override
  public ProcessorScope getScope() {
    return ProcessorScope.READING_BLOCKS;
  }
}
