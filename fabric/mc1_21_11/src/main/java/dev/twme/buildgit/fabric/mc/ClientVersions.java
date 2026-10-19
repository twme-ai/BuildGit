package dev.twme.buildgit.fabric.mc;

import java.util.List;
import java.util.function.Consumer;

/** 用戶端專用、各版本不同的 API（和 {@link Versions} 分開，專用伺服器不會載入）。 */
public final class ClientVersions {
  private ClientVersions() {}

  /** 伺服器宣告了可以收的頻道。 */
  public static void onServerChannels(Consumer<List<?>> listener) {
    net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> listener.accept(channels));
  }
}
