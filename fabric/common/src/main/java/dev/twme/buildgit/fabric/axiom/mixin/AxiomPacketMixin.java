package dev.twme.buildgit.fabric.axiom.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.packets.AxiomServerboundDeleteEntity;
import com.moulberry.axiom.packets.AxiomServerboundFixArea;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundSetBlock;
import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.packets.AxiomServerboundSpawnEntity;
import com.moulberry.axiom.packets.AxiomServerboundTickBlocks;
import dev.twme.buildgit.fabric.axiom.AxiomRecorder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** 會改世界的 Axiom 封包：處理期間的變化記給送出的玩家。 */
@Mixin({AxiomServerboundSetBuffer.class, AxiomServerboundSetBlock.class, AxiomServerboundSpawnEntity.class,
    AxiomServerboundManipulateEntity.class, AxiomServerboundDeleteEntity.class, AxiomServerboundFixArea.class,
    AxiomServerboundTickBlocks.class})
public abstract class AxiomPacketMixin {
  @WrapMethod(method = "handle")
  private void buildgit$handle(MinecraftServer server, ServerPlayer player, Operation<Void> original) {
    try (var cause = AxiomRecorder.cause(player)) {
      original.call(server, player);
    }
  }
}
