package dev.twme.buildgit.fabric.mc.worldedit;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.world.World;
import net.minecraft.server.level.ServerLevel;

/** WorldEdit 的世界轉成原版世界（WorldEdit 7.4.2，Minecraft 1.21.11）。 */
public final class WorldEditWorlds {
  private WorldEditWorlds() {}

  /** @return 不是伺服器世界時為 null */
  public static ServerLevel level(World world) {
    try {
      return FabricAdapter.adapt(world) instanceof ServerLevel level ? level : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
