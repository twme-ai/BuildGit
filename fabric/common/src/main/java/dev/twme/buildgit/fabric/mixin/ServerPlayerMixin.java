package dev.twme.buildgit.fabric.mixin;

import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 玩家換了世界（傳送門、/bg go、/tp 到其他維度都會經過這裡）。 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
  @Inject(method = "triggerDimensionChangeTriggers", at = @At("HEAD"))
  private void buildgit$changed(ServerLevel from, CallbackInfo ci) {
    Hooks.changedWorld((ServerPlayer) (Object) this, from);
  }

  /** 死後重生的地方（重生時用死掉的玩家呼叫，這時還在死掉的世界）：建築世界改成那個世界的出生點。 */
  @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
  private void buildgit$respawn(boolean useCharge, TeleportTransition.PostTeleportTransition post, CallbackInfoReturnable<TeleportTransition> cir) {
    var self = (ServerPlayer) (Object) this;
    if (!self.isDeadOrDying()) return;
    var r = Hooks.respawn(self.level(), cir.getReturnValue().newLevel());
    if (r != null) cir.setReturnValue(new TeleportTransition(r.level(), new Vec3(r.x(), r.y(), r.z()), Vec3.ZERO, r.yaw(), r.pitch(), post));
  }
}
