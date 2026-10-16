package dev.twme.buildgit.fabric.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** 玩家右鍵實體（展示框放物品、轉向，盔甲座換裝備）：26.2 一律經過這裡。 */
@Mixin(Player.class)
public abstract class InteractMixin {
  @WrapMethod(method = "interactOn")
  private InteractionResult buildgit$interact(Entity target, InteractionHand hand, Vec3 location, Operation<InteractionResult> original) {
    if (!((Object) this instanceof ServerPlayer player)) return original.call(target, hand, location);
    if (Hooks.readOnly(player)) return InteractionResult.FAIL;
    Hooks.touch(player, target);
    try (var cause = Hooks.cause(player)) {
      return original.call(target, hand, location);
    }
  }
}
