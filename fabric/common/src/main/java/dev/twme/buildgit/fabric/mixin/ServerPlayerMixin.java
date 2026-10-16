package dev.twme.buildgit.fabric.mixin;

import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家換了世界（傳送門、/bg go、/tp 到其他維度都會經過這裡）。 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
  @Inject(method = "triggerDimensionChangeTriggers", at = @At("HEAD"))
  private void buildgit$changed(ServerLevel from, CallbackInfo ci) {
    Hooks.changedWorld((ServerPlayer) (Object) this, from);
  }
}
