package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;

/** 玩家射出的投射物：射中按鈕、標靶、裝飾陶罐、展示框、盔甲座。 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
  @WrapMethod(method = "onHit")
  private void buildgit$hit(HitResult hit, Operation<Void> original) {
    var author = Hooks.source((Projectile) (Object) this);
    if (author != null && hit instanceof EntityHitResult e) Hooks.touch(e.getEntity(), author);
    try (var cause = Hooks.cause(author)) {
      original.call(hit);
    }
  }
}
