package dev.twme.buildgit.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 世界資料夾的位置。 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
  @Accessor("storageSource")
  LevelStorageSource.LevelStorageAccess buildgit$storage();
}
