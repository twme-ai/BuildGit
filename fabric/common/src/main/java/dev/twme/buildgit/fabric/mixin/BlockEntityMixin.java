package dev.twme.buildgit.fabric.mixin;

import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 方塊實體的內容變化（容器、告示牌、講台）不經過 setBlockState。 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
  @Shadow protected Level level;
  @Shadow @Final protected BlockPos worldPosition;

  @Inject(method = "setChanged()V", at = @At("HEAD"))
  private void buildgit$changed(CallbackInfo ci) {
    if (level != null) Hooks.blockEntityChanged(level, worldPosition);
  }
}
