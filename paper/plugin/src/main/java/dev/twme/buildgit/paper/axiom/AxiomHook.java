package dev.twme.buildgit.paper.axiom;

import com.moulberry.axiom.event.AxiomAfterManipulateEntityEvent;
import com.moulberry.axiom.event.AxiomManipulateEntityEvent;
import com.moulberry.axiom.event.AxiomRemoveEntityEvent;
import com.moulberry.axiom.event.AxiomSpawnEntityEvent;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.integration.SectionPermissionChecker;
import dev.twme.buildgit.paper.track.BukkitTracker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
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
 * <p>實體：AxiomPaper 只透過生成、修改、刪除三種封包碰實體（貼上剪貼簿裡的實體也是生成），三者都有事件。
 * 事件只帶一個實體，但生成會連乘客一起加入、修改可以讓別的實體騎上或下來；載具的內容包含乘客，
 * 所以整串（最外層載具與它所有的乘客）都記下。
 *
 * <p>已知限制：擁有 {@code axiomadmin.bypass_region_checks} 權限的玩家，AxiomPaper 不會詢問整合，
 * 他們的方塊緩衝區編輯無法記錄（此權限預設不給任何人，包括 OP）。
 */
public final class AxiomHook implements Listener {
  private static final BitSet FULL = new BitSet(4096);

  static {
    FULL.set(0, 4096);
  }

  private final BukkitTracker tracker;
  /** 修改前的整串實體，修改後一起記下（被趕下來的乘客修改後就不在串裡了）。 */
  private final Map<UUID, List<Entity>> beforeManipulate = new HashMap<>();

  public AxiomHook(BukkitTracker tracker) {
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
    var author = tracker.author(e.getPlayer());
    for (var entity : stack(e.getEntity())) tracker.adopt(entity, author);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBeforeManipulate(AxiomManipulateEntityEvent e) {
    beforeManipulate.put(e.getEntity().getUniqueId(), stack(e.getEntity()));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onManipulate(AxiomAfterManipulateEntityEvent e) {
    var author = tracker.author(e.getPlayer());
    var before = beforeManipulate.remove(e.getEntity().getUniqueId());
    if (before != null) for (var entity : before) tracker.markEntity(entity, author);
    for (var entity : stack(e.getEntity())) tracker.markEntity(entity, author);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onRemove(AxiomRemoveEntityEvent e) {
    var author = tracker.author(e.getPlayer());
    for (var entity : stack(e.getEntity())) tracker.markEntity(entity, author);
  }

  /** 實體所在的整串：最外層的載具與它所有的乘客。 */
  private static List<Entity> stack(Entity entity) {
    var root = entity;
    while (root.getVehicle() != null) root = root.getVehicle();
    var result = new ArrayList<Entity>();
    var pending = new ArrayDeque<Entity>(List.of(root));
    while (!pending.isEmpty()) {
      var e = pending.poll();
      result.add(e);
      pending.addAll(e.getPassengers());
    }
    return result;
  }
}
