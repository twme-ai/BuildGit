package dev.twme.buildgit.fabric.mc;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.gamerules.GameRules;

/** 用戶端專用、各版本不同的 API（和 {@link Versions} 分開，專用伺服器不會載入）。 */
public final class ClientVersions {
  private ClientVersions() {}

  /** 伺服器宣告了可以收的頻道。 */
  public static void onServerChannels(Consumer<List<?>> listener) {
    net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> listener.accept(channels));
  }

  /** 離開目前的世界或伺服器，打開單人世界 {@code levelId}；沒有時建立一個創造模式、和平、可用指令的虛空世界。 */
  public static void openWorld(Minecraft mc, String levelId, String title) {
    mc.disconnect(new TitleScreen(), false);
    var flows = mc.createWorldOpenFlows();
    if (mc.getLevelSource().levelExists(levelId)) {
      flows.openWorld(levelId, () -> mc.setScreen(new TitleScreen()));
      return;
    }
    var settings = new LevelSettings(title, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
        new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
    flows.createFreshLevel(levelId, settings, new WorldOptions(WorldOptions.randomSeed(), false, false), registries -> {
      var empty = registries.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET).getOrThrow(FlatLevelGeneratorPresets.THE_VOID).value().settings();
      return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(empty));
    }, new TitleScreen());
  }
}
