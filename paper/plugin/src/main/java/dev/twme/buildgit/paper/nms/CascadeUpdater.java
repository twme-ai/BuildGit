package dev.twme.buildgit.paper.nms;

import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.paper.track.Tracker;
import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * 連帶變化的掛點（設計 §3.3）：取代世界的鄰居更新器，在每次形狀更新、鄰居通知時詢問追蹤器。
 *
 * <p>原版所有「一格改變後影響周圍」的路徑（形狀、紅石、流體開始流動、活塞、指令的方塊更新）
 * 都經過 {@code Level.neighborUpdater}。欄位型別是 {@link CollectingNeighborUpdater}，所以用子類別取代。
 * 追蹤器在沒有人剛蓋過東西時直接返回，不加鎖，所以平時幾乎沒有成本。
 */
public final class CascadeUpdater extends CollectingNeighborUpdater {
  private static final Field FIELD;
  private static final Field MAX_CHAINED;

  static {
    try {
      FIELD = net.minecraft.world.level.Level.class.getDeclaredField("neighborUpdater");
      FIELD.setAccessible(true);
      MAX_CHAINED = CollectingNeighborUpdater.class.getDeclaredField("maxChainedNeighborUpdates");
      MAX_CHAINED.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Tracker tracker;
  private final World world;
  private final CollectingNeighborUpdater original;

  private CascadeUpdater(ServerLevel level, int maxChained, Tracker tracker, CollectingNeighborUpdater original) {
    super(level, maxChained);
    this.tracker = tracker;
    this.world = level.getWorld();
    this.original = original;
  }

  /** 換上掛點；已經換過時不重複。失敗時回傳 false，追蹤退回只靠事件。 */
  public static boolean install(World world, Tracker tracker, Logger log) {
    var level = ((CraftWorld) world).getHandle();
    try {
      var current = (CollectingNeighborUpdater) FIELD.get(level);
      if (current instanceof CascadeUpdater) return true;
      FIELD.set(level, new CascadeUpdater(level, MAX_CHAINED.getInt(current), tracker, current));
      return true;
    } catch (ReflectiveOperationException | RuntimeException e) {
      log.log(Level.WARNING, "無法掛上鄰居更新器（" + world.getName() + "）；連帶變化只會以事件近似記錄", e);
      return false;
    }
  }

  public static void uninstall(World world) {
    var level = ((CraftWorld) world).getHandle();
    try {
      if (FIELD.get(level) instanceof CascadeUpdater c) FIELD.set(level, c.original);
    } catch (ReflectiveOperationException ignored) {
      // 伺服器關閉中；世界即將卸載
    }
  }

  private void hook(BlockPos pos) {
    MarkTable.Dimension d = tracker.dimension(world);
    if (d != null) tracker.cascade(d, world, pos.getX(), pos.getY(), pos.getZ());
  }

  @Override
  public void shapeUpdate(Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos, int flags, int updateLimit) {
    hook(neighborPos);
    hook(pos);
    super.shapeUpdate(direction, state, pos, neighborPos, flags, updateLimit);
  }

  @Override
  public void neighborChanged(BlockPos pos, Block block, Orientation orientation) {
    hook(pos);
    super.neighborChanged(pos, block, orientation);
  }

  @Override
  public void neighborChanged(BlockState state, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston) {
    hook(pos);
    super.neighborChanged(state, pos, block, orientation, movedByPiston);
  }

  @Override
  public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, Direction skip, Orientation orientation) {
    hook(pos);
    super.updateNeighborsAtExceptFromFacing(pos, block, skip, orientation);
  }
}
