package dev.twme.buildgit.fabric.axiom.mixin;

import com.moulberry.axiom.packets.AxiomServerboundSetBlock;
import dev.twme.buildgit.fabric.axiom.AxiomRecorder;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 單格放置：不更新形狀的放置可能不經過 setBlockState 的鄰居更新，每格連同鄰格記下。 */
@Mixin(AxiomServerboundSetBlock.class)
public abstract class SetBlockMixin {
  @Shadow @Final private Map<BlockPos, BlockState> blocks;

  @Inject(method = "handle", at = @At("HEAD"))
  private void buildgit$handle(MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
    AxiomRecorder.blocks(player, blocks.keySet());
  }
}
