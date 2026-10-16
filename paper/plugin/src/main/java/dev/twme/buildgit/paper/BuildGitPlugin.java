package dev.twme.buildgit.paper;

import dev.twme.buildgit.api.BuildGitApi;
import dev.twme.buildgit.paper.axiom.AxiomHook;
import dev.twme.buildgit.paper.track.BlockListener;
import dev.twme.buildgit.paper.track.BukkitTracker;
import dev.twme.buildgit.paper.track.EntityListener;
import dev.twme.buildgit.paper.worldedit.WorldEditHook;
import dev.twme.buildgit.server.BuildGit;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.logging.Level;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.block.CraftBlock;
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
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper 外掛的入口：建立共用的 {@link BuildGit}，把 Bukkit 事件轉給它。 */
public final class BuildGitPlugin extends JavaPlugin implements Listener {
  private PaperPlatform platform;
  private BuildGit bg;
  private WorldEditHook worldEdit;

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
        worldEdit = new WorldEditHook(tracker, getLogger());
        worldEdit.register();
        getLogger().info("已掛上 WorldEdit/FAWE 記錄");
      } catch (LinkageError e) {
        worldEdit = null;
        getLogger().log(Level.WARNING, "無法掛上 WorldEdit/FAWE（版本不相容？）", e);
      }
    }
    if (settings.axiom() && pm.getPlugin("AxiomPaper") != null) {
      try {
        new AxiomHook(tracker).register(this, getLogger());
      } catch (LinkageError e) {
        getLogger().log(Level.WARNING, "無法掛上 AxiomPaper（版本不相容？）", e);
      }
    }
    getServer().getServicesManager().register(BuildGitApi.class, new ApiImpl(tracker), this, ServicePriority.Normal);

    bg.enable();
    Bukkit.getScheduler().runTaskTimer(this, bg::tick, 1, 1);
  }

  @Override
  public void onDisable() {
    if (bg == null) return; // 版本不符，沒有啟動過
    Bukkit.getScheduler().cancelTasks(this);
    if (worldEdit != null) worldEdit.unregister();
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
  }

  @EventHandler
  public void onWorldLoad(WorldLoadEvent e) {
    bg.levelLoaded(PaperPlatform.level(e.getWorld()));
  }

  @EventHandler
  public void onChangeWorld(PlayerChangedWorldEvent e) {
    bg.changedWorld(handle(e.getPlayer()), PaperPlatform.level(e.getFrom()));
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
    if (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.PHYSICAL) readOnly(e.getPlayer(), e);
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
