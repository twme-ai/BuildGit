package dev.twme.buildgit.server;

import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

/** 指令的執行者：玩家或主控台（含指令方塊）。 */
public interface Sender {
  void send(Component message);

  boolean hasPermission(String permission);

  /** 玩家；主控台時為 null。 */
  ServerPlayer player();

  default boolean isPlayer() {
    return player() != null;
  }
}
