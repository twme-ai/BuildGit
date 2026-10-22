package dev.twme.buildgit.fabric.mc;

import java.util.UUID;
import java.util.function.IntConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/** 26.2 與 1.21.11 不同的部分（26.2 版）。共用程式碼只透過這個類別碰這些 API。 */
public final class Versions {
  private Versions() {}

  /** 世界載入、卸載（包括多世界模組在遊戲中開關的世界）。 */
  public static void onLevelEvents(java.util.function.Consumer<ServerLevel> load, java.util.function.Consumer<ServerLevel> unload) {
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.LOAD.register((server, level) -> load.accept(level));
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.UNLOAD.register((server, level) -> unload.accept(level));
  }

  /** 已存在的 chunk 載入（不含剛生成的）。 */
  public static void onChunkLoad(java.util.function.BiConsumer<ServerLevel, net.minecraft.world.level.chunk.LevelChunk> load) {
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
      if (!generated) load.accept(level, chunk);
    });
  }

  /** 雙向的 play 封包。 */
  public static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void registerPayload(
      net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type,
      net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec) {
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(type, codec);
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(type, codec);
  }

  public static ServerBossEvent bossEvent(Component name, BossEvent.BossBarColor color) {
    return new ServerBossEvent(UUID.randomUUID(), name, color, BossEvent.BossBarOverlay.PROGRESS);
  }

  public static boolean entitiesLoaded(ServerLevel level, int chunkX, int chunkZ) {
    return level.areEntitiesLoaded(ChunkPos.pack(chunkX, chunkZ));
  }

  /** 6 排的箱子畫面；點了上面的格子呼叫 {@code onClick}，物品不會被拿走。 */
  public static AbstractContainerMenu chestMenu(int id, Inventory inventory, Container container, IntConsumer onClick) {
    return new ChestMenu(MenuType.GENERIC_9x5, id, inventory, container, 5) { // Platform.MENU_ROWS
      @Override
      public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (slot >= 0 && slot < container.getContainerSize()) onClick.accept(slot);
        sendAllDataToRemote();
      }

      @Override
      public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
      }
    };
  }

  // ---- Fantasy（26.x 的 API 以 Level 命名） ----

  /** 每個世界的 Fantasy 設定：難度只能從這裡改。 */
  private static final java.util.Map<ServerLevel, RuntimeLevelConfig> CONFIGS = new java.util.WeakHashMap<>();
  /**
   * BuildGit 開的世界各自的天氣（{@code mixin.WeatherMixin} 讓這些世界讀自己的）：26.x 原版的天氣是整台伺服器一份，
   * Fantasy 的世界也跟著伺服器世界下雨。
   */
  private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, net.minecraft.world.level.saveddata.WeatherData> WEATHER =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 時間要不要走由遊戲規則決定（BuildGit 照建築世界的設定）。 */
  public static Object openLevel(MinecraftServer server, Identifier id, net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> type,
      net.minecraft.world.level.chunk.ChunkGenerator generator, long seed) {
    var config = new RuntimeLevelConfig()
        .setDimensionType(type)
        .setGenerator(generator)
        .setSeed(seed)
        .setShouldTickTime(true);
    // 世界建立時就會讀天氣，先登記
    WEATHER.computeIfAbsent(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id),
        k -> new net.minecraft.world.level.saveddata.WeatherData());
    var handle = Fantasy.get(server).getOrOpenPersistentLevel(id, config);
    CONFIGS.put(level(handle), config);
    return handle;
  }

  /** 這個世界自己的天氣；不是 BuildGit 開的世界時為 null（用伺服器的）。 */
  public static net.minecraft.world.level.saveddata.WeatherData weather(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
    return WEATHER.get(dimension);
  }

  /** 照維度類型的時鐘（主世界、終界類型各有一個；地獄類型沒有）。 */
  public static void time(ServerLevel level, long time) {
    level.dimensionType().defaultClock().ifPresent(clock -> {
      level.clockManager().setTotalTicks(clock, time);
      level.clockManager().setPaused(clock, false);
    });
  }

  public static void weather(ServerLevel level, boolean rain, boolean thunder) {
    var data = level.getWeatherData();
    data.setRaining(rain);
    data.setThundering(thunder);
  }

  public static void difficulty(ServerLevel level, net.minecraft.world.Difficulty difficulty) {
    var config = CONFIGS.get(level);
    if (config != null) config.setDifficulty(difficulty);
  }

  public static ServerLevel level(Object handle) {
    return ((RuntimeLevelHandle) handle).asLevel();
  }

  public static void unload(Object handle) {
    ((RuntimeLevelHandle) handle).unload();
  }

  public static void delete(Object handle) {
    ((RuntimeLevelHandle) handle).delete();
  }
}
