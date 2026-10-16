package dev.twme.buildgit.fabric;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.mojang.serialization.JsonOps;
import dev.twme.buildgit.fabric.mc.Versions;
import dev.twme.buildgit.fabric.mixin.LevelAccessor;
import dev.twme.buildgit.fabric.mixin.MinecraftServerAccessor;
import dev.twme.buildgit.server.PermissionSets;
import dev.twme.buildgit.server.Platform;
import dev.twme.buildgit.server.Sender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/**
 * Fabric 平台：自己的刻排程、Fantasy 建立世界、原版 bossbar 與箱子畫面、fabric-permissions-api 權限。
 * BuildGit 的世界以 {@code buildgit:<名稱>} 的維度存在 {@code <存檔>/dimensions/buildgit/} 下。
 */
final class FabricPlatform implements Platform {
  static final String NAMESPACE = "buildgit";

  private record Delayed(long at, Runnable task) {}

  private final MinecraftServer server;
  private final Logger log;
  private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
    var t = new Thread(r, "BuildGit");
    t.setDaemon(true);
    return t;
  });
  /** {@code later}／{@code asyncLater} 的佇列，每刻開始時檢查（主執行緒）。 */
  private final List<Delayed> delayed = new ArrayList<>();
  /** 已開啟的 BuildGit 世界：名稱 → Fantasy 的世界控制代碼。 */
  private final Map<String, Object> handles = new HashMap<>();
  /** 正在卸載的世界（Fantasy 等到 chunk 都卸下才真正移除）。 */
  private final Set<ServerLevel> unloading = Collections.newSetFromMap(new WeakHashMap<>());
  private final Map<UUID, ServerBossEvent> bars = new HashMap<>();

  FabricPlatform(MinecraftServer server) {
    this.server = server;
    this.log = bridge();
  }

  /** 共用程式碼用 java.util.logging；轉到 Minecraft 的 log（SLF4J）。 */
  private static Logger bridge() {
    var slf = LoggerFactory.getLogger("BuildGit");
    var jul = Logger.getLogger("dev.twme.buildgit.fabric");
    jul.setUseParentHandlers(false);
    for (var h : jul.getHandlers()) jul.removeHandler(h);
    jul.addHandler(new Handler() {
      @Override
      public void publish(LogRecord r) {
        int level = r.getLevel().intValue();
        String text = r.getMessage();
        if (level >= java.util.logging.Level.SEVERE.intValue()) slf.error(text, r.getThrown());
        else if (level >= java.util.logging.Level.WARNING.intValue()) slf.warn(text, r.getThrown());
        else if (level >= java.util.logging.Level.INFO.intValue()) slf.info(text, r.getThrown());
        else slf.debug(text, r.getThrown());
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    });
    return jul;
  }

  @Override
  public MinecraftServer server() {
    return server;
  }

  @Override
  public Logger log() {
    return log;
  }

  @Override
  public Path configDir() {
    return FabricLoader.getInstance().getConfigDir().resolve("buildgit");
  }

  /** 專用伺服器放在伺服器目錄（同 Paper）；單人遊戲放在存檔裡，每個存檔各一個版本庫。 */
  @Override
  public Path repoDir() {
    if (server.isDedicatedServer()) return Path.of("").toAbsolutePath().resolve("buildgit");
    return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().resolve("buildgit");
  }

  // ---- 執行緒 ----

  @Override
  public void sync(Runnable task) {
    if (server.isSameThread()) task.run();
    else server.execute(task);
  }

  @Override
  public void async(Runnable task) {
    pool.execute(task);
  }

  @Override
  public void later(long ticks, Runnable task) {
    sync(() -> delayed.add(new Delayed(server.getTickCount() + Math.max(1, ticks), task)));
  }

  @Override
  public void asyncLater(long ticks, Runnable task) {
    later(ticks, () -> async(task));
  }

  @Override
  public long tick() {
    return server.getTickCount();
  }

  /** 每刻開始時（主執行緒）。 */
  void runDelayed() {
    if (delayed.isEmpty()) return;
    long now = server.getTickCount();
    var due = new ArrayList<Runnable>();
    delayed.removeIf(d -> {
      if (d.at() > now) return false;
      due.add(d.task());
      return true;
    });
    for (var task : due) {
      try {
        task.run();
      } catch (RuntimeException e) {
        log.log(java.util.logging.Level.SEVERE, "排程的工作失敗", e);
      }
    }
  }

  void shutdown() {
    delayed.clear();
    pool.shutdown();
  }

  // ---- 世界 ----

  @Override
  public long[] loadedChunks(ServerLevel level) {
    // 鍵是 ChunkPos 的打包格式（z 在高位），換成 x 在高位
    return ((dev.twme.buildgit.fabric.mixin.ChunkMapAccessor) level.getChunkSource().chunkMap).buildgit$chunks().keySet().longStream()
        .map(k -> k << 32 | k >>> 32).toArray();
  }

  /** 世界名稱 → 維度 id。id 只能有 {@code [a-z0-9_-]}，其他字元寫成 {@code .u<十六進位>.}，可以還原。 */
  static Identifier id(String name) {
    var sb = new StringBuilder();
    name.codePoints().forEach(c -> {
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') sb.appendCodePoint(c);
      else sb.append(".u").append(Integer.toHexString(c)).append('.');
    });
    return Identifier.fromNamespaceAndPath(NAMESPACE, sb.toString());
  }

  /** {@link #id} 的反向；不是這個格式時回傳 null。 */
  static String name(String path) {
    var sb = new StringBuilder();
    for (int i = 0; i < path.length(); ) {
      char c = path.charAt(i);
      if (c != '.') {
        sb.append(c);
        i++;
        continue;
      }
      int end = path.indexOf('.', i + 1);
      if (end < 0 || path.charAt(i + 1) != 'u') return null;
      try {
        sb.appendCodePoint(Integer.parseInt(path.substring(i + 2, end), 16));
      } catch (IllegalArgumentException e) {
        return null;
      }
      i = end + 1;
    }
    return sb.toString();
  }

  private static ResourceKey<Level> key(String name) {
    return ResourceKey.create(Registries.DIMENSION, id(name));
  }

  @Override
  public ServerLevel findLevel(String name) {
    var level = server.getLevel(key(name));
    return level == null || unloading.contains(level) ? null : level;
  }

  @Override
  public ServerLevel openLevel(String name, ServerLevel template, Synthesizer blocks, Synthesizer entities) throws IOException {
    var dir = levelFolder(name);
    // 必須在世界建立之前登記：Fantasy 建立世界時就可能讀取 chunk
    Synthesis.register(dir.resolve("region"), blocks);
    Synthesis.register(dir.resolve("entities"), entities);
    try {
      var handle = Versions.openLevel(server, id(name), template);
      var level = Versions.level(handle);
      handles.put(name, handle);
      unloading.remove(level);
      return level;
    } catch (RuntimeException e) {
      Synthesis.unregister(dir.resolve("region"));
      Synthesis.unregister(dir.resolve("entities"));
      throw new IOException("無法建立世界 " + name + "：" + e.getMessage(), e);
    }
  }

  @Override
  public boolean unloadLevel(ServerLevel level, boolean save) {
    String name = levelName(level);
    var handle = handles.remove(name);
    if (handle == null) return false;
    unloading.add(level);
    var dir = levelDirectory(level);
    // Fantasy 在之後的刻真正卸載（等 chunk 都卸下）；儲存與否由 Fantasy 處理
    if (save) Versions.unload(handle);
    else Versions.delete(handle);
    Synthesis.unregister(dir.resolve("region"));
    Synthesis.unregister(dir.resolve("entities"));
    return true;
  }

  private MinecraftServerAccessor storage() {
    return (MinecraftServerAccessor) server;
  }

  @Override
  public Path levelDirectory(ServerLevel level) {
    return storage().buildgit$storage().getDimensionPath(level.dimension());
  }

  @Override
  public Path levelFolder(String name) {
    return storage().buildgit$storage().getDimensionPath(key(name));
  }

  @Override
  public List<String> levelFolders() throws IOException {
    var dir = levelFolder("x").getParent();
    if (!Files.isDirectory(dir)) return List.of();
    try (var list = Files.list(dir)) {
      return list.filter(Files::isDirectory).map(p -> name(p.getFileName().toString())).filter(Objects::nonNull).toList();
    }
  }

  @Override
  public String levelName(ServerLevel level) {
    var id = level.dimension().identifier();
    if (id.getNamespace().equals(NAMESPACE)) {
      var name = name(id.getPath());
      if (name != null) return name;
    }
    return id.toString();
  }

  @Override
  public CompletableFuture<LevelChunk> loadChunk(ServerLevel level, int chunkX, int chunkZ) {
    var future = new CompletableFuture<LevelChunk>();
    sync(() -> {
      try {
        future.complete(level.getChunk(chunkX, chunkZ));
      } catch (RuntimeException e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  public boolean entitiesLoaded(ServerLevel level, int chunkX, int chunkZ) {
    return Versions.entitiesLoaded(level, chunkX, chunkZ);
  }

  @Override
  public CollectingNeighborUpdater neighborUpdater(ServerLevel level) {
    return ((LevelAccessor) level).buildgit$neighborUpdater();
  }

  @Override
  public void neighborUpdater(ServerLevel level, CollectingNeighborUpdater updater) {
    ((LevelAccessor) level).buildgit$neighborUpdater(updater);
  }

  @Override
  public int maxChainedNeighborUpdates(CollectingNeighborUpdater updater) {
    return server.getMaxChainedNeighborUpdates();
  }

  // ---- 玩家 ----

  /** Adventure 的文字轉成原版的（經過 JSON）。 */
  net.minecraft.network.chat.Component vanilla(Component text) {
    var json = GsonComponentSerializer.gson().serializeToTree(text);
    return ComponentSerialization.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), json)
        .getOrThrow();
  }

  @Override
  public Sender sender(CommandSourceStack source) {
    return new Sender() {
      @Override
      public void send(Component message) {
        source.sendSystemMessage(vanilla(message));
      }

      @Override
      public boolean hasPermission(String permission) {
        return PermissionSets.check(p -> Permissions.check(source, p, 2), permission);
      }

      @Override
      public ServerPlayer player() {
        return source.getPlayer();
      }
    };
  }

  @Override
  public Sender sender(ServerPlayer player) {
    return new Sender() {
      @Override
      public void send(Component message) {
        player.sendSystemMessage(vanilla(message));
      }

      @Override
      public boolean hasPermission(String permission) {
        return FabricPlatform.this.hasPermission(player, permission);
      }

      @Override
      public ServerPlayer player() {
        return player;
      }
    };
  }

  @Override
  public boolean canSync(ServerPlayer player) {
    return ServerPlayNetworking.canSend(player, SyncPayload.TYPE);
  }

  @Override
  public void sendSync(ServerPlayer player, byte[] data) {
    ServerPlayNetworking.send(player, new SyncPayload(data));
  }

  /** 單人世界（內建伺服器）與用戶端共用遊戲資料夾。 */
  @Override
  public Path syncFolder() {
    return server.isDedicatedServer() ? null : FabricLoader.getInstance().getGameDir().resolve("buildgit").resolve("sync");
  }

  @Override
  public boolean hasPermission(ServerPlayer player, String permission) {
    return PermissionSets.check(p -> Permissions.check(player, p, 2), permission);
  }

  @Override
  public CompletableFuture<Boolean> teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
    var future = new CompletableFuture<Boolean>();
    sync(() -> future.complete(player.teleportTo(level, x, y, z, Set.of(), yaw, pitch, true)));
    return future;
  }

  @Override
  public void performCommand(ServerPlayer player, String command) {
    server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
  }

  // ---- 畫面 ----

  @Override
  public void bar(ServerPlayer player, Component text, BarColor color) {
    var bar = bars.get(player.getUUID());
    if (text == null) {
      if (bar != null) bar.removeAllPlayers();
      bars.remove(player.getUUID());
      return;
    }
    var c = BossEvent.BossBarColor.valueOf(color.name());
    if (bar == null) {
      bar = Versions.bossEvent(vanilla(text), c);
      bars.put(player.getUUID(), bar);
      bar.addPlayer(player);
    } else {
      bar.setName(vanilla(text));
      bar.setColor(c);
    }
  }

  @Override
  public void clearBars() {
    for (var bar : bars.values()) bar.removeAllPlayers();
    bars.clear();
  }

  void quit(ServerPlayer player) {
    var bar = bars.remove(player.getUUID());
    if (bar != null) bar.removeAllPlayers();
  }

  @Override
  public void openMenu(ServerPlayer player, Component title, List<MenuItem> items) {
    var container = new SimpleContainer(54);
    var actions = new HashMap<Integer, String>();
    for (var item : items) {
      var id = Identifier.tryParse(item.item());
      var type = id == null ? Items.PAPER : BuiltInRegistries.ITEM.getValue(id);
      var stack = new ItemStack(type == Items.AIR ? Items.PAPER : type);
      stack.set(DataComponents.CUSTOM_NAME, vanilla(item.name().decoration(TextDecoration.ITALIC, false)));
      stack.set(DataComponents.LORE, new ItemLore(List.of(vanilla(item.lore().decoration(TextDecoration.ITALIC, false)))));
      container.setItem(item.slot(), stack);
      if (item.action() != null) actions.put(item.slot(), item.action());
    }
    player.openMenu(new SimpleMenuProvider((id, inventory, p) -> Versions.chestMenu(id, inventory, container, slot -> {
      var action = actions.get(slot);
      if (action == null) return;
      // 點擊還在處理中，下一刻再關閉畫面並執行
      later(1, () -> {
        player.closeContainer();
        performCommand(player, "bg " + action);
      });
    }), vanilla(title)));
  }

  @Override
  public Optional<Selection> worldEditSelection(ServerPlayer player) {
    return Optional.empty();
  }
}
