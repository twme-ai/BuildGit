package dev.twme.buildgit.server;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import java.util.*;
import java.util.logging.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家的選取範圍（設計 §6.4）：依序取自 WorldEdit／FAWE 的選取，再來是 {@code /bg sel pos1|pos2}。
 * Axiom 的選取只存在客戶端，伺服器拿不到。
 */
final class Selections {
  private record Corner(ServerLevel world, BlockPos pos) {}

  private final Platform platform;
  private final Map<UUID, Corner[]> corners = new HashMap<>();
  private boolean worldEditBroken;

  Selections(Platform platform) {
    this.platform = platform;
  }

  void set(ServerPlayer player, int index, BlockPos pos) {
    corners.computeIfAbsent(player.getUUID(), k -> new Corner[2])[index] = new Corner(player.level(), pos);
  }

  void clear(ServerPlayer player) {
    corners.remove(player.getUUID());
  }

  Optional<Platform.Selection> get(ServerPlayer player) {
    if (!worldEditBroken) {
      try {
        var we = platform.worldEditSelection(player);
        if (we.isPresent()) return we;
      } catch (LinkageError | RuntimeException e) {
        worldEditBroken = true;
        platform.log().log(Level.WARNING, "無法讀取 WorldEdit 選取範圍，改用 /bg sel", e);
      }
    }
    var c = corners.get(player.getUUID());
    if (c == null || c[0] == null || c[1] == null || c[0].world() != c[1].world()) return Optional.empty();
    var world = c[0].world();
    if (platform.server().getLevel(world.dimension()) != world) return Optional.empty();
    return Optional.of(new Platform.Selection(world, Box.of(c[0].pos(), c[1].pos())));
  }

  void quit(UUID player) {
    corners.remove(player);
  }
}
