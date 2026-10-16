package dev.twme.buildgit.fabric.mixin;

import dev.twme.buildgit.fabric.Synthesis;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** region 檔第一次開啟前合成分支世界的內容。 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
  @Shadow @Final private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;
  @Shadow @Final private Path folder;

  @Inject(method = "getRegionFile", at = @At("HEAD"))
  private void buildgit$synthesize(ChunkPos pos, CallbackInfoReturnable<RegionFile> cir) {
    int rx = pos.getRegionX(), rz = pos.getRegionZ();
    if (!regionCache.containsKey((rx & 0xFFFFFFFFL) | (rz & 0xFFFFFFFFL) << 32)) Synthesis.beforeOpen(folder, rx, rz);
  }
}
