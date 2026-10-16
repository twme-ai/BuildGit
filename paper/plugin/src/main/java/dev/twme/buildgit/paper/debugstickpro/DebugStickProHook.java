package dev.twme.buildgit.paper.debugstickpro;

import dev.twme.buildgit.paper.track.BukkitTracker;
import dev.twme.buildgit.server.track.Tracker;
import dev.twme.debugstickpro.events.ClassicModeChangingBlockEvent;
import dev.twme.debugstickpro.events.CopyModePasteBlockDataEvent;
import dev.twme.debugstickpro.mode.freeze.FreezeBlockManager;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * DebugStickPro 的記錄掛點（設計 §3.2）。只在伺服器裝了 DebugStickPro 時載入。
 *
 * <p>經典、複製模式以 {@code setBlockData(…, false)} 改方塊狀態：不發鄰居更新，觸發的互動事件也被它取消，
 * 一般的掛點看不到，所以由它自己的事件記下那一格。
 *
 * <p>凍結模式暫時把真的方塊換成屏障，解凍時換回來（解凍不一定有事件，例如玩家離線、插件停用）。
 * 存檔時直接問它哪些格子凍結中，那些格子先不存，等換回來再存，屏障不會進版本。
 */
public final class DebugStickProHook implements Listener {
  private final BukkitTracker tracker;

  public DebugStickProHook(BukkitTracker tracker) {
    this.tracker = tracker;
  }

  public void register(Plugin plugin, Tracker raw, Logger log) {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    raw.setHeld((level, x, y, z) -> {
      try {
        return FreezeBlockManager.isFreezeBlock(new Location(level.getWorld(), x, y, z));
      } catch (LinkageError e) { // DebugStickPro 改了內部類別：不要讓存檔跟著失敗
        raw.setHeld(null);
        log.log(java.util.logging.Level.WARNING, "無法詢問 DebugStickPro 的凍結方塊，之後凍結中的屏障可能被存進版本", e);
        return false;
      }
    });
    log.info("已掛上 DebugStickPro 記錄");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onClassic(ClassicModeChangingBlockEvent e) {
    mark(e.getPlayer(), e.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPaste(CopyModePasteBlockDataEvent e) {
    mark(e.getPlayer(), e.getBlock());
  }

  private void mark(Player player, Block block) {
    if (player == null || block == null || !tracker.isTracked(block.getWorld())) return;
    tracker.markBlock(block, tracker.author(player));
  }
}
