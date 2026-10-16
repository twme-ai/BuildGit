package dev.twme.buildgit.fabric.mixin;

import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 方塊寫入的單一入口：回傳非 null 表示這一格真的變了。 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
  @Shadow @Final Level level;

  @Inject(method = "setBlockState", at = @At("RETURN"))
  private void buildgit$changed(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
    if (cir.getReturnValue() != null) Hooks.blockChanged(level, pos);
  }
}
