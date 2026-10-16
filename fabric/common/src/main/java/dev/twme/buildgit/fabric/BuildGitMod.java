package dev.twme.buildgit.fabric;

import dev.twme.buildgit.server.BuildGit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

  @Override
  public void onInitialize() {
    ServerLifecycleEvents.SERVER_STARTING.register(this::starting);
    ServerLifecycleEvents.SERVER_STARTED.register(server -> {
      if (bg != null) bg.enable();
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
  }

  private void starting(MinecraftServer server) {
    platform = new FabricPlatform(server);
    Synthesis.log = platform.log();
    bg = new BuildGit(platform);
    Hooks.bg = bg;
    register(server.getCommands().getDispatcher());
  }

  private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    var node = bg.command().<CommandSourceStack>build(platform::sender);
    dispatcher.getRoot().addChild(node);
    dispatcher.register(Commands.literal("buildgit").executes(node.getCommand()).redirect(node));
  }

  private void stop() {
    if (bg == null) return;
    Hooks.bg = null;
    try {
      bg.disable();
    } finally {
      platform.shutdown();
      bg = null;
      platform = null;
    }
  }
}
