package dev.twme.buildgit.paper.worldedit;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import dev.twme.buildgit.core.model.Box;
import org.bukkit.entity.Player;

/** 讀 WorldEdit／FAWE 的選取範圍。只在其中一個插件存在時載入這個類別。 */
public final class WorldEditSelection {
  public record Result(String world, Box box) {}

  private WorldEditSelection() {}

  /** @return 沒有完整選取時為 null */
  public static Result of(Player player) {
    var session = WorldEdit.getInstance().getSessionManager().findByName(player.getName());
    if (session == null) return null;
    var world = session.getSelectionWorld();
    if (world == null) return null;
    try {
      var region = session.getSelection(world);
      var min = region.getMinimumPoint();
      var max = region.getMaximumPoint();
      return new Result(world.getName(), new Box(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
    } catch (IncompleteRegionException e) {
      return null;
    }
  }
}
