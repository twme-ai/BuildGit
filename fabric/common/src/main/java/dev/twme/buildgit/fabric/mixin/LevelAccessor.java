package dev.twme.buildgit.fabric.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 換掉世界的鄰居更新器（連帶變化的掛點，設計 §3.3）。 */
@Mixin(Level.class)
public interface LevelAccessor {
  @Accessor("neighborUpdater")
  CollectingNeighborUpdater buildgit$neighborUpdater();

  @Mutable
  @Accessor("neighborUpdater")
  void buildgit$neighborUpdater(CollectingNeighborUpdater updater);
}
