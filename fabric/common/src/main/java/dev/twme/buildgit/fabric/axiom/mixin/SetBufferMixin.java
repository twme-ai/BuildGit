package dev.twme.buildgit.fabric.axiom.mixin;

import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.render.regions.ChunkedBooleanRegion;
import com.moulberry.axiom.world_modification.BiomeBuffer;
import com.moulberry.axiom.world_modification.BlockBuffer;
import dev.twme.buildgit.fabric.axiom.AxiomRecorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 方塊與生態域緩衝區直接寫進 section：寫入之前記下（同步執行，寫完前不會存檔）。 */
@Mixin(AxiomServerboundSetBuffer.class)
public abstract class SetBufferMixin {
  @Inject(method = "applyBlockBufferServer", at = @At("HEAD"))
  private static void buildgit$blocks(BlockBuffer buffer, ServerLevel level, ChunkedBooleanRegion region, ServerPlayer player,
      CallbackInfo ci) {
    AxiomRecorder.blockBuffer(buffer, level, player);
  }

  @Inject(method = "applyBiomeBufferServer", at = @At("HEAD"))
  private static void buildgit$biomes(BiomeBuffer buffer, ServerLevel level, CallbackInfo ci) {
    AxiomRecorder.biomeBuffer(buffer, level);
  }
}
