package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** 火的蔓延與燒毀是自然變化：玩家點的火燒到旁邊的方塊不算他的建築。 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
  @WrapMethod(method = "tick")
  private void buildgit$tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
    Hooks.naturalBegin();
    try {
      original.call(state, level, pos, random);
    } finally {
      Hooks.naturalEnd();
    }
  }
}
