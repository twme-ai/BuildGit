package dev.twme.buildgit.fabric.mixin;

import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 實體加入、離開世界（載入、生成、卸載、死亡、換維度）。 */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class EntityCallbacksMixin {
  @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
  private void buildgit$loaded(Entity entity, CallbackInfo ci) {
    Hooks.entityLoaded(entity);
  }

  @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
  private void buildgit$removed(Entity entity, CallbackInfo ci) {
    Hooks.entityRemoved(entity);
  }
}
