package dev.twme.buildgit.fabric.mc.mixin;

import dev.twme.buildgit.fabric.mc.Versions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BuildGit 開的世界讀自己的天氣（26.x 原版整台伺服器一份），建築世界才能各自固定晴天或下雨。 */
@Mixin(ServerLevel.class)
public abstract class WeatherMixin {
  @Inject(method = "getWeatherData", at = @At("HEAD"), cancellable = true)
  private void buildgit$weather(CallbackInfoReturnable<WeatherData> cir) {
    var own = Versions.weather(((ServerLevel) (Object) this).dimension());
    if (own != null) cir.setReturnValue(own);
  }
}
