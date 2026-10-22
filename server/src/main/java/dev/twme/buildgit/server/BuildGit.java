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
  private final BuildWorlds worlds;
  private CommandHook commands;
  private Outlines outlines;
  private Selections selections;
  private Hud hud;
  private Menu menu;
  private BgCommand command;
  private Sync sync;
  private WorldUpload worldUpload;
  private LocalSync localSync;
  private final Backup backup;
  private final Contexts contexts = new Contexts();
  private final Realms realms = new Realms(this);
  private final Submissions submissions = new Submissions(this);
  private long ticks;
  private String openError;

  public BuildGit(Platform platform) {
    this.platform = platform;
    settings = Settings.load(platform.configDir(), platform.log());
    messages = new Messages(platform.configDir(), settings.language(), platform.log());
    tracker = new Tracker(settings, platform::tick);
    worlds = new BuildWorlds(platform, tracker, () -> settings);
    outlines = new Outlines(platform);
    selections = new Selections(platform);
    hud = new Hud(this);
    menu = new Menu(this);
    sync = new Sync(this);
    worldUpload = new WorldUpload(this);
    localSync = new LocalSync(this);
    backup = new Backup(this);
    command = new BgCommand(this);
    PermissionSets.roles = p -> {
      var ws = worlds.of(p.level());
      if (ws.buildWorld() == null) return null;
      var role = ws.role(p.getUUID());
      return role != null ? role : WorldConfig.Role.VISITOR;
    };
    PermissionSets.serverRoles = p -> realms.inServerWorld(p.level()) ? worlds.server().serverRole(p.getUUID()) : null;
    PermissionSets.visitorCopy = p -> {
      var ws = worlds.of(p.level());
      return ws.buildWorld() != null && ws.config() != null && ws.config().allowFork;
    };
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

  /** 伺服器世界的版本庫。玩家所在的版本庫用 {@link #workspace(ServerLevel)}。 */
  public Workspace workspace() {
    return worlds.server();
  }

  /** 世界所屬的版本庫：建築世界（含它的草稿、檢視世界）各自的，其他都是伺服器世界的。 */
  public Workspace workspace(ServerLevel level) {
    return worlds.of(level);
  }

  public BuildWorlds worlds() {
    return worlds;
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

  WorldUpload worldUpload() {
    return worldUpload;
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

  Contexts contexts() {
    return contexts;
  }

  Submissions submissions() {
    return submissions;
  }

  Realms realms() {
    return realms;
  }

  /** 重生的地方。 */
  public record Respawn(ServerLevel level, double x, double y, double z, float yaw, float pitch) {}

  /**
   * 平台決定死後重生的地方時呼叫（{@code target} 是原版選的世界）：死在建築世界（含草稿）、那個世界打開 {@code respawn}、
   * 原版選的不在這個世界（沒有在這裡的床、重生錨）時，改成死掉的那個世界的出生點；其他照原版（null）。
   */
  public Respawn respawn(ServerLevel died, ServerLevel target) {
    var ws = worlds.of(died);
    if (ws.buildWorld() == null || ws.config() == null || !ws.config().respawn || worlds.of(target) == ws) return null;
    var s = ws.config().spawn;
    if (s != null) return new Respawn(died, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
    died.getChunk(0, 0);
    return new Respawn(died, 0.5, died.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0), 0.5, 0, 0);
  }

  /** 平台擋下建築世界裡的傳送門時呼叫：提醒玩家（有節制）。 */
  public void portalRefused(ServerPlayer player) {
    realms.portalRefused(player);
  }

  /** 用戶端送來的 {@code buildgit:sync} 封包（主執行緒）。 */
  public void syncReceived(ServerPlayer player, byte[] data) {
    if (workspace().isOpen()) sync.receive(player, data);
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
      workspace().open();
    } catch (IOException e) {
      openError = e.getMessage();
      platform.log().log(Level.SEVERE, "無法開啟版本庫，記錄停用", e);
    }
    if (!workspace().isOpen() && openError == null) platform.log().info("尚未初始化版本控制；用 /bg init 開始");
    worlds.open();
    worlds.purge();
    backup.start();
  }

  /** 版本庫存在但開不了（例如被較新的 Minecraft 寫過）的原因；沒有錯誤時為 null。 */
  public String openError() {
    return openError;
  }

  public void disable() {
    // 關閉時玩家的離線事件可能在停用之後才發生：先記下還在草稿裡的玩家
    for (var p : platform.server().getPlayerList().getPlayers()) for (var ws : worlds.all()) ws.playerLeft(p);
    if (platform.syncFolder() != null) localSync.packAll(platform.syncFolder());
    backup.stop();
    platform.clearBars();
    outlines.clearAll();
    if (commands != null) commands.unwrap();
    for (var level : platform.server().getAllLevels()) CascadeUpdater.uninstall(platform, level);
    worlds.close();
    workspace().close();
  }

  void reload() {
    settings = Settings.load(platform.configDir(), platform.log());
    messages = new Messages(platform.configDir(), settings.language(), platform.log());
    tracker.settings(settings);
    backup.start();
    worlds.keepLoaded();
  }

  /** 每刻開始時（主執行緒）。 */
  public void tick() {
    tracker.tick();
    ticks++;
    contexts.tick(worlds, platform.server().getPlayerList().getPlayers());
    if (ticks % settings.journalFlushTicks() == 0) {
      var all = worlds.all();
      platform.async(() -> all.forEach(Workspace::flushJournal));
    }
    if (ticks % 20 != 0) return;
    for (var ws : worlds.all())
      ws.autosaveTick(r -> r.commit().ifPresent(id -> {
        // 建築世界的自動存檔只通知在那個世界裡的人
        for (var p : platform.server().getPlayerList().getPlayers())
          if ((ws.buildWorld() == null || worlds.of(p.level()) == ws) && platform.hasPermission(p, "buildgit.status"))
            messages.send(platform.sender(p), "autosave-done", "id", id.abbrev(), "cells", r.cells());
      }));
    hud.tick();
    realms.tick();
    sync.tick();
    worldUpload.tick();
    for (var ws : worlds.all()) ws.unloadIdleTick(settings.unloadIdleTicks(), worlds.kept(ws));
    if (ticks % 600 == 40) worlds.keepLoaded();
    if (ticks % (20L * 3600 * 24) == 0) worlds.purge();
  }

  /** 指令執行前：其他插件或模組晚註冊的指令也包上原因。 */
  public void beforeCommand() {
    if (commands != null) commands.ensure();
  }

  /** 世界載入（主執行緒）：掛上鄰居更新器。 */
  public void levelLoaded(ServerLevel level) {
    tracker.precise(level, CascadeUpdater.install(platform, level, tracker));
    // 載入事件期間不存檔、不開新世界：下一刻再納入版本控制
    platform.later(1, () -> {
      for (var ws : worlds.all()) ws.worldLoaded(level);
    });
  }

  /** 世界將被卸載。回傳 false 表示應該擋下（見 {@link Workspace#mayUnload}）；放行或擋不下時之後呼叫 {@link #levelUnloaded}。 */
  public boolean levelUnloading(ServerLevel level) {
    return worlds.of(level).mayUnload(level);
  }

  public void levelUnloaded(ServerLevel level) {
    worlds.of(level).worldUnloaded(level);
  }

  /** 載入一個已存在的 chunk（主執行緒）。 */
  public void chunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
    workspace().chunkLoaded(level, chunkX, chunkZ);
  }

  /** 剛生成一個 chunk（主執行緒）。 */
  public void newChunk(ServerLevel level, LevelChunk chunk) {
    worlds.of(level).newChunk(level, chunk);
  }

  public void changedWorld(ServerPlayer player, ServerLevel from) {
    outlines.forget(player.getUUID());
    hud.changedWorld(player, from);
    // 在建築世界裡權限由角色決定：換世界後重送指令清單，補全與能不能打指令跟著變
    if (worlds.of(player.level()) != worlds.of(from)) {
      platform.server().getCommands().sendCommands(player);
      realms.apply(player);
    }
  }

  /** 玩家上線（主執行緒）。 */
  public void joined(ServerPlayer player) {
    // 下一刻再送回草稿或建築世界：Fabric 的上線事件在玩家加進世界之前，這時跨世界傳送會讓玩家同時留在兩個世界
    platform.later(1, () -> {
      if (player.hasDisconnected()) return;
      for (var ws : worlds.all()) ws.playerJoined(player);
      realms.joined(player);
    });
    if (platform.syncFolder() != null) localSync.joined(player, platform.syncFolder());
  }

  public void quit(ServerPlayer player) {
    for (var ws : worlds.all()) ws.playerLeft(player);
    outlines.forget(player.getUUID());
    selections.quit(player.getUUID());
    command.quit(player.getUUID());
    sync.quit(player.getUUID());
    worldUpload.quit(player.getUUID());
    hud.quit(player);
    realms.quit(player);
  }
}
