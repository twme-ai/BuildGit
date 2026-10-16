package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
  /** 隨機刻（生長、蔓延、融化、樹葉腐爛）、降雪結冰、雷擊：都是自然變化，不讓連帶歸屬算給玩家。 */
  @WrapMethod(method = "tickChunk")
  private void buildgit$natural(LevelChunk chunk, int randomTickSpeed, Operation<Void> original) {
    Hooks.naturalBegin();
    try {
      original.call(chunk, randomTickSpeed);
    } finally {
      Hooks.naturalEnd();
    }
  }

  @Inject(method = "addFreshEntity", at = @At("HEAD"))
  private void buildgit$spawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
    Hooks.spawn(entity);
  }
}
