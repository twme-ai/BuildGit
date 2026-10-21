package dev.twme.buildgit.fabric.mc;

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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

/** 26.2 與 1.21.11 不同的部分（1.21.11 版）。共用程式碼只透過這個類別碰這些 API。 */
public final class Versions {
  private Versions() {}

  /** 世界載入、卸載（包括多世界模組在遊戲中開關的世界）。 */
  public static void onLevelEvents(java.util.function.Consumer<ServerLevel> load, java.util.function.Consumer<ServerLevel> unload) {
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.LOAD.register((server, level) -> load.accept(level));
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.UNLOAD.register((server, level) -> unload.accept(level));
  }

  /** 已存在的 chunk 載入（不含剛生成的）。 */
  public static void onChunkLoad(java.util.function.BiConsumer<ServerLevel, net.minecraft.world.level.chunk.LevelChunk> load) {
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register(load::accept);
  }

  /** 雙向的 play 封包。 */
  public static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void registerPayload(
      net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type,
      net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec) {
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(type, codec);
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(type, codec);
  }

  public static ServerBossEvent bossEvent(Component name, BossEvent.BossBarColor color) {
    return new ServerBossEvent(name, color, BossEvent.BossBarOverlay.PROGRESS);
  }

  public static boolean entitiesLoaded(ServerLevel level, int chunkX, int chunkZ) {
    return level.areEntitiesLoaded(ChunkPos.asLong(chunkX, chunkZ));
  }

  /** 6 排的箱子畫面；點了上面的格子呼叫 {@code onClick}，物品不會被拿走。 */
  public static AbstractContainerMenu chestMenu(int id, Inventory inventory, Container container, IntConsumer onClick) {
    return new ChestMenu(MenuType.GENERIC_9x5, id, inventory, container, 5) { // Platform.MENU_ROWS
      @Override
      public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot >= 0 && slot < container.getContainerSize()) onClick.accept(slot);
        sendAllDataToRemote();
      }

      @Override
      public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
      }
    };
  }

  // ---- Fantasy（1.21.x 的 API 以 World 命名） ----

  /** @param fixed 建築用規則：時間不走（Fantasy 的世界預設是正午、晴天）、和平 */
  public static Object openLevel(MinecraftServer server, Identifier id, net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> type,
      net.minecraft.world.level.chunk.ChunkGenerator generator, long seed, boolean fixed) {
    var config = new RuntimeWorldConfig()
        .setDimensionType(type)
        .setGenerator(generator)
        .setSeed(seed)
        .setShouldTickTime(!fixed);
    if (fixed) config.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL);
    return Fantasy.get(server).getOrOpenPersistentWorld(id, config);
  }

  public static ServerLevel level(Object handle) {
    return ((RuntimeWorldHandle) handle).asWorld();
  }

  public static void unload(Object handle) {
    ((RuntimeWorldHandle) handle).unload();
  }

  public static void delete(Object handle) {
    ((RuntimeWorldHandle) handle).delete();
  }
}
