package dev.twme.buildgit.fabric;

import dev.twme.buildgit.core.sync.Frame;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * {@code buildgit:sync} 頻道的封包（設計 §6.7）。內容不加長度前綴，與 Paper 的插件訊息相同，
 * 所以用戶端連 Paper 或 Fabric 伺服器都一樣。
 */
public record SyncPayload(byte[] data) implements CustomPacketPayload {
  public static final Type<SyncPayload> TYPE = new Type<>(Identifier.parse(Frame.CHANNEL));
  public static final StreamCodec<FriendlyByteBuf, SyncPayload> CODEC = CustomPacketPayload.codec(SyncPayload::write, SyncPayload::read);

  private void write(FriendlyByteBuf buf) {
    buf.writeBytes(data);
  }

  private static SyncPayload read(FriendlyByteBuf buf) {
    var bytes = new byte[buf.readableBytes()];
    buf.readBytes(bytes);
    return new SyncPayload(bytes);
  }

  @Override
  public Type<SyncPayload> type() {
    return TYPE;
  }
}
