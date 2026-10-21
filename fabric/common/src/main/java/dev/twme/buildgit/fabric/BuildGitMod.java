package dev.twme.buildgit.fabric;

import dev.twme.buildgit.fabric.axiom.AxiomRecorder;
import dev.twme.buildgit.fabric.mc.Versions;
import dev.twme.buildgit.fabric.worldedit.WorldEditHook;
import java.util.logging.Level;
import net.fabricmc.loader.api.FabricLoader;
import dev.twme.buildgit.server.BuildGit;
import dev.twme.buildgit.server.LuckPermsContexts;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric 模組的入口：伺服器啟動時建立共用的 {@link BuildGit}，停止時收掉。
 * 單人遊戲每次開存檔都是一個新的伺服器，所以每次重新建立。
 */
public final class BuildGitMod implements ModInitializer {
  private FabricPlatform platform;
  private BuildGit bg;
  private WorldEditHook worldEdit;

  @Override
  public void onInitialize() {
    Versions.registerPayload(SyncPayload.TYPE, SyncPayload.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> {
      if (bg != null) bg.syncReceived(context.player(), payload.data());
    });
    ServerLifecycleEvents.SERVER_STARTING.register(this::starting);
    ServerLifecycleEvents.SERVER_STARTED.register(server -> {
      if (bg != null) bg.enable();
      // LuckPerms 在伺服器啟動時才準備好 API
      if (bg != null && bg.settings().luckPermsContexts() && FabricLoader.getInstance().isModLoaded("luckperms")) {
        try {
          LuckPermsContexts.<ServerPlayer>register(bg, ServerPlayer::getUUID, p -> p);
        } catch (LinkageError | IllegalStateException e) {
          platform.log().log(Level.WARNING, "無法向 LuckPerms 提供情境（版本不相容？）", e);
        }
      }
    });
    ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
    ServerTickEvents.START_SERVER_TICK.register(server -> {
      if (bg == null) return;
      platform.runDelayed();
      bg.tick();
    });
    // 資料包重載會重建指令樹：再註冊一次
    CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
      if (bg != null) register(dispatcher);
    });
    ServerChunkEvents.CHUNK_GENERATE.register((level, chunk) -> {
      if (bg != null) bg.newChunk(level, chunk);
    });
    // 多世界模組在遊戲中載入、卸載的世界
    ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
      if (bg != null) bg.joined(handler.player);
    });
    Versions.onChunkLoad((level, chunk) -> {
      if (bg != null) bg.chunkLoaded(level, chunk.getPos().getMinBlockX() >> 4, chunk.getPos().getMinBlockZ() >> 4);
    });
    Versions.onLevelEvents(level -> {
      if (bg != null) bg.levelLoaded(level);
    }, level -> {
      if (bg != null) bg.levelUnloaded(level);
    });
  }

  private void starting(MinecraftServer server) {
    platform = new FabricPlatform(server);
    Synthesis.log = platform.log();
    BuildGit.applyRestore(platform.repoDir(), platform.log()); // 失敗時丟出例外，伺服器停止啟動
    bg = new BuildGit(platform);
    Hooks.bg = bg;
    register(server.getCommands().getDispatcher());
    if (bg.settings().worldEdit() && FabricLoader.getInstance().isModLoaded("worldedit")) {
      try {
        worldEdit = new WorldEditHook(bg.tracker(), platform.log(), id -> {
          var p = server.getPlayerList().getPlayer(id);
          return p != null && Hooks.readOnly(p);
        });
        worldEdit.register();
      } catch (LinkageError e) {
        worldEdit = null;
        platform.log().log(Level.WARNING, "無法掛上 WorldEdit（版本不相容？）", e);
      }
    }
    if (bg.settings().axiom() && FabricLoader.getInstance().isModLoaded("axiom")) AxiomRecorder.check(platform.log());
  }

  private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    var node = bg.command().<CommandSourceStack>build(platform::sender);
    dispatcher.getRoot().addChild(node);
    dispatcher.register(Commands.literal("buildgit").executes(node.getCommand()).redirect(node));
  }

  private void stop() {
    if (bg == null) return;
    Hooks.bg = null;
    if (FabricLoader.getInstance().isModLoaded("axiom")) AxiomRecorder.disable();
    if (worldEdit != null) {
      worldEdit.unregister();
      worldEdit = null;
    }
    try {
      bg.disable();
    } finally {
      platform.shutdown();
      bg = null;
      platform = null;
    }
  }
}
