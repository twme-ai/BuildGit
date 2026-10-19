package dev.twme.buildgit.fabric.worldedit;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.server.track.Tracker;

/** WorldEdit 的記錄層：每次寫入只記下位置，不讀舊方塊（同 Paper 版）。 */
final class MarkingExtent extends AbstractDelegateExtent {
  private final Tracker tracker;
  private final MarkTable.Dimension dimension;
  private final Author author;

  MarkingExtent(Extent extent, Tracker tracker, MarkTable.Dimension dimension, Author author) {
    super(extent);
    this.tracker = tracker;
    this.dimension = dimension;
    this.author = author;
  }

  @Override
  public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block) throws WorldEditException {
    dimension.markCell(position.x(), position.y(), position.z(), author);
    return super.setBlock(position, block);
  }

  /** 生態域：記下那一格，存檔時會比較它所在 section 的生態域。 */
  @Override
  public boolean setBiome(BlockVector3 position, BiomeType biome) {
    dimension.markCell(position.x(), position.y(), position.z(), author);
    return super.setBiome(position, biome);
  }

  @Override
  public Entity createEntity(Location location, BaseEntity entity) {
    // 實體加入世界時經過 ServerLevel.addFreshEntity 的掛點，以目前原因接手
    try (var scope = tracker.push(author)) {
      return super.createEntity(location, entity);
    }
  }
}
