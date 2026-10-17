package dev.twme.buildgit.paper.axiom;

import com.moulberry.axiom.operations.OperationQueue;
import com.moulberry.axiom.operations.PendingOperation;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.paper.track.BukkitTracker;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 記錄有 {@code axiomadmin.bypass_region_checks} 的玩家。
 * AxiomPaper 對這些玩家不詢問整合，沒有公開的掛點，所以讀它的內部：
 *
 * <ul>
 *   <li>方塊緩衝區（筆刷、貼上、填充等）：把 AxiomPaper 的操作佇列換成 {@link Queue}，包住這些玩家的
 *       {@code SetBlockBufferOperation}，第一次執行前與完成後各把緩衝區裡的 section 整個標記一次
 *       （操作可能跨好幾個 tick 等 chunk 載入，中途存檔也不會漏）。佇列在主執行緒、佇列空著時會當場執行，
 *       所以只能在加入佇列時包住，不能事後去看。
 *   <li>單格放置：另外聽 {@code axiom:set_block} 頻道，讀封包開頭的位置表（VarInt 筆數，每筆 BlockPos 與方塊 id）。
 * </ul>
 *
 * <p>生態域筆刷記錄不到。這些都依賴 AxiomPaper 的內部結構：換佇列失敗時整個停用；之後讀不到編輯內容時
 * 也停用並警告一次。兩種情況都不影響 Axiom 本身的編輯。
 */
final class AxiomBypass {
  static final String PERMISSION = "axiomadmin.bypass_region_checks";
  private static final String SET_BLOCK = "axiom:set_block";
  private static final String BUFFER_OPERATION = "com.moulberry.axiom.operations.SetBlockBufferOperation";

  private final BukkitTracker tracker;
  private final BitSet full;
  private final Logger log;
  private Field queueField;
  private Object axiom;
  private OperationQueue original;
  private Field bufferField;
  private Method entrySet;
  private boolean failed;

  AxiomBypass(BukkitTracker tracker, BitSet full, Logger log) {
    this.tracker = tracker;
    this.full = full;
    this.log = log;
  }

  /** 換上佇列並聽單格放置；失敗時回傳 false，什麼都不改。 */
  boolean install(Plugin plugin, Plugin axiomPlugin) {
    try {
      queueField = axiomPlugin.getClass().getDeclaredField("operationQueue");
      queueField.setAccessible(true);
      original = (OperationQueue) queueField.get(axiomPlugin);
      queueField.set(axiomPlugin, new Queue());
      axiom = axiomPlugin;
    } catch (ReflectiveOperationException | RuntimeException e) {
      log.log(Level.WARNING, "無法記錄有 " + PERMISSION + " 的玩家（AxiomPaper 版本不相容？）", e);
      return false;
    }
    plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SET_BLOCK, (channel, player, data) -> setBlock(player, data));
    log.info("記錄有 " + PERMISSION + " 的玩家的 Axiom 方塊編輯");
    return true;
  }

  /** 換回原本的佇列：BuildGit 卸載後 AxiomPaper 不能再呼叫到這裡的類別。 */
  void uninstall() {
    if (axiom == null) return;
    try {
      queueField.set(axiom, original);
    } catch (ReflectiveOperationException e) {
      log.log(Level.WARNING, "無法換回 AxiomPaper 的操作佇列", e);
    }
    axiom = null;
  }

  /** 還記得到：換上了佇列，也還沒遇過讀不懂的內容。 */
  boolean working() {
    return axiom != null && !failed;
  }

  private static boolean bypass(ServerPlayer player) {
    return player != null && player.getBukkitEntity().hasPermission(PERMISSION);
  }

  private void setBlock(Player player, byte[] data) {
    if (failed || !player.hasPermission(PERMISSION)) return; // 其他人經過 canPlaceBlock，已經記下
    var author = tracker.author(player);
    World world = player.getWorld();
    try {
      var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
      int count = buf.readVarInt();
      for (int i = 0; i < count; i++) {
        var pos = buf.readBlockPos();
        buf.readVarInt();
        tracker.markAround(world.getBlockAt(pos.getX(), pos.getY(), pos.getZ()), author);
      }
    } catch (RuntimeException e) {
      warnOnce(e); // 格式對不上：Axiom 照常處理，只是記不到
    }
  }

  /** 標記緩衝區裡所有的 section。 */
  private void markBuffer(PendingOperation operation, World world, Author author) {
    if (failed) return;
    try {
      if (bufferField == null) {
        bufferField = operation.getClass().getDeclaredField("buffer");
        bufferField.setAccessible(true);
      }
      var buffer = bufferField.get(operation);
      if (entrySet == null) entrySet = buffer.getClass().getMethod("entrySet");
      for (var entry : (Iterable<?>) entrySet.invoke(buffer)) {
        long key = ((Long2ObjectMap.Entry<?>) entry).getLongKey();
        tracker.markSection(world, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), full, author);
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      warnOnce(e);
    }
  }

  private void warnOnce(Exception e) {
    if (failed) return;
    failed = true;
    log.log(Level.WARNING, "讀不到 AxiomPaper 的編輯內容（版本不相容？），有 " + PERMISSION + " 的玩家的方塊編輯不會被記錄", e);
  }

  /** 換上 AxiomPaper 的操作佇列：只包住 bypass 玩家的方塊緩衝區，其餘原樣交給父類別。 */
  private final class Queue extends OperationQueue {
    @Override
    public void add(ServerLevel level, PendingOperation operation) {
      if (operation.getClass().getName().equals(BUFFER_OPERATION) && bypass(operation.executor()))
        operation = new Recorded(operation, level.getWorld(), tracker.author(operation.executor().getBukkitEntity()));
      super.add(level, operation);
    }
  }

  private final class Recorded implements PendingOperation {
    private final PendingOperation operation;
    private final World world;
    private final Author author;
    private boolean started;

    Recorded(PendingOperation operation, World world, Author author) {
      this.operation = operation;
      this.world = world;
      this.author = author;
    }

    @Override
    public void tick(ServerLevel level) {
      if (!started) {
        started = true;
        markBuffer(operation, world, author);
      }
      operation.tick(level);
      if (operation.isFinished()) markBuffer(operation, world, author);
    }

    @Override
    public boolean isFinished() {
      return operation.isFinished();
    }

    @Override
    public ServerPlayer executor() {
      return operation.executor();
    }
  }
}
