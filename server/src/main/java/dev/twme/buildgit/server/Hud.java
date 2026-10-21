package dev.twme.buildgit.server;

import dev.twme.buildgit.core.repo.Repository;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 不在主世界時的提示（設計 §6.6）：草稿、衝突整合、檢視世界各有 bossbar；花時間的動作進行中時，執行的玩家改看到進度。
 * 檢視世界是唯讀的，玩家的修改由平台的掛點詢問 {@link #readOnly} 後取消；離開後沒人的檢視世界收掉。
 */
public final class Hud {
  private static final Platform.BarColor[] COLORS = {Platform.BarColor.PINK, Platform.BarColor.BLUE, Platform.BarColor.GREEN,
      Platform.BarColor.YELLOW, Platform.BarColor.PURPLE, Platform.BarColor.WHITE};
  private final BuildGit bg;
  private final Map<UUID, Long> warned = new HashMap<>();
  private final Map<UUID, Task> tasks = new HashMap<>();

  /** {@code progress} 為 null 時只顯示經過的秒數。 */
  private record Task(Component label, Progress progress, long start) {}

  Hud(BuildGit bg) {
    this.bg = bg;
  }

  private Workspace ws(ServerLevel level) {
    return bg.workspace(level);
  }

  /** 每秒更新。 */
  void tick() {
    for (var p : bg.platform().server().getPlayerList().getPlayers()) update(p);
  }

  /** 開始在玩家的 bossbar 上顯示進度，直到 {@link #end}（主執行緒）。主控台不顯示。 */
  void start(Sender sender, Component label, Progress progress) {
    if (!(sender.player() instanceof ServerPlayer p)) return;
    tasks.put(p.getUUID(), new Task(label, progress, System.currentTimeMillis()));
    update(p);
  }

  /** 動作結束（成功或失敗都要呼叫），bossbar 回到平常的提示。 */
  void end(Sender sender) {
    if (sender.player() instanceof ServerPlayer p && tasks.remove(p.getUUID()) != null) update(p);
  }

  void update(ServerPlayer p) {
    var msg = bg.messages();
    var task = tasks.get(p.getUUID());
    if (task != null) {
      float f = task.progress() == null ? -1 : task.progress().fraction();
      long seconds = (System.currentTimeMillis() - task.start()) / 1000;
      var text = f < 0 ? msg.get("progress-elapsed", "label", task.label(), "seconds", seconds)
          : msg.get("progress-percent", "label", task.label(), "percent", (int) (f * 100), "seconds", seconds);
      bg.platform().bar(p, text, Platform.BarColor.GREEN, f < 0 ? 1f : f);
      return;
    }
    Component text = null;
    var color = Platform.BarColor.WHITE;
    var world = p.level();
    var ws = ws(world);
    if (ws.isOpen()) {
      var wt = ws.worktree(world);
      var view = ws.view(world);
      var session = ws.merges().of(world);
      if (session != null && wt != null) {
        var cached = ws.merges().result(session).getNow(null);
        text = msg.get("hud-merge", "source", session.source() == null ? session.base().abbrev() : session.source(), "target", session.target(),
            "conflicts", cached == null ? "?" : String.valueOf(cached.cellConflicts().size() + cached.entityConflicts().size()),
            "cells", wt.table().cellCount());
        color = Platform.BarColor.RED;
      } else if (view != null) {
        text = msg.get(session != null ? "hud-merge-view" : "hud-view", "id", view.commit().abbrev(),
            "side", session == null ? "" : view.commit().equals(session.ours()) ? session.target() : String.valueOf(session.source()));
      } else if (wt != null && !wt.branch().equals(Repository.MAIN)) {
        text = msg.get("hud-draft", "name", wt.branch(), "cells", wt.table().cellCount(), "entities", wt.table().entityCount());
        color = COLORS[Math.floorMod(wt.branch().hashCode(), COLORS.length)];
      } else if (wt != null && ws.buildWorld() != null) text = msg.get("hud-world", "world", ws.label());
      if (text != null && ws.buildWorld() != null && wt != null && !wt.branch().equals(Repository.MAIN))
        text = msg.get("hud-world-prefix", "world", ws.label(), "text", text);
    }
    bg.platform().bar(p, text, color, 1f);
  }

  /**
   * 玩家在檢視世界裡想改東西：回傳 true 表示要取消，並（每 3 秒最多一次）提醒玩家。
   * 平台在放置、破壞、互動、桶子、實體互動與攻擊的掛點呼叫。
   */
  public boolean readOnly(ServerPlayer p) {
    var ws = ws(p.level());
    if (!ws.isOpen() || ws.view(p.level()) == null) return false;
    long now = System.currentTimeMillis();
    if (now - warned.getOrDefault(p.getUUID(), 0L) > 3000) {
      warned.put(p.getUUID(), now);
      bg.messages().send(bg.platform().sender(p), "view-readonly");
    }
    return true;
  }

  /** 玩家換了世界。 */
  void changedWorld(ServerPlayer p, ServerLevel from) {
    var ws = ws(from);
    if (ws.isOpen()) ws.left(from);
    update(p);
  }

  void quit(ServerPlayer p) {
    warned.remove(p.getUUID());
    tasks.remove(p.getUUID());
    var ws = ws(p.level());
    if (ws.isOpen()) ws.left(p.level());
  }
}
