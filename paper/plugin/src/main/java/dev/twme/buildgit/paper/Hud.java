package dev.twme.buildgit.paper;

import java.util.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;

/**
 * 不在主世界時的提示（設計 §6.6）：草稿、衝突整合、檢視世界各有 bossbar；
 * 檢視世界是唯讀的，玩家的修改一律取消；離開後沒人的檢視世界收掉。
 */
final class Hud implements Listener {
  private static final BossBar.Color[] COLORS = {BossBar.Color.PINK, BossBar.Color.BLUE, BossBar.Color.GREEN, BossBar.Color.YELLOW,
      BossBar.Color.PURPLE, BossBar.Color.WHITE};
  private final BuildGitPlugin plugin;
  private final Map<UUID, BossBar> bars = new HashMap<>();
  private final Map<UUID, Long> warned = new HashMap<>();

  Hud(BuildGitPlugin plugin) {
    this.plugin = plugin;
  }

  private Workspace ws() {
    return plugin.workspace();
  }

  /** 每秒更新。 */
  void tick() {
    for (var p : Bukkit.getOnlinePlayers()) update(p);
  }

  private void update(Player p) {
    var msg = plugin.messages();
    Component text = null;
    BossBar.Color color = BossBar.Color.WHITE;
    if (ws().isOpen()) {
      var world = p.getWorld();
      var wt = ws().worktree(world);
      var view = ws().view(world);
      var session = ws().merges().of(world);
      if (session != null && wt != null) {
        var cached = ws().merges().result(session).getNow(null);
        text = msg.get("hud-merge", "source", session.source() == null ? session.base().abbrev() : session.source(), "target", session.target(),
            "conflicts", cached == null ? "?" : String.valueOf(cached.cellConflicts().size() + cached.entityConflicts().size()),
            "cells", wt.table().cellCount());
        color = BossBar.Color.RED;
      } else if (view != null) {
        text = msg.get(session != null ? "hud-merge-view" : "hud-view", "id", view.commit().abbrev(),
            "side", session == null ? "" : view.commit().equals(session.ours()) ? session.target() : String.valueOf(session.source()));
      } else if (wt != null && !wt.branch().equals(dev.twme.buildgit.core.repo.Repository.MAIN)) {
        text = msg.get("hud-draft", "name", wt.branch(), "cells", wt.table().cellCount(), "entities", wt.table().entityCount());
        color = COLORS[Math.floorMod(wt.branch().hashCode(), COLORS.length)];
      }
    }
    var bar = bars.get(p.getUniqueId());
    if (text == null) {
      if (bar != null) p.hideBossBar(bar);
      bars.remove(p.getUniqueId());
      return;
    }
    if (bar == null) {
      bar = BossBar.bossBar(text, 1f, color, BossBar.Overlay.PROGRESS);
      bars.put(p.getUniqueId(), bar);
      p.showBossBar(bar);
    } else {
      bar.name(text);
      bar.color(color);
    }
  }

  void hideAll() {
    for (var e : bars.entrySet()) {
      var p = Bukkit.getPlayer(e.getKey());
      if (p != null) p.hideBossBar(e.getValue());
    }
    bars.clear();
  }

  // ---- 檢視世界唯讀 ----

  private boolean readOnly(Player p) {
    if (!ws().isOpen() || ws().view(p.getWorld()) == null) return false;
    long now = System.currentTimeMillis();
    if (now - warned.getOrDefault(p.getUniqueId(), 0L) > 3000) {
      warned.put(p.getUniqueId(), now);
      plugin.messages().send(p, "view-readonly");
    }
    return true;
  }

  @EventHandler(ignoreCancelled = true)
  public void onBreak(BlockBreakEvent e) {
    if (readOnly(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent e) {
    if (readOnly(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onBucket(PlayerBucketEmptyEvent e) {
    if (readOnly(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onBucket(PlayerBucketFillEvent e) {
    if (readOnly(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent e) {
    if ((e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.PHYSICAL) && readOnly(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onInteractEntity(PlayerInteractEntityEvent e) {
    if (readOnly(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onArmorStand(PlayerArmorStandManipulateEvent e) {
    if (readOnly(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onDamage(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p && readOnly(p)) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void onHanging(HangingBreakByEntityEvent e) {
    if (e.getRemover() instanceof Player p && readOnly(p)) e.setCancelled(true);
  }

  // ---- 離開 ----

  @EventHandler
  public void onChangeWorld(PlayerChangedWorldEvent e) {
    if (ws().isOpen()) ws().left(e.getFrom());
    update(e.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    bars.remove(e.getPlayer().getUniqueId());
    warned.remove(e.getPlayer().getUniqueId());
    if (ws().isOpen()) ws().left(e.getPlayer().getWorld());
  }
}
