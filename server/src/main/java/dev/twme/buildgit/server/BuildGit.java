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
  private Sync sync;
  private LocalSync localSync;
  private final Backup backup;
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
    sync = new Sync(this);
    localSync = new LocalSync(this);
    backup = new Backup(this);
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

  Sync sync() {
    return sync;
  }

  LocalSync localSync() {
    return localSync;
  }

  /** 有下載好的異地備份還原時換上（設計 §2.5）。必須在世界載入之前呼叫；失敗時丟出 {@link IllegalStateException}。 */
  public static void applyRestore(java.nio.file.Path repoDir, java.util.logging.Logger log) {
    Restore.apply(repoDir, log);
  }

  Backup backup() {
    return backup;
  }

  /** 用戶端送來的 {@code buildgit:sync} 封包（主執行緒）。 */
  public void syncReceived(ServerPlayer player, byte[] data) {
    if (workspace.isOpen()) sync.receive(player, data);
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
    backup.start();
  }

  /** 版本庫存在但開不了（例如被較新的 Minecraft 寫過）的原因；沒有錯誤時為 null。 */
  public String openError() {
    return openError;
  }

  public void disable() {
    // 關閉時玩家的離線事件可能在停用之後才發生：先記下還在草稿裡的玩家
    for (var p : platform.server().getPlayerList().getPlayers()) workspace.playerLeft(p);
    if (platform.syncFolder() != null) localSync.packAll(platform.syncFolder());
    backup.stop();
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
    backup.start();
  }

  /** 每刻開始時（主執行緒）。 */
  public void tick() {
    tracker.tick();
    ticks++;
    if (ticks % settings.journalFlushTicks() == 0) platform.async(workspace::flushJournal);
    if (ticks % 20 != 0) return;
    workspace.autosaveTick(r -> r.commit().ifPresent(id -> {
      for (var p : platform.server().getPlayerList().getPlayers())
        if (platform.hasPermission(p, "buildgit.status")) messages.send(platform.sender(p), "autosave-done", "id", id.abbrev(), "cells", r.cells());
    }));
    hud.tick();
    sync.tick();
    workspace.unloadIdleTick(settings.unloadIdleTicks());
  }

  /** 指令執行前：其他插件或模組晚註冊的指令也包上原因。 */
  public void beforeCommand() {
    if (commands != null) commands.ensure();
  }

  /** 世界載入（主執行緒）：掛上鄰居更新器。 */
  public void levelLoaded(ServerLevel level) {
    tracker.precise(level, CascadeUpdater.install(platform, level, tracker));
    // 載入事件期間不存檔、不開新世界：下一刻再納入版本控制
    platform.later(1, () -> workspace.worldLoaded(level));
  }

  /** 世界將被卸載。回傳 false 表示應該擋下（見 {@link Workspace#mayUnload}）；放行或擋不下時之後呼叫 {@link #levelUnloaded}。 */
  public boolean levelUnloading(ServerLevel level) {
    return workspace.mayUnload(level);
  }

  public void levelUnloaded(ServerLevel level) {
    workspace.worldUnloaded(level);
  }

  /** 載入一個已存在的 chunk（主執行緒）。 */
  public void chunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
    workspace.chunkLoaded(level, chunkX, chunkZ);
  }

  /** 剛生成一個 chunk（主執行緒）。 */
  public void newChunk(ServerLevel level, LevelChunk chunk) {
    workspace.newChunk(level, chunk);
  }

  public void changedWorld(ServerPlayer player, ServerLevel from) {
    outlines.forget(player.getUUID());
    hud.changedWorld(player, from);
  }

  /** 玩家上線（主執行緒）。 */
  public void joined(ServerPlayer player) {
    workspace.playerJoined(player);
    if (platform.syncFolder() != null) localSync.joined(player, platform.syncFolder());
  }

  public void quit(ServerPlayer player) {
    workspace.playerLeft(player);
    outlines.forget(player.getUUID());
    selections.quit(player.getUUID());
    command.quit(player.getUUID());
    sync.quit(player.getUUID());
    hud.quit(player);
  }
}
