package dev.twme.buildgit.server;

import dev.twme.buildgit.server.nms.CascadeUpdater;
import dev.twme.buildgit.server.nms.CommandHook;
import dev.twme.buildgit.server.track.Tracker;
import java.io.IOException;
import java.util.logging.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * BuildGit 在伺服器上的本體，與平台無關。平台建立它、在伺服器啟動後呼叫 {@link #enable}，
 * 並把事件（每刻、世界載入、新 chunk、玩家換世界與離線、指令執行前）轉給它。
 */
public final class BuildGit {
  private final Platform platform;
  private Settings settings;
  private Messages messages;
  private Tracker tracker;
  private Workspace workspace;
  private CommandHook commands;
  private Outlines outlines;
  private Selections selections;
  private Hud hud;
  private Menu menu;
  private BgCommand command;
  private long ticks;
  private String openError;

  public BuildGit(Platform platform) {
    this.platform = platform;
    settings = Settings.load(platform.configDir(), platform.log());
    messages = new Messages(platform.configDir(), settings.language(), platform.log());
    tracker = new Tracker(settings, platform::tick);
    workspace = new Workspace(platform, tracker);
    outlines = new Outlines(platform);
    selections = new Selections(platform);
    hud = new Hud(this);
    menu = new Menu(this);
    command = new BgCommand(this);
  }

  public Platform platform() {
    return platform;
  }

  public Settings settings() {
    return settings;
  }

  public Messages messages() {
    return messages;
  }

  public Tracker tracker() {
    return tracker;
  }

  public Workspace workspace() {
    return workspace;
  }

  public Outlines outlines() {
    return outlines;
  }

  Selections selections() {
    return selections;
  }

  public Hud hud() {
    return hud;
  }

  Menu menu() {
    return menu;
  }

  /** {@code /bg} 指令樹；平台在註冊指令時取用。 */
  public BgCommand command() {
    return command;
  }

  /** 伺服器的世界都載入之後（主執行緒）。 */
  public void enable() {
    for (var level : platform.server().getAllLevels()) levelLoaded(level);
    commands = new CommandHook(platform.server(), tracker, platform.log());
    commands.ensure();
    try {
      workspace.open();
    } catch (IOException e) {
      openError = e.getMessage();
      platform.log().log(Level.SEVERE, "無法開啟版本庫，記錄停用", e);
    }
    if (!workspace.isOpen() && openError == null) platform.log().info("尚未初始化版本控制；用 /bg init 開始");
  }

  /** 版本庫存在但開不了（例如被較新的 Minecraft 寫過）的原因；沒有錯誤時為 null。 */
  public String openError() {
    return openError;
  }

  public void disable() {
    platform.clearBars();
    outlines.clearAll();
    if (commands != null) commands.unwrap();
    for (var level : platform.server().getAllLevels()) CascadeUpdater.uninstall(platform, level);
    workspace.close();
  }

  void reload() {
    settings = Settings.load(platform.configDir(), platform.log());
    messages = new Messages(platform.configDir(), settings.language(), platform.log());
    tracker.settings(settings);
  }

  /** 每刻開始時（主執行緒）。 */
  public void tick() {
    tracker.tick();
    ticks++;
    if (ticks % settings.journalFlushTicks() == 0) platform.async(workspace::flushJournal);
    if (ticks % 20 != 0) return;
    workspace.autosaveTick(r -> r.commit().ifPresent(id -> {
      for (var p : platform.server().getPlayerList().getPlayers())
        if (platform.hasPermission(p, "buildgit.view")) messages.send(platform.sender(p), "autosave-done", "id", id.abbrev(), "cells", r.cells());
    }));
    hud.tick();
    workspace.unloadIdleTick(settings.unloadIdleTicks());
  }

  /** 指令執行前：其他插件或模組晚註冊的指令也包上原因。 */
  public void beforeCommand() {
    if (commands != null) commands.ensure();
  }

  /** 世界載入（主執行緒）：掛上鄰居更新器。 */
  public void levelLoaded(ServerLevel level) {
    tracker.precise(level, CascadeUpdater.install(platform, level, tracker));
  }

  /** 剛生成一個 chunk（主執行緒）。 */
  public void newChunk(ServerLevel level, LevelChunk chunk) {
    workspace.newChunk(level, chunk);
  }

  public void changedWorld(ServerPlayer player, ServerLevel from) {
    outlines.forget(player.getUUID());
    hud.changedWorld(player, from);
  }

  public void quit(ServerPlayer player) {
    outlines.forget(player.getUUID());
    selections.quit(player.getUUID());
    command.quit(player.getUUID());
    hud.quit(player);
  }
}
