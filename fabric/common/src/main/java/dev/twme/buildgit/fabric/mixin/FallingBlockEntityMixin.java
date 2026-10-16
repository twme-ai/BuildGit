package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** 掉落方塊落地時放下的方塊，記給讓它掉落的人（設計 §3.3）。 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
  @WrapMethod(method = "tick")
  private void buildgit$tick(Operation<Void> original) {
    try (var cause = Hooks.cause(Hooks.source((FallingBlockEntity) (Object) this))) {
      original.call();
    }
  }
}
