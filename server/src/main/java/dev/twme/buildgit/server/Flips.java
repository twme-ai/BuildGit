package dev.twme.buildgit.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /bg diff flip}：在原地切換「改前／改後」。改前是只送給這位玩家的假方塊，世界不變；
 * 切回來時重送世界裡真正的方塊。玩家點到假方塊、或 chunk 重新送給玩家時，用戶端自然變回真的。
 */
final class Flips {
  /** 一次最多切換幾格；太多時封包量太大。 */
  static final int MAX = 50_000;

  private static final class State {
    final ServerLevel world;
    /** null：格子太多，不能切換。 */
    final Map<BlockPos, BlockState> before;
    boolean shown;

    State(ServerLevel world, Map<BlockPos, BlockState> before) {
      this.world = world;
      this.before = before;
    }
  }

  enum Result { BEFORE, AFTER, NONE, TOO_MANY }

  private final Map<UUID, State> states = new HashMap<>();

  /** 記下最新一次 diff 的改前（主執行緒）；之前切換中的先切回來。{@code before} 為 null 表示太多格。 */
  void set(ServerPlayer p, ServerLevel world, Map<BlockPos, BlockState> before) {
    reset(p);
    states.put(p.getUUID(), new State(world, before));
  }

  Result toggle(ServerPlayer p) {
    var s = states.get(p.getUUID());
    if (s == null || s.world != p.level()) return Result.NONE;
    if (s.before == null) return Result.TOO_MANY;
    s.shown = !s.shown;
    if (s.shown) s.before.forEach((pos, state) -> p.connection.send(new ClientboundBlockUpdatePacket(pos, state)));
    else real(p, s);
    return s.shown ? Result.BEFORE : Result.AFTER;
  }

  /** 不再切換：顯示著改前時送回真的方塊。 */
  void reset(ServerPlayer p) {
    var s = states.remove(p.getUUID());
    if (s != null && s.shown && s.before != null && p.level() == s.world) real(p, s);
  }

  private static void real(ServerPlayer p, State s) {
    for (var pos : s.before.keySet()) p.connection.send(new ClientboundBlockUpdatePacket(s.world, pos));
  }
}
