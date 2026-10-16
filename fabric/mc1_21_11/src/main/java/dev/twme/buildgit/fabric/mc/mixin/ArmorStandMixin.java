package dev.twme.buildgit.fabric.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** 1.21.11 的盔甲座換裝備走 interactAt，不經過 Player.interactOn。 */
@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin {
  @WrapMethod(method = "interactAt")
  private InteractionResult buildgit$interact(Player player, Vec3 location, InteractionHand hand, Operation<InteractionResult> original) {
    if (!(player instanceof ServerPlayer p)) return original.call(player, location, hand);
    if (Hooks.readOnly(p)) return InteractionResult.FAIL;
    Hooks.touch(p, (ArmorStand) (Object) this);
    try (var cause = Hooks.cause(p)) {
      return original.call(player, location, hand);
    }
  }
}
