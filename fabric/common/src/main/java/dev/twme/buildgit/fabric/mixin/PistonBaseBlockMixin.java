package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;

/** 活塞推動的方塊，記給剛動過活塞附近的人。 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
  @WrapMethod(method = "moveBlocks")
  private boolean buildgit$move(Level level, BlockPos pos, Direction direction, boolean extending, Operation<Boolean> original) {
    try (var cause = Hooks.cause(Hooks.recent(level, pos))) {
      return original.call(level, pos, direction, extending);
    }
  }
}
