package dev.twme.buildgit.paper.axiom;

import com.moulberry.axiom.event.AxiomAfterManipulateEntityEvent;
import com.moulberry.axiom.event.AxiomRemoveEntityEvent;
import com.moulberry.axiom.event.AxiomSpawnEntityEvent;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.integration.SectionPermissionChecker;
import dev.twme.buildgit.paper.track.Tracker;
import java.util.BitSet;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Axiom 的記錄掛點（設計 §3.2）。只在伺服器裝了 AxiomPaper 時載入。
 *
 * <p>Axiom 的筆刷、貼上、填充以「方塊緩衝區」直接寫進 chunk section，不經過 Bukkit 事件，也不發鄰居更新。
 * AxiomPaper 在寫入每個 section 之前會詢問所有自訂整合 {@code checkSection} 是否允許；
 * 我們永遠允許，同時把這個 section 記在該玩家名下。存檔時內容沒變的格子會被略過，所以多記整個 section 不影響結果。
 *
 * <p>單格放置與破壞會經過 {@code canPlaceBlock}／{@code canBreakBlock}，同樣記下。
 *
 * <p>已知限制：擁有 {@code axiomadmin.bypass_region_checks} 權限的玩家，AxiomPaper 不會詢問整合，
 * 他們的方塊緩衝區編輯無法記錄（此權限預設不給任何人，包括 OP）。
 */
public final class AxiomHook implements Listener {
  private static final BitSet FULL = new BitSet(4096);

  static {
    FULL.set(0, 4096);
  }

  private final Tracker tracker;

  public AxiomHook(Tracker tracker) {
    this.tracker = tracker;
  }

  public void register(Plugin plugin, Logger log) {
    Integration.registerCustomIntegration(plugin, new Recorder());
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    log.info("記錄 Axiom 的編輯");
  }

  private final class Recorder implements Integration.CustomIntegration {
    @Override
    public boolean canBreakBlock(Player player, Block block) {
      tracker.markAround(block, tracker.author(player));
      return true;
    }

    @Override
    public boolean canPlaceBlock(Player player, Location location) {
      if (location.getWorld() != null) tracker.markAround(location.getBlock(), tracker.author(player));
      return true;
    }

    @Override
    public SectionPermissionChecker checkSection(Player player, World world, int x, int y, int z) {
      tracker.markSection(world, x, y, z, FULL, tracker.author(player));
      return SectionPermissionChecker.ALL_ALLOWED;
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSpawn(AxiomSpawnEntityEvent e) {
    tracker.adopt(e.getEntity(), tracker.author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onManipulate(AxiomAfterManipulateEntityEvent e) {
    tracker.markEntity(e.getEntity(), tracker.author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onRemove(AxiomRemoveEntityEvent e) {
    tracker.markEntity(e.getEntity(), tracker.author(e.getPlayer()));
  }
}
