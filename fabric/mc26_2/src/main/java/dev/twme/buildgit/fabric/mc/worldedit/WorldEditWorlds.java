package dev.twme.buildgit.fabric.mc.worldedit;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.world.World;
import net.minecraft.server.level.ServerLevel;

/** WorldEdit 的世界轉成原版世界。WorldEdit 7.4.5 起（Minecraft 26）經過共用的 core-mc 轉接器。 */
public final class WorldEditWorlds {
  private WorldEditWorlds() {}

  /** @return 不是伺服器世界時為 null */
  public static ServerLevel level(World world) {
    try {
      return FabricAdapter.get().toNativeWorld(world);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
