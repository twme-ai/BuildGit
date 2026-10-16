package dev.twme.buildgit.fabric.mixin;

import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家離線。 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
  @Inject(method = "remove", at = @At("HEAD"))
  private void buildgit$quit(ServerPlayer player, CallbackInfo ci) {
    Hooks.quit(player);
  }
}
