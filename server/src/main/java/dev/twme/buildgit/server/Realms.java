package dev.twme.buildgit.server;

import java.util.*;
import java.util.logging.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 進出建築世界（03-worlds §2）：誰能進去、傳送到出生點、回伺服器世界，以及失去資格的人送回去。
 */
final class Realms {
  private record Location(ServerLevel level, double x, double y, double z, float yaw, float pitch) {}

  private final BuildGit bg;
  /** 進入建築世界之前在伺服器世界的位置，{@code home} 回到這裡。 */
  private final Map<UUID, Location> homes = new HashMap<>();

  Realms(BuildGit bg) {
    this.bg = bg;
  }

  boolean admin(ServerPlayer p) {
    return bg.platform().hasPermission(p, "buildgit.world.admin");
  }

  /** 能不能進去：伺服器世界都可以；建築世界要是成員，或世界是公開的；管理員都可以。 */
  boolean canEnter(ServerPlayer p, Workspace ws) {
    return ws.buildWorld() == null || ws.role(p.getUUID()) != null || admin(p);
  }

  /** 傳送到建築世界的出生點（第一次有人進來時才決定）。從伺服器世界進來時記下位置給 {@link #home}。 */
  void enter(ServerPlayer p, Workspace ws) throws java.io.IOException {
    if (bg.workspace(p.level()).buildWorld() == null)
      homes.put(p.getUUID(), new Location(p.level(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
    var level = ws.openMain();
    var sender = bg.platform().sender(p);
    ws.spawn(bg.tracker().author(p), bg.messages().raw("world-first-commit")).whenComplete((spawn, e) -> ws.sync(() -> {
      if (e != null) {
        bg.platform().log().log(Level.WARNING, "無法決定建築世界 " + ws.label() + " 的出生點", e);
        bg.messages().send(sender, "branch-failed", "error", String.valueOf(Workspace.unwrap(e).getMessage()));
        return;
      }
      bg.platform().teleport(p, level, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
      bg.messages().send(sender, "world-entered", "world", ws.label());
    }));
  }

  /** 回到進入建築世界之前的位置；不知道時到伺服器主世界的 (0, 0)。 */
  void home(ServerPlayer p) {
    var saved = homes.remove(p.getUUID());
    if (saved != null && bg.workspace().loaded(saved.level()))
      bg.platform().teleport(p, saved.level(), saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
    else {
      var level = bg.platform().server().overworld();
      bg.platform().loadChunk(level, 0, 0).thenAccept(chunk -> bg.workspace().sync(() -> bg.platform().teleport(p, level, 0.5,
          level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0), 0.5, p.getYRot(), p.getXRot())));
    }
  }

  /** 在這個建築世界（含草稿、檢視世界）裡、已經沒有資格的人送回伺服器世界（成員被移除、改成私人時）。 */
  void evict(Workspace ws) {
    for (var p : List.copyOf(bg.platform().server().getPlayerList().getPlayers()))
      if (bg.workspace(p.level()) == ws && !canEnter(p, ws)) {
        bg.messages().send(bg.platform().sender(p), "world-evicted", "world", ws.label());
        home(p);
      }
  }

  /** 上線時在沒有資格的建築世界裡（離線期間被移除、改成私人）：送回伺服器世界。 */
  void joined(ServerPlayer p) {
    var ws = bg.workspace(p.level());
    if (ws.buildWorld() != null && !canEnter(p, ws)) {
      bg.messages().send(bg.platform().sender(p), "world-evicted", "world", ws.label());
      home(p);
    }
  }
}
