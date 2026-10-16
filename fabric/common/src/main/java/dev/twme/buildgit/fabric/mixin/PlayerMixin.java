package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** 玩家攻擊實體：打掉展示框的物品、打壞盔甲座、拆掉畫。右鍵互動的簽名隨版本不同，在各版本的 mixin。 */
@Mixin(Player.class)
public abstract class PlayerMixin {
  @WrapMethod(method = "attack")
  private void buildgit$attack(Entity target, Operation<Void> original) {
    if (!((Object) this instanceof ServerPlayer player)) {
      original.call(target);
      return;
    }
    if (Hooks.readOnly(player)) return;
    Hooks.touch(player, target);
    try (var cause = Hooks.cause(player)) {
      original.call(target);
    }
  }
}
