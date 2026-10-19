package dev.twme.buildgit.fabric.axiom;

import com.moulberry.axiom.packets.AxiomServerboundDeleteEntity;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundSetBlock;
import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.render.regions.ChunkedBooleanRegion;
import com.moulberry.axiom.world_modification.BiomeBuffer;
import com.moulberry.axiom.world_modification.BlockBuffer;
import dev.twme.buildgit.fabric.Hooks;
import dev.twme.buildgit.server.track.Tracker;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Fabric 版 Axiom 的記錄（設計 §3.9）。伺服器端跑的是 Axiom 模組本體，沒有給其他模組的整合 API，
 * 所以以 mixin 掛在它的伺服器封包上（{@code dev.twme.buildgit.fabric.axiom.mixin}）：
 *
 * <ul>
 *   <li>每個封包處理期間設定玩家原因：經過 {@code LevelChunk.setBlockState} 的變化、新加入的實體、連帶變化都記給他；
 *   <li>方塊緩衝區（筆刷、貼上、填充）直接寫進 section，不經過 {@code setBlockState}：寫入前標記緩衝區裡有寫到的格子；
 *   <li>生態域緩衝區：每個生態域格記一格；
 *   <li>單格放置：每一格與它的鄰格（不更新形狀的放置）；
 *   <li>修改、刪除實體：前後各把整串（最外層載具與所有乘客）記下。
 * </ul>
 *
 * <p>這些依賴 Axiom 的內部類別：啟動時先確認都在（{@link #check}），對不上就整個停用並警告，不影響 Axiom 本身。
 */
public final class AxiomRecorder {
  /** 設定開啟、Axiom 的內部也對得上時為 true。 */
  private static volatile boolean enabled;

  private AxiomRecorder() {}

  /** 確認 mixin 掛的類別、方法、欄位都在；對得上才開始記錄。 */
  public static void check(Logger log) {
    try {
      AxiomServerboundSetBuffer.class.getDeclaredMethod("applyBlockBufferServer", BlockBuffer.class, ServerLevel.class,
          ChunkedBooleanRegion.class, ServerPlayer.class);
      AxiomServerboundSetBuffer.class.getDeclaredMethod("applyBiomeBufferServer", BiomeBuffer.class, ServerLevel.class);
      AxiomServerboundSetBlock.class.getDeclaredField("blocks");
      AxiomServerboundManipulateEntity.class.getDeclaredField("entries");
      AxiomServerboundDeleteEntity.class.getDeclaredField("delete");
      enabled = true;
      log.info("記錄 Axiom 的編輯");
    } catch (ReflectiveOperationException | LinkageError e) {
      enabled = false;
      log.warning("這個版本的 Axiom 和 BuildGit 對不上（" + e + "），Axiom 的編輯不會被記錄");
    }
  }

  public static void disable() {
    enabled = false;
  }

  public static Tracker.Scope cause(ServerPlayer player) {
    return enabled ? Hooks.cause(player) : null;
  }

  public static void blockBuffer(BlockBuffer buffer, ServerLevel level, ServerPlayer player) {
    var t = Hooks.tracker();
    if (!enabled || t == null) return;
    var author = t.author(player);
    for (var entry : buffer.entrySet()) {
      // 沒寫到的格子是 EMPTY_STATE（保留原樣）
      var section = entry.getValue();
      var cells = new BitSet(4096);
      for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++) if (section.get(x, y, z) != BlockBuffer.EMPTY_STATE) cells.set((y << 8) | (z << 4) | x);
      long key = entry.getLongKey();
      t.markSection(level, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), cells, author);
    }
  }

  /** 生態域格座標（方塊座標 ÷ 4）；作者是處理封包期間設定的原因。 */
  public static void biomeBuffer(BiomeBuffer buffer, ServerLevel level) {
    var t = Hooks.tracker();
    if (!enabled || t == null) return;
    var author = t.cause();
    if (author == null) return;
    buffer.forEachEntry((x, y, z, biome) -> t.markBlock(level, x << 2, y << 2, z << 2, author));
  }

  public static void blocks(ServerPlayer player, Collection<BlockPos> positions) {
    var t = Hooks.tracker();
    if (!enabled || t == null) return;
    var author = t.author(player);
    for (var p : positions) t.markAround(player.level(), p.getX(), p.getY(), p.getZ(), author);
  }

  public static void entities(ServerPlayer player, Collection<UUID> uuids) {
    var t = Hooks.tracker();
    if (!enabled || t == null) return;
    var author = t.author(player);
    for (var uuid : uuids) {
      var entity = player.level().getEntity(uuid);
      if (entity != null) for (var e : stack(entity)) t.markEntity(e, author);
    }
  }

  /** 實體所在的整串：最外層的載具與它所有的乘客。 */
  private static List<Entity> stack(Entity entity) {
    var result = new java.util.ArrayList<Entity>();
    var pending = new ArrayDeque<Entity>(List.of(entity.getRootVehicle()));
    while (!pending.isEmpty()) {
      var e = pending.poll();
      result.add(e);
      pending.addAll(e.getPassengers());
    }
    return result;
  }
}
