package dev.twme.buildgit.paper.nms;

import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.paper.track.Tracker;
import java.lang.reflect.Field;
import java.util.ArrayList;
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
  private final ServerLevel level;
  private final World world;
  private final CollectingNeighborUpdater original;

  private CascadeUpdater(ServerLevel level, int maxChained, Tracker tracker, CollectingNeighborUpdater original) {
    super(level, maxChained);
    this.tracker = tracker;
    this.level = level;
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

  // ---- 判斷 ----
  //
  // 「來源」：剛剛已經改變的格子（updateNeighborsAt 的中心、形狀更新的 neighborPos），直接判斷並標記。
  // 「候選」：收到通知的格子，不一定會變。先記下當時的狀態與可能的作者，等最外層的更新跑完
  // （巢狀的更新會排進佇列，在最外層呼叫裡才執行）再比對；真的變了才標記。
  // 否則只是收到通知、內容沒變的格子也會成為傳染源，旁邊的自然變化（草蔓延）就會被一路記進去。

  private record Candidate(BlockPos pos, BlockState before, MarkTable.Probe probe) {}

  private final ArrayList<Candidate> candidates = new ArrayList<>();
  private int depth;

  private void source(BlockPos pos) {
    MarkTable.Dimension d = tracker.dimension(world);
    if (d != null) tracker.cascade(d, world, pos.getX(), pos.getY(), pos.getZ());
  }

  private void candidate(BlockPos pos) {
    MarkTable.Dimension d = tracker.dimension(world);
    if (d == null) return;
    var probe = tracker.probe(d, world, pos.getX(), pos.getY(), pos.getZ());
    if (probe == null) return;
    var before = level.getBlockStateIfLoaded(pos);
    if (before != null) candidates.add(new Candidate(pos.immutable(), before, probe));
  }

  private void enter() {
    depth++;
  }

  private void exit() {
    if (--depth > 0 || candidates.isEmpty()) return;
    MarkTable.Dimension d = tracker.dimension(world);
    // 標記本身不會再觸發更新，但保險起見先複製再清空。
    var list = new ArrayList<>(candidates);
    candidates.clear();
    if (d == null) return;
    for (var c : list) {
      var now = level.getBlockStateIfLoaded(c.pos());
      if (now != null && now != c.before()) tracker.apply(d, c.pos().getX(), c.pos().getY(), c.pos().getZ(), c.probe());
    }
  }

  @Override
  public void shapeUpdate(Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos, int flags, int updateLimit) {
    enter();
    try {
      source(neighborPos);
      candidate(pos);
      super.shapeUpdate(direction, state, pos, neighborPos, flags, updateLimit);
    } finally {
      exit();
    }
  }

  @Override
  public void neighborChanged(BlockPos pos, Block block, Orientation orientation) {
    enter();
    try {
      candidate(pos);
      super.neighborChanged(pos, block, orientation);
    } finally {
      exit();
    }
  }

  @Override
  public void neighborChanged(BlockState state, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston) {
    enter();
    try {
      candidate(pos);
      super.neighborChanged(state, pos, block, orientation, movedByPiston);
    } finally {
      exit();
    }
  }

  @Override
  public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, Direction skip, Orientation orientation) {
    enter();
    try {
      source(pos);
      super.updateNeighborsAtExceptFromFacing(pos, block, skip, orientation);
    } finally {
      exit();
    }
  }
}
