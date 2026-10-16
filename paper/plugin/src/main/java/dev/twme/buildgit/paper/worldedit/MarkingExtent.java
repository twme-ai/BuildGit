package dev.twme.buildgit.paper.worldedit;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.paper.track.BukkitTracker;
import java.util.UUID;

/**
 * 原版 WorldEdit（以及 FAWE 不走佇列時）的記錄層：每次寫入只記下位置，不讀舊方塊。
 *
 * <p>CoreProtect 的同類 extent 每格都讀舊方塊與容器內容，大量編輯時明顯變慢；BuildGit 存檔時才讀最終內容，
 * 這裡的成本只有一次雜湊表寫入。
 *
 * <p>這個類別放在 {@code dev.twme.buildgit} 套件下，FAWE 的 {@code allowed-plugins} 以類別名稱判斷，
 * 由 {@link WorldEditHook} 在執行時加入。
 */
public final class MarkingExtent extends AbstractDelegateExtent {
  private final BukkitTracker tracker;
  private final MarkTable.Dimension dimension;
  private final Author author;

  public MarkingExtent(Extent extent, BukkitTracker tracker, MarkTable.Dimension dimension, Author author) {
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

  /** FAWE 的整數座標版本；原版 WorldEdit 沒有這個方法，不會被呼叫。 */
  @Override
  public <B extends BlockStateHolder<B>> boolean setBlock(int x, int y, int z, B block) throws WorldEditException {
    dimension.markCell(x, y, z, author);
    return super.setBlock(x, y, z, block);
  }

  @Override
  public Entity createEntity(Location location, BaseEntity entity) {
    // 實體加入世界時觸發 EntitySpawnEvent，追蹤器以目前原因接手。
    try (var scope = tracker.push(author)) {
      return super.createEntity(location, entity);
    }
  }

  /** FAWE 的指定 UUID 版本。 */
  @Override
  public Entity createEntity(Location location, BaseEntity entity, UUID uuid) {
    try (var scope = tracker.push(author)) {
      return super.createEntity(location, entity, uuid);
    }
  }

  @Override
  public void removeEntity(int x, int y, int z, UUID uuid) {
    dimension.markEntity(uuid, author);
    super.removeEntity(x, y, z, uuid);
  }
}
