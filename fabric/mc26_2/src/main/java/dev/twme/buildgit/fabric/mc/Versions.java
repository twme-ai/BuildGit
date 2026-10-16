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
    return new ChestMenu(MenuType.GENERIC_9x6, id, inventory, container, 6) {
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

  public static Object openLevel(MinecraftServer server, Identifier id, ServerLevel template) {
    var config = new RuntimeLevelConfig()
        .setDimensionType(template.dimensionTypeRegistration())
        .setGenerator(template.getChunkSource().getGenerator())
        .setSeed(template.getSeed())
        .setShouldTickTime(true);
    return Fantasy.get(server).getOrOpenPersistentLevel(id, config);
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
