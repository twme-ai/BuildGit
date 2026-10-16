package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.sugar.Local;
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

/**
 * region 檔第一次開啟前合成分支世界的內容。
 *
 * <p>26.3 起 {@code getRegionFile} 多了一個參數、快取的值改成 {@code Optional<RegionFile>}；
 * 這裡只取 {@code ChunkPos} 參數、只看快取有沒有這個鍵，所以各版本共用。
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
  @Shadow @Final private Long2ObjectLinkedOpenHashMap<?> regionCache;
  @Shadow @Final private Path folder;

  @Inject(method = "getRegionFile", at = @At("HEAD"))
  private void buildgit$synthesize(CallbackInfoReturnable<RegionFile> cir, @Local(argsOnly = true) ChunkPos pos) {
    int rx = pos.getRegionX(), rz = pos.getRegionZ();
    if (!regionCache.containsKey((rx & 0xFFFFFFFFL) | (rz & 0xFFFFFFFFL) << 32)) Synthesis.beforeOpen(folder, rx, rz);
  }
}
