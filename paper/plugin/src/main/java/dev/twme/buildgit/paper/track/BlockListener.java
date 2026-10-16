package dev.twme.buildgit.paper.track;

import dev.twme.buildgit.core.model.Author;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;

/**
 * 玩家直接造成的方塊變化，以及要排除的自然變化（設計 §3.2、§3.7）。事件清單對照 CoreProtect。
 *
 * <p>全部在 MONITOR、忽略已取消的事件：只記真的會發生的變化。標記只記位置，存檔時才讀內容，
 * 所以這裡不讀方塊狀態也不複製容器內容。
 */
public final class BlockListener implements Listener {
  private final BukkitTracker tracker;

  public BlockListener(BukkitTracker tracker) {
    this.tracker = tracker;
  }

  private Author author(Player player) {
    return tracker.author(player);
  }

  // ---- 放置與破壞 ----

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent e) {
    var a = author(e.getPlayer());
    tracker.markAround(e.getBlockPlaced(), a);
    if (e instanceof BlockMultiPlaceEvent multi)
      for (BlockState s : multi.getReplacedBlockStates()) tracker.markAround(s.getBlock(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent e) {
    tracker.markAround(e.getBlock(), author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBucketEmpty(PlayerBucketEmptyEvent e) {
    var a = author(e.getPlayer());
    tracker.markAround(e.getBlock(), a);
    tracker.markAround(e.getBlockClicked(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBucketFill(PlayerBucketFillEvent e) {
    var a = author(e.getPlayer());
    tracker.markAround(e.getBlock(), a);
    tracker.markAround(e.getBlockClicked(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSign(SignChangeEvent e) {
    tracker.markBlock(e.getBlock(), author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onIgnite(BlockIgniteEvent e) {
    if (e.getPlayer() != null) tracker.markAround(e.getBlock(), author(e.getPlayer()));
    else carried(e.getIgnitingEntity(), e.getBlock(), true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFertilize(BlockFertilizeEvent e) {
    if (e.getPlayer() == null) return;
    var a = author(e.getPlayer());
    tracker.markAround(e.getBlock(), a);
    for (var s : e.getBlocks()) tracker.markBlock(s.getBlock(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent e) {
    if (e.getPlayer() != null) {
      var a = author(e.getPlayer());
      tracker.markAround(e.getLocation().getBlock(), a);
      for (var s : e.getBlocks()) tracker.markBlock(s.getBlock(), a);
    } else {
      for (var s : e.getBlocks()) tracker.suppress(s.getBlock());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPortal(PortalCreateEvent e) {
    if (!(e.getEntity() instanceof Player p)) return;
    var a = author(p);
    for (var s : e.getBlocks()) tracker.markBlock(s.getBlock(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onHarvest(PlayerHarvestBlockEvent e) {
    tracker.markBlock(e.getHarvestedBlock(), author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onLecternTake(PlayerTakeLecternBookEvent e) {
    tracker.markBlock(e.getLectern().getBlock(), author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCauldron(CauldronLevelChangeEvent e) {
    if (e.getEntity() instanceof Player p) tracker.markBlock(e.getBlock(), author(p));
    else carried(e.getEntity(), e.getBlock(), false);
  }

  // ---- 互動：門、拉桿、按鈕、音符盒、中繼器、花盆、蠟燭、剝樹皮、耕地等 ----

  /**
   * 右鍵互動有上百種方塊行為，逐一列舉一定會漏。這裡把點到的格子與它的鄰居、以及點擊面那一格都標記：
   * 多標記的格子內容沒變，存檔時自然略過。即使事件之後被其他插件拒絕，也只是多標記。
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onInteract(PlayerInteractEvent e) {
    var block = e.getClickedBlock();
    if (block == null || e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY && e.useItemInHand() == org.bukkit.event.Event.Result.DENY)
      return;
    if (!tracker.isTracked(block.getWorld())) return;
    var a = author(e.getPlayer());
    switch (e.getAction()) {
      case RIGHT_CLICK_BLOCK -> {
        tracker.markAround(block, a);
        tracker.markBlock(block.getRelative(e.getBlockFace()), a);
      }
      case LEFT_CLICK_BLOCK -> tracker.markBlock(block.getRelative(e.getBlockFace()), a); // 撲滅火焰
      case PHYSICAL -> tracker.markAround(block, a); // 壓力板、踩壞耕地、絆線、海龜蛋
      default -> {}
    }
  }

  // ---- 容器 ----

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent e) {
    if (e.getWhoClicked() instanceof Player p) container(e.getInventory(), p);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInventoryDrag(InventoryDragEvent e) {
    if (e.getWhoClicked() instanceof Player p) container(e.getInventory(), p);
  }

  private void container(Inventory inventory, Player player) {
    // 不取快照：只需要位置，避免 CoreProtect 那種每次點擊複製整個容器的成本。
    var holder = inventory.getHolder(false);
    var a = author(player);
    switch (holder) {
      case BlockInventoryHolder b -> tracker.markBlock(b.getBlock(), a);
      case DoubleChest d -> {
        if (d.getLeftSide(false) instanceof BlockInventoryHolder l) tracker.markBlock(l.getBlock(), a);
        if (d.getRightSide(false) instanceof BlockInventoryHolder r) tracker.markBlock(r.getBlock(), a);
      }
      case Entity entity when tracker.isBuildEntity(entity.getType()) -> tracker.markEntity(entity, a);
      case null, default -> {
        Location l = inventory.getLocation();
        if (l != null && !(holder instanceof Player)) tracker.markBlock(l.getBlock(), a);
      }
    }
  }

  // ---- 活塞、流體、爆炸：沿用觸發者 ----

  /** 這一格附近剛被誰動過；用於活塞、流體等以方塊為來源的事件。 */
  private Author recent(Block block) {
    var c = tracker.cause();
    if (c != null) return c;
    var d = tracker.dimension(block.getWorld());
    return d == null ? null : d.recentAuthor(block.getX(), block.getY(), block.getZ(), tracker.settings().cascadeWindow());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent e) {
    var a = recent(e.getBlock());
    if (a == null) return;
    var dir = e.getDirection();
    tracker.markAround(e.getBlock().getRelative(dir), a);
    for (var b : e.getBlocks()) {
      tracker.markBlock(b, a);
      tracker.markAround(b.getRelative(dir), a);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent e) {
    var a = recent(e.getBlock());
    if (a == null) return;
    var dir = e.getDirection();
    tracker.markAround(e.getBlock().getRelative(dir.getOppositeFace()), a);
    for (var b : e.getBlocks()) {
      tracker.markBlock(b, a);
      tracker.markAround(b.getRelative(dir), a);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFlow(BlockFromToEvent e) {
    var a = recent(e.getBlock());
    if (a != null) tracker.markBlock(e.getToBlock(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSponge(SpongeAbsorbEvent e) {
    var a = recent(e.getBlock());
    if (a == null) return;
    tracker.markBlock(e.getBlock(), a);
    for (var s : e.getBlocks()) tracker.markBlock(s.getBlock(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent e) {
    // 床、重生錨：引爆的那一格是玩家點的，已被標記。
    var a = recent(e.getBlock());
    if (a == null) return;
    tracker.markAround(e.getBlock(), a);
    for (var b : e.blockList()) tracker.markAround(b, a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityChangeBlock(EntityChangeBlockEvent e) {
    if (e.getEntity() instanceof Player p) {
      tracker.markAround(e.getBlock(), author(p));
      return;
    }
    carried(e.getEntity(), e.getBlock(), true);
  }

  /** 實體造成的方塊變化：帶著玩家原因的實體（掉落方塊、TNT、投射物）記下；其他是自然變化。 */
  private void carried(Entity entity, Block block, boolean around) {
    var a = entity == null ? null : tracker.carrier(entity);
    if (a == null) a = tracker.cause();
    if (a == null) {
      tracker.suppress(block);
      return;
    }
    if (around) tracker.markAround(block, a);
    else tracker.markBlock(block, a);
  }

  // ---- 自然變化：本刻內不讓連帶歸屬把它算進玩家的版本 ----

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGrow(BlockGrowEvent e) {
    if (tracker.cause() == null) tracker.suppress(e.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSpread(BlockSpreadEvent e) {
    if (tracker.cause() == null) tracker.suppress(e.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onForm(BlockFormEvent e) {
    if (tracker.cause() == null && !(e instanceof BlockSpreadEvent)) tracker.suppress(e.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFade(BlockFadeEvent e) {
    if (tracker.cause() == null) tracker.suppress(e.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDecay(LeavesDecayEvent e) {
    tracker.suppress(e.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBurn(BlockBurnEvent e) {
    tracker.suppress(e.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMoisture(MoistureChangeEvent e) {
    tracker.suppress(e.getBlock());
  }
}
