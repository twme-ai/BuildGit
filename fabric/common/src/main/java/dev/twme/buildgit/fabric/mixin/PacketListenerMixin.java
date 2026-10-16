package dev.twme.buildgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 容器點擊（箱子內容）、容器按鈕（講台取書）、告示牌編輯：期間的方塊實體變化記給該玩家。
 * 封包先在網路執行緒進來、再排回主執行緒重跑；兩次都設定原因無妨，範圍結束就還原。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PacketListenerMixin {
  @Shadow public ServerPlayer player;

  @WrapMethod(method = "handleContainerClick")
  private void buildgit$click(ServerboundContainerClickPacket packet, Operation<Void> original) {
    try (var cause = Hooks.cause(player)) {
      original.call(packet);
    }
  }

  @WrapMethod(method = "handleContainerButtonClick")
  private void buildgit$button(ServerboundContainerButtonClickPacket packet, Operation<Void> original) {
    try (var cause = Hooks.cause(player)) {
      original.call(packet);
    }
  }

  @WrapMethod(method = "handleSignUpdate")
  private void buildgit$sign(ServerboundSignUpdatePacket packet, Operation<Void> original) {
    try (var cause = Hooks.cause(player)) {
      original.call(packet);
    }
  }
}
