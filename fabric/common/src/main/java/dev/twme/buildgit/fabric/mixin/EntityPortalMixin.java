package dev.twme.buildgit.fabric.mixin;

import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 建築世界裡進了傳送門（地獄門、終界門、終界折躍門）也不算數。 */
@Mixin(Entity.class)
public abstract class EntityPortalMixin {
  @Inject(method = "setAsInsidePortal", at = @At("HEAD"), cancellable = true)
  private void buildgit$portal(Portal portal, BlockPos pos, CallbackInfo ci) {
    if (Hooks.portalBlocked((Entity) (Object) this)) ci.cancel();
  }
}
