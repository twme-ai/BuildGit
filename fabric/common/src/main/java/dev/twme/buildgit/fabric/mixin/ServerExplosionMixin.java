package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;

/** 玩家點的 TNT 炸掉的方塊記給他；沒有作者的爆炸（苦力怕）是自然變化。 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
  @WrapMethod(method = "explode")
  private int buildgit$explode(Operation<Integer> original) {
    var author = Hooks.source(((ServerExplosion) (Object) this).getDirectSourceEntity());
    if (author != null) {
      try (var cause = Hooks.cause(author)) {
        return original.call();
      }
    }
    Hooks.naturalBegin();
    try {
      return original.call();
    } finally {
      Hooks.naturalEnd();
    }
  }
}
