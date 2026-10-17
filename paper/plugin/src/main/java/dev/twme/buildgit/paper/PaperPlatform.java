package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.sync.Frame;
import dev.twme.buildgit.paper.nms.RegionHook;
import dev.twme.buildgit.paper.worldedit.WorldEditSelection;
import dev.twme.buildgit.server.PermissionSets;
import dev.twme.buildgit.server.Platform;
import dev.twme.buildgit.server.Sender;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Paper 平台：Bukkit 排程、WorldCreator 建立世界、Adventure bossbar、Bukkit 物品欄選單。 */
final class PaperPlatform implements Platform, Listener {
  private static final Field NEIGHBOR_UPDATER;
  private static final Field MAX_CHAINED;

  static {
    try {
      NEIGHBOR_UPDATER = net.minecraft.world.level.Level.class.getDeclaredField("neighborUpdater");
      NEIGHBOR_UPDATER.setAccessible(true);
      MAX_CHAINED = CollectingNeighborUpdater.class.getDeclaredField("maxChainedNeighborUpdates");
      MAX_CHAINED.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private record Pending(ServerLevel template, Synthesizer blocks, Synthesizer entities) {}

  private record Holder(Map<Integer, String> actions, Inventory[] inventory) implements InventoryHolder {
    @Override
    public Inventory getInventory() {
      return inventory[0];
    }
  }

  private final BuildGitPlugin plugin;
  private final Map<String, Pending> creating = new HashMap<>();
  private final Map<UUID, BossBar> bars = new HashMap<>();

  PaperPlatform(BuildGitPlugin plugin) {
    this.plugin = plugin;
  }

  static ServerLevel level(World world) {
    return ((CraftWorld) world).getHandle();
  }

  private static Player bukkit(ServerPlayer player) {
    return player.getBukkitEntity();
  }

  @Override
  public MinecraftServer server() {
    return MinecraftServer.getServer();
  }

  @Override
  public Logger log() {
    return plugin.getLogger();
  }

  @Override
  public Path configDir() {
    return plugin.getDataFolder().toPath();
  }

  @Override
  public Path repoDir() {
    return Path.of("").toAbsolutePath().resolve("buildgit");
  }

  // ---- 執行緒 ----

  @Override
  public void sync(Runnable task) {
    if (Bukkit.isPrimaryThread()) task.run();
    else Bukkit.getScheduler().runTask(plugin, task);
  }

  @Override
  public void async(Runnable task) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
  }

  @Override
  public void later(long ticks, Runnable task) {
    Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
  }

  @Override
  public void asyncLater(long ticks, Runnable task) {
    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
  }

  @Override
  public long tick() {
    return Bukkit.getCurrentTick();
  }

  // ---- 世界 ----

  @Override
  public long[] loadedChunks(ServerLevel level) {
    return java.util.Arrays.stream(level.getWorld().getLoadedChunks()).mapToLong(c -> (long) c.getX() << 32 | c.getZ() & 0xFFFFFFFFL).toArray();
  }

  @Override
  public ServerLevel findLevel(String name) {
    var world = Bukkit.getWorld(name);
    return world == null ? null : level(world);
  }

  @Override
  public ServerLevel openLevel(String name, ServerLevel template, Synthesizer blocks, Synthesizer entities) throws IOException {
    World world;
    creating.put(name, new Pending(template, blocks, entities));
    try {
      world = new WorldCreator(name).copy(template.getWorld()).createWorld();
    } finally {
      creating.remove(name);
    }
    if (world == null) throw new IOException("無法建立世界 " + name);
    return level(world);
  }

  /** 分支世界在讀取任何 chunk 之前掛上合成讀取。 */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onWorldInit(WorldInitEvent e) {
    var pending = creating.get(e.getWorld().getName());
    if (pending == null) return;
    try {
      var level = level(e.getWorld());
      RegionHook.install(level, e.getWorld().getName(), pending.blocks(), pending.entities(), log());
      skipSpawnSearch(level, pending.template());
    } catch (ReflectiveOperationException | RuntimeException ex) {
      // 沒有合成讀取的世界會變成全新生成的世界：拒絕使用比給錯內容好。
      throw new IllegalStateException("無法在 " + e.getWorld().getName() + " 掛上合成讀取", ex);
    }
  }

  /**
   * 新世界的出生點直接沿用來源世界的，並標成已初始化：否則伺服器會在建立世界時搜尋出生點，
   * 在主執行緒同步載入（合成）出生點附近的 chunk，可能卡住好幾秒。這個事件在檢查「是否已初始化」之前觸發。
   */
  private static void skipSpawnSearch(ServerLevel level, ServerLevel template) {
    net.minecraft.world.level.storage.ServerLevelData data = level.serverLevelData;
    if (data.isInitialized()) return;
    var spawn = template.getLevelData().getRespawnData();
    data.setSpawn(net.minecraft.world.level.storage.LevelData.RespawnData.of(level.dimension(), spawn.pos(), spawn.yaw(), spawn.pitch()));
    data.setInitialized(true);
  }

  @Override
  public boolean unloadLevel(ServerLevel level, boolean save) {
    return Bukkit.unloadWorld(level.getWorld(), save);
  }

  /** 主執行緒只把 chunk 交給 IO 執行緒（不等寫完）；Moonrise 的 flush 可以在其他執行緒等。 */
  @Override
  public Runnable save(ServerLevel level) {
    level.save(null, false, false);
    return () -> ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush(level);
  }

  @Override
  public Path levelDirectory(ServerLevel level) {
    return ((CraftWorld) level.getWorld()).getWorldPath();
  }

  /** 世界資料夾的上層（26.x 的 {@code world/dimensions/minecraft}）。 */
  private Path levelsDir() {
    return levelDirectory(level(Bukkit.getWorlds().getFirst())).getParent();
  }

  @Override
  public Path levelFolder(String name) {
    return levelsDir().resolve(name);
  }

  @Override
  public List<String> levelFolders() throws IOException {
    try (var list = Files.list(levelsDir())) {
      return list.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
    }
  }

  @Override
  public String levelName(ServerLevel level) {
    return level.getWorld().getName();
  }

  @Override
  public CompletableFuture<LevelChunk> loadChunk(ServerLevel level, int chunkX, int chunkZ) {
    return level.getWorld().getChunkAtAsync(chunkX, chunkZ, true).thenApply(c -> level.getChunk(chunkX, chunkZ));
  }

  @Override
  public boolean entitiesLoaded(ServerLevel level, int chunkX, int chunkZ) {
    var world = level.getWorld();
    return world.isChunkLoaded(chunkX, chunkZ) && world.getChunkAt(chunkX, chunkZ).isEntitiesLoaded();
  }

  @Override
  public CollectingNeighborUpdater neighborUpdater(ServerLevel level) {
    try {
      return (CollectingNeighborUpdater) NEIGHBOR_UPDATER.get(level);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void neighborUpdater(ServerLevel level, CollectingNeighborUpdater updater) {
    try {
      NEIGHBOR_UPDATER.set(level, updater);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public int maxChainedNeighborUpdates(CollectingNeighborUpdater updater) {
    try {
      return MAX_CHAINED.getInt(updater);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- 玩家 ----

  Sender sender(CommandSender sender) {
    return new Sender() {
      @Override
      public void send(Component message) {
        sender.sendMessage(message);
      }

      @Override
      public boolean hasPermission(String permission) {
        return PermissionSets.check(sender::hasPermission, permission);
      }

      @Override
      public ServerPlayer player() {
        return sender instanceof CraftPlayer p ? p.getHandle() : null;
      }
    };
  }

  @Override
  public Sender sender(CommandSourceStack source) {
    return sender(source.getBukkitSender());
  }

  @Override
  public Sender sender(ServerPlayer player) {
    return sender(bukkit(player));
  }

  @Override
  public boolean hasPermission(ServerPlayer player, String permission) {
    return PermissionSets.check(bukkit(player)::hasPermission, permission);
  }

  @Override
  public CompletableFuture<Boolean> teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
    return bukkit(player).teleportAsync(new Location(level.getWorld(), x, y, z, yaw, pitch));
  }

  @Override
  public void performCommand(ServerPlayer player, String command) {
    bukkit(player).performCommand(command);
  }

  // ---- 畫面 ----

  @Override
  public void bar(ServerPlayer player, Component text, BarColor color) {
    var p = bukkit(player);
    var bar = bars.get(p.getUniqueId());
    if (text == null) {
      if (bar != null) p.hideBossBar(bar);
      bars.remove(p.getUniqueId());
      return;
    }
    var c = BossBar.Color.valueOf(color.name());
    if (bar == null) {
      bar = BossBar.bossBar(text, 1f, c, BossBar.Overlay.PROGRESS);
      bars.put(p.getUniqueId(), bar);
      p.showBossBar(bar);
    } else {
      bar.name(text);
      bar.color(c);
    }
  }

  @Override
  public void clearBars() {
    for (var e : bars.entrySet()) {
      var p = Bukkit.getPlayer(e.getKey());
      if (p != null) p.hideBossBar(e.getValue());
    }
    bars.clear();
  }

  void quit(UUID player) {
    bars.remove(player);
  }

  @Override
  public void openMenu(ServerPlayer player, Component title, List<MenuItem> items) {
    var holder = new Holder(new HashMap<>(), new Inventory[1]);
    var inv = Bukkit.createInventory(holder, 54, title);
    holder.inventory()[0] = inv;
    for (var item : items) {
      var material = Material.matchMaterial(item.item());
      var stack = new ItemStack(material == null ? Material.PAPER : material);
      stack.editMeta(m -> {
        m.displayName(item.name().decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(item.lore().decoration(TextDecoration.ITALIC, false)));
      });
      inv.setItem(item.slot(), stack);
      if (item.action() != null) holder.actions().put(item.slot(), item.action());
    }
    bukkit(player).openInventory(inv);
  }

  @EventHandler
  public void onClick(InventoryClickEvent e) {
    if (!(e.getInventory().getHolder(false) instanceof Holder holder)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
    var action = holder.actions().get(e.getSlot());
    if (action == null) return;
    p.closeInventory();
    Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("bg " + action));
  }

  @EventHandler
  public void onDrag(InventoryDragEvent e) {
    if (e.getInventory().getHolder(false) instanceof Holder) e.setCancelled(true);
  }

  @Override
  public boolean canSync(ServerPlayer player) {
    return bukkit(player).getListeningPluginChannels().contains(Frame.CHANNEL);
  }

  @Override
  public void sendSync(ServerPlayer player, byte[] data) {
    bukkit(player).sendPluginMessage(plugin, Frame.CHANNEL, data);
  }

  @Override
  public Optional<Selection> worldEditSelection(ServerPlayer player) {
    var pm = Bukkit.getPluginManager();
    if (!pm.isPluginEnabled("WorldEdit") && !pm.isPluginEnabled("FastAsyncWorldEdit")) return Optional.empty();
    var we = WorldEditSelection.of(bukkit(player));
    if (we == null) return Optional.empty();
    var world = Bukkit.getWorld(we.world());
    return world == null ? Optional.empty() : Optional.of(new Selection(level(world), we.box()));
  }
}
