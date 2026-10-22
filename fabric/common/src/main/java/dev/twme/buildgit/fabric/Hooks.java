package dev.twme.buildgit.fabric;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.server.BuildGit;
import dev.twme.buildgit.server.track.Tracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

/**
 * mixin 呼叫的入口（設計 §3.7）。Fabric 沒有 Bukkit 那樣的事件，改在原版的方法上設定原因：
 * 玩家的操作期間設定原因，這期間 {@code LevelChunk.setBlockState} 與方塊實體的變化直接記給該玩家；
 * 之後的連帶變化由共用的鄰居更新器（{@code CascadeUpdater}）判斷。
 *
 * <p>BuildGit 沒有啟動時（伺服器啟動前、停止後、單人模式切換存檔之間）一律不做事。
 */
public final class Hooks {
  private Hooks() {}

  static volatile BuildGit bg;
  /** 自然變化（隨機刻、降雪、火）進行中：只在主執行緒使用。 */
  private static int natural;

  /** BuildGit 沒有啟動時為 null。 */
  public static Tracker tracker() {
    var b = bg;
    return b == null ? null : b.tracker();
  }

  // ---- 原因 ----

  /** 玩家的操作：期間的變化記給該玩家。BuildGit 沒啟動時回傳 null（try-with-resources 會略過）。 */
  public static Tracker.Scope cause(ServerPlayer player) {
    var t = tracker();
    return t == null ? null : t.push(t.author(player));
  }

  /** 以作者設定原因；沒有作者時不設定。 */
  public static Tracker.Scope cause(Author author) {
    var t = tracker();
    return t == null || author == null ? null : t.push(author);
  }

  /** 玩家，或帶著玩家原因的實體（投射物、掉落方塊、TNT）造成的變化該記給誰。 */
  public static Author source(Entity entity) {
    var t = tracker();
    if (t == null || entity == null) return null;
    if (entity instanceof ServerPlayer p) return t.author(p);
    if (entity instanceof Projectile pr && pr.getOwner() instanceof ServerPlayer p) return t.author(p);
    return t.carrier(entity);
  }

  /** 活塞推動：附近剛被誰動過就記給誰。 */
  public static Author recent(Level level, BlockPos pos) {
    var t = tracker();
    return t == null ? null : t.recent(level, pos.getX(), pos.getY(), pos.getZ());
  }

  public static void naturalBegin() {
    natural++;
  }

  public static void naturalEnd() {
    natural--;
  }

  // ---- 寫入 ----

  /** 一格真的改變了（{@code LevelChunk.setBlockState} 回傳非 null）。 */
  public static void blockChanged(Level level, BlockPos pos) {
    var t = tracker();
    if (t == null) return;
    if (natural > 0) {
      t.suppress(level, pos.getX(), pos.getY(), pos.getZ());
      return;
    }
    var c = t.cause();
    if (c != null) t.markBlock(level, pos.getX(), pos.getY(), pos.getZ(), c);
  }

  /** 方塊實體的內容變了（容器、告示牌、講台）。 */
  public static void blockEntityChanged(Level level, BlockPos pos) {
    var t = tracker();
    if (t == null || level == null || level.isClientSide()) return;
    var c = t.cause();
    if (c != null) t.markBlock(level, pos.getX(), pos.getY(), pos.getZ(), c);
  }

  // ---- 實體 ----

  /** 玩家（或帶著原因的實體）動了這個實體。 */
  public static void touch(Entity entity, Author author) {
    var t = tracker();
    if (t != null) t.touch(entity, author);
  }

  public static void touch(ServerPlayer player, Entity entity) {
    var t = tracker();
    if (t != null) t.touch(entity, t.author(player));
  }

  /** 新實體加入世界之前（{@code ServerLevel.addFreshEntity}）。 */
  public static void spawn(Entity entity) {
    var t = tracker();
    if (t == null || !t.isTracked(entity.level())) return;
    var a = t.spawnAuthor(entity);
    if (a == null && entity instanceof Projectile p && p.getOwner() instanceof ServerPlayer shooter) a = t.author(shooter);
    if (a != null) t.adopt(entity, a);
  }

  public static void entityLoaded(Entity entity) {
    var t = tracker();
    if (t != null) t.entityLoaded(entity);
  }

  public static void entityRemoved(Entity entity) {
    var t = tracker();
    if (t == null) return;
    var reason = entity.getRemovalReason();
    t.entityRemoved(entity, reason == Entity.RemovalReason.UNLOADED_TO_CHUNK || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER);
  }

  // ---- 檢查模式與唯讀 ----

  /** 建築世界裡的傳送門一律不能用（03-worlds §2.2），不然會被送到伺服器的地獄、終界。 */
  public static boolean portalBlocked(Entity entity) {
    var b = bg;
    if (b == null || !(entity.level() instanceof ServerLevel level) || b.workspace(level).buildWorld() == null) return false;
    if (entity instanceof ServerPlayer p) b.portalRefused(p);
    return true;
  }

  /** 死後重生的地方，見 {@link BuildGit#respawn}。 */
  public static BuildGit.Respawn respawn(ServerLevel died, ServerLevel target) {
    var b = bg;
    return b == null ? null : b.respawn(died, target);
  }

  /** 檢視世界裡不能改東西。 */
  public static boolean readOnly(ServerPlayer player, net.minecraft.world.level.block.state.BlockState clicked) {
    var b = bg;
    return b != null && b.hud().readOnly(player, clicked);
  }

  public static boolean readOnly(ServerPlayer player) {
    var b = bg;
    return b != null && b.hud().readOnly(player);
  }

  /** 檢查模式的點擊：回傳 true 表示已處理，原本的動作要取消。 */
  public static boolean inspect(ServerPlayer player, BlockPos pos, Direction face, boolean right, boolean mainHand) {
    var b = bg;
    if (b == null) return false;
    if (!mainHand) return b.command().isInspecting(player);
    return b.command().inspectClick(player, pos, face, right);
  }

  // ---- 生命週期 ----

  public static void beforeCommand() {
    var b = bg;
    if (b != null) b.beforeCommand();
  }

  public static void changedWorld(ServerPlayer player, ServerLevel from) {
    var b = bg;
    if (b != null) b.changedWorld(player, from);
  }

  public static void quit(ServerPlayer player) {
    var b = bg;
    if (b == null) return;
    if (b.platform() instanceof FabricPlatform fp) fp.quit(player);
    b.quit(player);
  }
}
