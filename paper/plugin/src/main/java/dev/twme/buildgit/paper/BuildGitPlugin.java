package dev.twme.buildgit.paper;

import dev.twme.buildgit.api.BuildGitApi;
import dev.twme.buildgit.paper.axiom.AxiomHook;
import dev.twme.buildgit.paper.debugstickpro.DebugStickProHook;
import dev.twme.buildgit.paper.track.BlockListener;
import dev.twme.buildgit.paper.track.BukkitTracker;
import dev.twme.buildgit.paper.track.EntityListener;
import dev.twme.buildgit.paper.worldedit.WorldEditHook;
import dev.twme.buildgit.core.sync.Frame;
import dev.twme.buildgit.server.BuildGit;
import dev.twme.buildgit.server.LuckPermsContexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.nio.file.Path;
import java.util.logging.Level;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper 外掛的入口：建立共用的 {@link BuildGit}，把 Bukkit 事件轉給它。 */
public final class BuildGitPlugin extends JavaPlugin implements Listener {
  private PaperPlatform platform;
  private BuildGit bg;
  private WorldEditHook worldEdit;
  private AxiomHook axiom;

  /** 共用的本體（測試用）。 */
  public BuildGit buildGit() {
    return bg;
  }

  /** 這個 jar 編譯時對應的 Minecraft 版本（打包時寫進 plugin.yml）。 */
  private String builtFor() {
    try (var in = getResource("plugin.yml")) {
      var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
      return yaml.getString("buildgit-minecraft", "");
    } catch (java.io.IOException e) {
      return "";
    }
  }

  /** 世界載入之前：有下載好的異地備份還原時換上。換到一半失敗就停止啟動，不帶著一半的狀態開服。 */
  @Override
  public void onLoad() {
    try {
      BuildGit.applyRestore(Path.of("").toAbsolutePath().resolve("buildgit"), getLogger());
    } catch (IllegalStateException e) {
      getLogger().log(Level.SEVERE, e.getMessage(), e.getCause());
      Bukkit.shutdown();
    }
  }

  @Override
  public void onEnable() {
    // 每個 Minecraft 版本各有一個 jar（內部程式碼不相容）；版本不符時直接停用，而不是之後在奇怪的地方出錯。
    String built = builtFor();
    if (!Bukkit.getMinecraftVersion().equals(built)) {
      getLogger().severe("這個 BuildGit 是給 Minecraft " + built + " 的，伺服器是 " + Bukkit.getMinecraftVersion() + "；請下載對應版本的 jar。");
      Bukkit.getPluginManager().disablePlugin(this);
      return;
    }
    platform = new PaperPlatform(this);
    bg = new BuildGit(platform);
    var tracker = new BukkitTracker(bg.tracker());

    var pm = getServer().getPluginManager();
    pm.registerEvents(platform, this);
    pm.registerEvents(new BlockListener(tracker), this);
    pm.registerEvents(new EntityListener(tracker), this);
    pm.registerEvents(this, this);
    getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> e.registrar().register(
        bg.command().<CommandSourceStack>build(s -> platform.sender(s.getSender())), "BuildGit 版本控制", List.of("buildgit")));

    var settings = bg.settings();
    if (settings.worldEdit() && (pm.getPlugin("FastAsyncWorldEdit") != null || pm.getPlugin("WorldEdit") != null)) {
      try {
        worldEdit = new WorldEditHook(tracker, getLogger(), id -> {
          var p = getServer().getPlayer(id);
          return p != null && bg.hud().readOnly(handle(p));
        });
        worldEdit.register();
        getLogger().info("已掛上 WorldEdit/FAWE 記錄");
      } catch (LinkageError e) {
        worldEdit = null;
        getLogger().log(Level.WARNING, "無法掛上 WorldEdit/FAWE（版本不相容？）", e);
      }
    }
    if (settings.axiom() && pm.getPlugin("AxiomPaper") != null) {
      try {
        axiom = new AxiomHook(tracker, (p, recorded) ->
            bg.messages().send(platform.sender(((CraftPlayer) p).getHandle()), recorded ? "axiom-bypass-recorded" : "axiom-bypass-warning"),
            p -> bg.hud().readOnly(handle(p)));
        axiom.register(this, getLogger());
      } catch (LinkageError e) {
        getLogger().log(Level.WARNING, "無法掛上 AxiomPaper（版本不相容？）", e);
      }
    }
    if (settings.debugStickPro() && pm.getPlugin("DebugStickPro") != null) {
      try {
        new DebugStickProHook(tracker).register(this, bg.tracker(), getLogger());
      } catch (LinkageError e) {
        bg.tracker().setHeld(null);
        getLogger().log(Level.WARNING, "無法掛上 DebugStickPro（版本不相容？）", e);
      }
    }
    if (settings.luckPermsContexts() && pm.getPlugin("LuckPerms") != null) {
      try {
        LuckPermsContexts.<Player>register(bg, Player::getUniqueId, p -> p.getBukkitEntity());
      } catch (LinkageError | IllegalStateException e) {
        getLogger().log(Level.WARNING, "無法向 LuckPerms 提供情境（版本不相容？）", e);
      }
    }
    getServer().getServicesManager().register(BuildGitApi.class, new ApiImpl(tracker), this, ServicePriority.Normal);

    // 與單人世界同步：BuildGit 的 Fabric 用戶端以插件訊息頻道收發
    getServer().getMessenger().registerOutgoingPluginChannel(this, Frame.CHANNEL);
    getServer().getMessenger().registerIncomingPluginChannel(this, Frame.CHANNEL,
        (channel, player, data) -> bg.syncReceived(((CraftPlayer) player).getHandle(), data));

    bg.enable();
    Bukkit.getScheduler().runTaskTimer(this, bg::tick, 1, 1);
  }

  @Override
  public void onDisable() {
    if (bg == null) return; // 版本不符，沒有啟動過
    Bukkit.getScheduler().cancelTasks(this);
    if (worldEdit != null) worldEdit.unregister();
    if (axiom != null) axiom.unregister();
    bg.disable();
    getServer().getServicesManager().unregisterAll(this);
  }

  private static ServerPlayer handle(Player p) {
    return ((CraftPlayer) p).getHandle();
  }

  // 其他外掛晚註冊的 Brigadier 指令：在執行前確認都包好了。

  @EventHandler
  public void onServerLoad(ServerLoadEvent e) {
    bg.beforeCommand();
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
    bg.beforeCommand();
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onServerCommand(ServerCommandEvent e) {
    bg.beforeCommand();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onChunkLoad(ChunkLoadEvent e) {
    if (e.isNewChunk()) bg.newChunk(PaperPlatform.level(e.getWorld()), PaperPlatform.level(e.getWorld()).getChunk(e.getChunk().getX(), e.getChunk().getZ()));
    else bg.chunkLoaded(PaperPlatform.level(e.getWorld()), e.getChunk().getX(), e.getChunk().getZ());
  }

  @EventHandler
  public void onWorldLoad(WorldLoadEvent e) {
    bg.levelLoaded(PaperPlatform.level(e.getWorld()));
  }

  /** 受版本控制的世界：它或它的草稿世界還有玩家、或正在存檔時，不讓多世界插件卸載。 */
  @EventHandler(ignoreCancelled = true)
  public void onWorldUnload(WorldUnloadEvent e) {
    if (bg.levelUnloading(PaperPlatform.level(e.getWorld()))) return;
    e.setCancelled(true);
    getLogger().warning("世界 " + e.getWorld().getName() + " 受版本控制，它或它的草稿世界還有玩家、或正在存檔，BuildGit 先不讓它卸載");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onWorldUnloaded(WorldUnloadEvent e) {
    bg.levelUnloaded(PaperPlatform.level(e.getWorld()));
  }

  @EventHandler
  public void onChangeWorld(PlayerChangedWorldEvent e) {
    bg.changedWorld(handle(e.getPlayer()), PaperPlatform.level(e.getFrom()));
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent e) {
    var p = handle(e.getPlayer());
    // 加入事件期間不傳送：下一刻再送回草稿
    getServer().getScheduler().runTask(this, () -> {
      if (e.getPlayer().isOnline()) bg.joined(p);
    });
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    platform.quit(e.getPlayer().getUniqueId());
    bg.quit(handle(e.getPlayer()));
  }

  // ---- 檢查模式 ----

  @EventHandler(priority = EventPriority.LOWEST)
  public void onInspect(PlayerInteractEvent e) {
    if (e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
    if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    var block = (CraftBlock) e.getClickedBlock();
    var face = Direction.valueOf(e.getBlockFace().name());
    if (bg.command().inspectClick(handle(e.getPlayer()), block.getPosition(), face, e.getAction() == Action.RIGHT_CLICK_BLOCK)) e.setCancelled(true);
  }

  // ---- 檢視世界唯讀 ----

  private void readOnly(Player p, Cancellable e) {
    if (bg.hud().readOnly(handle(p))) e.setCancelled(true);
  }

  // ---- 建築世界的傳送門（03-worlds §2.2）：會被送到伺服器的地獄、終界，一律不能用 ----

  @EventHandler(ignoreCancelled = true)
  public void onPortal(PlayerPortalEvent e) {
    if (bg.workspace(((CraftWorld) e.getFrom().getWorld()).getHandle()).buildWorld() == null) return;
    e.setCancelled(true);
    bg.portalRefused(handle(e.getPlayer()));
  }

  /** 死後重生的地方（這時玩家還在死掉的世界）：建築世界改成那個世界的出生點。 */
  @EventHandler
  public void onRespawn(PlayerRespawnEvent e) {
    // 死後按重生是 DEATH，自動重生等插件呼叫的是 PLUGIN；終界門出來的不算
    if (e.getRespawnReason() == PlayerRespawnEvent.RespawnReason.END_PORTAL) return;
    var r = bg.respawn(PaperPlatform.level(e.getPlayer().getWorld()), PaperPlatform.level(e.getRespawnLocation().getWorld()));
    if (r != null) e.setRespawnLocation(new org.bukkit.Location(r.level().getWorld(), r.x(), r.y(), r.z(), r.yaw(), r.pitch()));
  }

  @EventHandler(ignoreCancelled = true)
  public void onEntityPortal(EntityPortalEvent e) {
    if (bg.workspace(((CraftWorld) e.getFrom().getWorld()).getHandle()).buildWorld() != null) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onBreak(BlockBreakEvent e) {
    readOnly(e.getPlayer(), e);
  }

  @EventHandler(ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent e) {
    readOnly(e.getPlayer(), e);
  }

  @EventHandler(ignoreCancelled = true)
  public void onBucket(PlayerBucketEmptyEvent e) {
    readOnly(e.getPlayer(), e);
  }

  @EventHandler(ignoreCancelled = true)
  public void onBucket(PlayerBucketFillEvent e) {
    readOnly(e.getPlayer(), e);
  }

  @EventHandler(ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent e) {
    if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL || e.getClickedBlock() == null) return;
    var p = handle(e.getPlayer());
    if (bg.hud().readOnly(p, p.level().getBlockState(((CraftBlock) e.getClickedBlock()).getPosition()))) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onInteractEntity(PlayerInteractEntityEvent e) {
    readOnly(e.getPlayer(), e);
  }

  @EventHandler(ignoreCancelled = true)
  public void onArmorStand(PlayerArmorStandManipulateEvent e) {
    readOnly(e.getPlayer(), e);
  }

  @EventHandler(ignoreCancelled = true)
  public void onDamage(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p) readOnly(p, e);
  }

  @EventHandler(ignoreCancelled = true)
  public void onHanging(HangingBreakByEntityEvent e) {
    if (e.getRemover() instanceof Player p) readOnly(p, e);
  }
}
