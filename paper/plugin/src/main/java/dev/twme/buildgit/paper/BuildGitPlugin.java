package dev.twme.buildgit.paper;

import dev.twme.buildgit.api.BuildGitApi;
import dev.twme.buildgit.paper.axiom.AxiomHook;
import dev.twme.buildgit.paper.nms.CascadeUpdater;
import dev.twme.buildgit.paper.nms.CommandHook;
import dev.twme.buildgit.paper.track.BlockListener;
import dev.twme.buildgit.paper.track.EntityListener;
import dev.twme.buildgit.paper.track.Tracker;
import dev.twme.buildgit.paper.worldedit.WorldEditHook;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class BuildGitPlugin extends JavaPlugin implements Listener {
  private Settings settings;
  private Messages messages;
  private Tracker tracker;
  private Workspace workspace;
  private CommandHook commands;
  private WorldEditHook worldEdit;
  private Outlines outlines;
  private Selections selections;
  private Hud hud;
  private Menu menu;
  private final java.util.List<BukkitTask> tasks = new java.util.ArrayList<>();

  Messages messages() {
    return messages;
  }

  Tracker tracker() {
    return tracker;
  }

  Workspace workspace() {
    return workspace;
  }

  Outlines outlines() {
    return outlines;
  }

  Selections selections() {
    return selections;
  }

  Menu menu() {
    return menu;
  }

  @Override
  public void onEnable() {
    saveDefaultConfig();
    settings = Settings.load(getConfig(), getLogger());
    messages = new Messages(this, settings.language());
    tracker = new Tracker(settings, Bukkit::getCurrentTick);
    workspace = new Workspace(this, tracker);

    var pm = getServer().getPluginManager();
    pm.registerEvents(new BlockListener(tracker), this);
    pm.registerEvents(new EntityListener(tracker), this);
    pm.registerEvents(this, this);
    outlines = new Outlines(this);
    selections = new Selections(this);
    hud = new Hud(this);
    menu = new Menu(this);
    pm.registerEvents(outlines, this);
    pm.registerEvents(selections, this);
    pm.registerEvents(hud, this);
    pm.registerEvents(menu, this);
    var bg = new BgCommand(this);
    pm.registerEvents(bg, this);
    getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
        e -> e.registrar().register(bg.build(), "BuildGit 版本控制", List.of("buildgit")));

    for (World world : Bukkit.getWorlds()) tracker.precise(world, CascadeUpdater.install(world, tracker, getLogger()));
    commands = new CommandHook(tracker, getLogger());
    commands.ensure();

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

    try {
      workspace.open();
    } catch (IOException e) {
      getLogger().log(Level.SEVERE, "無法開啟版本庫，記錄停用", e);
    }
    if (!workspace.isOpen()) getLogger().info("尚未初始化版本控制；用 /bg init 開始");

    tasks.add(Bukkit.getScheduler().runTaskTimer(this, tracker::tick, 1, 1));
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, workspace::flushJournal, settings.journalFlushTicks(), settings.journalFlushTicks()));
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> workspace.autosaveTick(r -> r.commit().ifPresent(id ->
        Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("buildgit.view"))
            .forEach(p -> messages.send(p, "autosave-done", "id", id.abbrev(), "cells", r.cells())))), 20, 20));
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, hud::tick, 20, 20));
  }

  @Override
  public void onDisable() {
    tasks.forEach(BukkitTask::cancel);
    tasks.clear();
    if (hud != null) hud.hideAll();
    if (outlines != null) outlines.clearAll();
    if (worldEdit != null) worldEdit.unregister();
    if (commands != null) commands.unwrap();
    for (World world : Bukkit.getWorlds()) CascadeUpdater.uninstall(world);
    if (workspace != null) workspace.close();
    getServer().getServicesManager().unregisterAll(this);
  }

  void reload() {
    reloadConfig();
    settings = Settings.load(getConfig(), getLogger());
    messages = new Messages(this, settings.language());
    tracker.settings(settings);
  }

  // 其他外掛晚註冊的 Brigadier 指令：在執行前確認都包好了。

  @EventHandler
  public void onServerLoad(ServerLoadEvent e) {
    commands.ensure();
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
    commands.ensure();
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onServerCommand(ServerCommandEvent e) {
    commands.ensure();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent e) {
    if (e.isNewChunk()) workspace.newChunk(e.getChunk());
  }

  /** 分支世界在讀取任何 chunk 之前掛上合成讀取。 */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onWorldInit(org.bukkit.event.world.WorldInitEvent e) {
    workspace.worldInit(e.getWorld());
  }

  @EventHandler
  public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent e) {
    tracker.precise(e.getWorld(), CascadeUpdater.install(e.getWorld(), tracker, getLogger()));
  }
}
