package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 玩家破壞、放置、使用物品：期間的變化記給該玩家；檢查模式與檢視世界在這裡擋下。 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
  @Shadow @Final protected ServerPlayer player;
  @Shadow protected ServerLevel level;

  @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
  private void buildgit$breakAction(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction face, int maxY, int sequence,
      CallbackInfo ci) {
    boolean cancel = action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && Hooks.inspect(player, pos, face, false, true);
    if (!cancel) cancel = Hooks.readOnly(player);
    if (!cancel) return;
    // 客戶端已經預測方塊被敲掉，送回原本的方塊
    player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
    player.connection.ackBlockChangesUpTo(sequence);
    ci.cancel();
  }

  @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
  private void buildgit$useItemOnCheck(ServerPlayer p, Level l, ItemStack stack, InteractionHand hand, BlockHitResult hit,
      CallbackInfoReturnable<InteractionResult> cir) {
    if (Hooks.inspect(p, hit.getBlockPos(), hit.getDirection(), true, hand == InteractionHand.MAIN_HAND) || Hooks.readOnly(p))
      cir.setReturnValue(InteractionResult.FAIL);
  }

  @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
  private void buildgit$useItemCheck(ServerPlayer p, Level l, ItemStack stack, InteractionHand hand,
      CallbackInfoReturnable<InteractionResult> cir) {
    if (Hooks.readOnly(p)) cir.setReturnValue(InteractionResult.FAIL);
  }

  @WrapMethod(method = "destroyBlock")
  private boolean buildgit$destroy(BlockPos pos, Operation<Boolean> original) {
    if (Hooks.readOnly(player)) return false;
    try (var cause = Hooks.cause(player)) {
      return original.call(pos);
    }
  }

  @WrapMethod(method = "useItemOn")
  private InteractionResult buildgit$useItemOn(ServerPlayer p, Level l, ItemStack stack, InteractionHand hand, BlockHitResult hit,
      Operation<InteractionResult> original) {
    try (var cause = Hooks.cause(p)) {
      return original.call(p, l, stack, hand, hit);
    }
  }

  @WrapMethod(method = "useItem")
  private InteractionResult buildgit$useItem(ServerPlayer p, Level l, ItemStack stack, InteractionHand hand,
      Operation<InteractionResult> original) {
    try (var cause = Hooks.cause(p)) {
      return original.call(p, l, stack, hand);
    }
  }
}
