package dev.twme.buildgit.fabric.worldedit;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.fabric.mc.worldedit.WorldEditWorlds;
import dev.twme.buildgit.server.Platform;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** 讀 WorldEdit 的選取範圍。只在裝了 WorldEdit 時載入這個類別。 */
public final class WorldEditSelection {
  private WorldEditSelection() {}

  public static Optional<Platform.Selection> of(ServerPlayer player) {
    var session = WorldEdit.getInstance().getSessionManager().findByName(player.getScoreboardName());
    if (session == null) return Optional.empty();
    var world = session.getSelectionWorld();
    if (world == null) return Optional.empty();
    var level = WorldEditWorlds.level(world);
    if (level == null) return Optional.empty();
    try {
      var region = session.getSelection(world);
      var min = region.getMinimumPoint();
      var max = region.getMaximumPoint();
      return Optional.of(new Platform.Selection(level, new Box(min.x(), min.y(), min.z(), max.x(), max.y(), max.z())));
    } catch (IncompleteRegionException e) {
      return Optional.empty();
    }
  }
}
