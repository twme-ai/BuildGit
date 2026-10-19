package dev.twme.buildgit.fabric.axiom.mixin;

import com.moulberry.axiom.packets.AxiomServerboundDeleteEntity;
import dev.twme.buildgit.fabric.axiom.AxiomRecorder;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 刪除實體：刪之前把整串記下。 */
@Mixin(AxiomServerboundDeleteEntity.class)
public abstract class DeleteEntityMixin {
  @Shadow @Final private List<UUID> delete;

  @Inject(method = "handle", at = @At("HEAD"))
  private void buildgit$handle(MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
    AxiomRecorder.entities(player, delete);
  }
}
