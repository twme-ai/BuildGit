package dev.twme.buildgit.paper.track;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import dev.twme.buildgit.core.model.Author;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

/**
 * 實體：玩家放置或生成的實體成為被追蹤實體（設計 §3.3、§3.4），玩家改動或移除時記下。
 * 另外記住會把原因帶到方塊上的實體（掉落方塊、TNT、投射物）。
 */
public final class EntityListener implements Listener {
  private final Tracker tracker;

  public EntityListener(Tracker tracker) {
    this.tracker = tracker;
  }

  /** 玩家或帶著玩家原因的實體。 */
  private Author source(Entity entity) {
    if (entity instanceof Player p) return tracker.author(p);
    if (entity instanceof Projectile pr && pr.getShooter() instanceof Player p) return tracker.author(p);
    return entity == null ? null : tracker.carrier(entity);
  }

  /** 玩家動了這個實體：被追蹤的或建築用實體記下。 */
  private void touch(Entity entity, Author author) {
    if (author == null || !tracker.isTracked(entity.getWorld())) return;
    if (tracker.isBuildEntity(entity.getType()) || tracker.trackedAuthor(entity) != null) tracker.markEntity(entity, author);
  }

  // ---- 生成 ----

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSpawn(EntitySpawnEvent e) {
    var entity = e.getEntity();
    if (!tracker.isTracked(entity.getWorld())) return;
    var a = tracker.spawnAuthor(entity);
    if (a == null && entity instanceof Projectile p && p.getShooter() instanceof Player shooter) a = tracker.author(shooter);
    if (a != null) tracker.adopt(entity, a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(EntityPlaceEvent e) {
    if (e.getPlayer() != null) tracker.adopt(e.getEntity(), tracker.author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onHangingPlace(HangingPlaceEvent e) {
    if (e.getPlayer() != null) tracker.adopt(e.getEntity(), tracker.author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onLaunch(ProjectileLaunchEvent e) {
    if (e.getEntity().getShooter() instanceof Player p && tracker.isTracked(e.getEntity().getWorld()))
      tracker.adopt(e.getEntity(), tracker.author(p));
  }

  // ---- 改動 ----

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInteract(PlayerInteractEntityEvent e) {
    // 展示框放物品與旋轉、拴繩、命名牌、染色、騎乘等。PlayerInteractAtEntityEvent 是子類別，也會進來。
    touch(e.getRightClicked(), tracker.author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onArmorStand(PlayerArmorStandManipulateEvent e) {
    touch(e.getRightClicked(), tracker.author(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDamage(EntityDamageByEntityEvent e) {
    // 打掉展示框裡的物品、打壞盔甲座、終界水晶。
    touch(e.getEntity(), source(e.getDamager()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onHangingBreak(HangingBreakEvent e) {
    Author a = e instanceof HangingBreakByEntityEvent by ? source(by.getRemover()) : null;
    if (a == null) a = tracker.trackedAuthor(e.getEntity()); // 支撐的方塊被玩家拆掉
    if (a == null) a = tracker.cause();
    touch(e.getEntity(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onVehicleDestroy(VehicleDestroyEvent e) {
    touch(e.getVehicle(), source(e.getAttacker()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onProjectileHit(ProjectileHitEvent e) {
    var a = source(e.getEntity());
    if (a == null) return;
    // 射中按鈕、標靶、裝飾陶罐、紫水晶；射中展示框、盔甲座。
    if (e.getHitBlock() != null) tracker.markAround(e.getHitBlock(), a);
    if (e.getHitEntity() != null) touch(e.getHitEntity(), a);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onExplode(EntityExplodeEvent e) {
    var a = source(e.getEntity());
    if (a == null) {
      for (var b : e.blockList()) tracker.suppress(b);
      return;
    }
    tracker.markAround(e.getLocation().getBlock(), a);
    for (var b : e.blockList()) tracker.markAround(b, a);
  }

  // ---- 載入、卸載與移除 ----

  @EventHandler(priority = EventPriority.MONITOR)
  public void onAdd(EntityAddToWorldEvent e) {
    var entity = e.getEntity();
    tracker.entityLoaded(entity.getUniqueId());
    // 跨維度傳送：新維度的實體以同一個 UUID 加入（先於舊的移除），沿用原作者繼續追蹤。
    tracker.followMove(entity);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onRemove(EntityRemoveEvent e) {
    var entity = e.getEntity();
    var d = tracker.dimension(entity.getWorld());
    if (d == null) return;
    var author = d.entityAuthor(entity.getUniqueId());
    switch (e.getCause()) {
      case UNLOAD, PLAYER_QUIT -> {
        if (author != null) tracker.entityUnloaded(entity.getUniqueId(), d.id());
      }
      default -> {
        // 死亡、被拆除、被撿起：被追蹤的實體在下次存檔記成移除。
        // 沒被追蹤（例如已提交、這次還沒碰過）但正由玩家造成（/kill 等指令）時也記。
        var by = tracker.cause();
        if (author != null) d.markEntity(entity.getUniqueId(), by != null ? by : author);
        else if (by != null && tracker.isBuildEntity(entity.getType())) d.markEntity(entity.getUniqueId(), by);
        tracker.forgetCarrier(entity.getUniqueId());
      }
    }
  }
}
