package dev.twme.buildgit.fabric.client;

import dev.twme.buildgit.core.sync.Frame;
import dev.twme.buildgit.core.sync.SyncClient;
import dev.twme.buildgit.fabric.SyncPayload;
import dev.twme.buildgit.fabric.mc.ClientVersions;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

/**
 * 用戶端的入口（設計 §6.7）：收下伺服器送來的範圍，伺服器要求時把改過的範圍送回；連上時列出還沒送回的範圍。
 * 範圍檔放在 {@code .minecraft/buildgit/sync/}，單人世界的 {@code /bg load} 也讀這裡。
 * {@code /bgc open <名稱>} 離開伺服器、在專屬的單人世界打開範圍。伺服器的 {@code /bg new … from <存檔>} 要求時，
 * 把 {@code .minecraft/saves/<存檔>} 打包上傳。
 */
public final class BuildGitClient implements ClientModInitializer {
  /** 每刻送出的段數（每段約 30 KB）。 */
  private static final int PARTS_PER_TICK = 4;

  /** 上傳單人世界的回覆從背景執行緒加進來。 */
  private final ConcurrentLinkedQueue<byte[]> outgoing = new ConcurrentLinkedQueue<>();
  /** 下一刻要做的事（不在指令執行中途離開世界）。 */
  private Runnable next;

  @Override
  public void onInitializeClient() {
    var log = LoggerFactory.getLogger("BuildGit");
    var folder = FabricLoader.getInstance().getGameDir().resolve("buildgit").resolve("sync");
    var sync = new SyncClient(folder, FabricLoader.getInstance().getGameDir().resolve("saves"), f -> outgoing.add(f.encode()), BuildGitClient::ask);
    ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> {
      try {
        for (var reply : sync.receive(Frame.decode(payload.data()))) outgoing.add(reply.encode());
      } catch (IOException e) {
        log.warn("收到無法解讀的同步封包", e);
      }
    });
    // /bgc open <名稱>：伺服器的「在單人世界打開」按鈕
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bgc")
        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open").then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
            .executes(ctx -> open(ctx.getSource(), folder, StringArgumentType.getString(ctx, "name")))))
        // /bgc import yes|no [編號]：回答伺服器上傳單人世界的要求（按鈕帶編號）
        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("import")
            .then(answerCommand("yes", sync, true))
            .then(answerCommand("no", sync, false)))));
    ClientTickEvents.END_CLIENT_TICK.register(client -> {
      if (next != null) {
        var run = next;
        next = null;
        run.run();
      }
      if (outgoing.isEmpty()) return;
      if (!ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
        outgoing.clear();
        return;
      }
      for (int i = 0; i < PARTS_PER_TICK && !outgoing.isEmpty(); i++) ClientPlayNetworking.send(new SyncPayload(outgoing.poll()));
    });
    // 連上裝了 BuildGit 的伺服器：告訴它有哪些改過的範圍還沒送回
    ClientVersions.onServerChannels(channels -> {
      if (channels.contains(SyncPayload.TYPE.id())) outgoing.add(sync.edited().encode());
    });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
      outgoing.clear();
      sync.reset();
    });
  }

  /** 伺服器要上傳單人世界：在聊天欄問玩家，點了同意才打包。 */
  private static void ask(SyncClient.ImportRequest r) {
    var mc = Minecraft.getInstance();
    var server = mc.getCurrentServer() == null ? "?" : mc.getCurrentServer().ip;
    var yes = Component.translatableWithFallback("buildgit.import.yes", "[Upload]")
        .withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/bgc import yes " + r.id())));
    var no = Component.translatableWithFallback("buildgit.import.no", "[Cancel]")
        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/bgc import no " + r.id())));
    ClientVersions.chat(mc, Component.translatableWithFallback("buildgit.import.ask",
        "Server %s wants to upload your singleplayer world \"%s\" (blocks and entities of the overworld, at most %s MB). ", server, r.save(),
        r.max() / 1024 / 1024).withStyle(ChatFormatting.YELLOW).append(yes).append(" ").append(no));
  }

  private static LiteralArgumentBuilder<FabricClientCommandSource> answerCommand(String word, SyncClient sync, boolean yes) {
    return LiteralArgumentBuilder.<FabricClientCommandSource>literal(word).executes(ctx -> answer(ctx.getSource(), sync, null, yes))
        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("id", IntegerArgumentType.integer(1))
            .executes(ctx -> answer(ctx.getSource(), sync, IntegerArgumentType.getInteger(ctx, "id"), yes)));
  }

  private static int answer(FabricClientCommandSource source, SyncClient sync, Integer id, boolean yes) {
    if (sync.answer(id, yes)) return 1;
    source.sendError(Component.translatableWithFallback("buildgit.import.none", "No upload request is waiting (or this prompt is out of date)."));
    return 0;
  }

  /** 離開伺服器，在專屬的單人世界（{@code buildgit-<名稱>}）打開範圍；放入與傳送由單人世界的 BuildGit 接手。 */
  private int open(FabricClientCommandSource source, Path folder, String name) {
    if (!Frame.NAME.matcher(name).matches() || !SyncClient.pulled(folder, name)) {
      source.sendError(Component.translatableWithFallback("buildgit.open.missing", "No pulled area %s: run /bg pull on the server first.", name));
      return 0;
    }
    var p = source.getPlayer();
    String level = "buildgit-" + name;
    try {
      SyncClient.writeOpen(folder, new SyncClient.Open(name, level, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
    } catch (IOException e) {
      source.sendError(Component.literal(String.valueOf(e.getMessage())));
      return 0;
    }
    var mc = source.getClient();
    next = () -> ClientVersions.openWorld(mc, level, "BuildGit " + name);
    return 1;
  }
}
