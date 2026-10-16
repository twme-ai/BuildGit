package dev.twme.buildgit.fabric.client;

import dev.twme.buildgit.core.sync.Frame;
import dev.twme.buildgit.core.sync.SyncClient;
import dev.twme.buildgit.fabric.SyncPayload;
import java.io.IOException;
import java.util.ArrayDeque;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/**
 * 用戶端的入口（設計 §6.7）：收下伺服器送來的範圍，伺服器要求時把改過的範圍送回。
 * 範圍檔放在 {@code .minecraft/buildgit/sync/}，單人世界的 {@code /bg load} 也讀這裡。
 */
public final class BuildGitClient implements ClientModInitializer {
  /** 每刻送出的段數（每段約 30 KB）。 */
  private static final int PARTS_PER_TICK = 4;

  private final ArrayDeque<byte[]> outgoing = new ArrayDeque<>();

  @Override
  public void onInitializeClient() {
    var log = LoggerFactory.getLogger("BuildGit");
    var sync = new SyncClient(FabricLoader.getInstance().getGameDir().resolve("buildgit").resolve("sync"));
    ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> {
      try {
        for (var reply : sync.receive(Frame.decode(payload.data()))) outgoing.add(reply.encode());
      } catch (IOException e) {
        log.warn("收到無法解讀的同步封包", e);
      }
    });
    ClientTickEvents.END_CLIENT_TICK.register(client -> {
      if (outgoing.isEmpty()) return;
      if (!ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
        outgoing.clear();
        return;
      }
      for (int i = 0; i < PARTS_PER_TICK && !outgoing.isEmpty(); i++) ClientPlayNetworking.send(new SyncPayload(outgoing.poll()));
    });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> outgoing.clear());
  }
}
