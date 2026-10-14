package dev.twme.buildgit.paper.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.paper.Settings;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

/**
 * 變更追蹤的中心：哪些世界受版本控制、目前執行緒的原因（cause），以及標記的入口。
 *
 * <p>所有掛點（事件、NMS、WorldEdit、Axiom、API）都只呼叫這裡，不直接碰標記表。
 * 標記只記位置與作者，從不讀取世界，所以可以在任何執行緒呼叫，成本固定且很低。
 */
public final class Tracker {
  private volatile Settings settings;
  private volatile MarkTable table;
  /** Bukkit 世界 UUID → 標記表的維度。只有受版本控制的世界在這裡。 */
  private final Map<UUID, MarkTable.Dimension> worlds = new ConcurrentHashMap<>();
  private final Map<UUID, Author> authors = new ConcurrentHashMap<>();

  /** 目前執行緒的原因：指令、API 範圍。可以巢狀。 */
  private final ThreadLocal<ArrayDeque<Author>> cause = ThreadLocal.withInitial(ArrayDeque::new);

  /** 本刻由自然事件（生長、蔓延、消退）造成的位置：連帶歸屬要略過。只在主執行緒使用。 */
  private final Set<Long> suppressed = new HashSet<>();
  private World suppressedWorld;
  private final Set<UUID> precise = ConcurrentHashMap.newKeySet();

  /**
   * 不在目前世界、但被追蹤的實體（卸載、換維度）。提交時這些實體延後到再次載入，
   * 不能誤當成被移除。值為最後所在維度。
   */
  private final Map<UUID, String> unloadedEntities = new ConcurrentHashMap<>();

  /** FAWE 寫入了實體的 chunk：之後短時間內在這裡生成的實體歸給該作者。 */
  private final Map<Long, PendingSpawn> pendingSpawns = new ConcurrentHashMap<>();

  private record PendingSpawn(UUID world, Author author, long until) {}

  public Tracker(Settings settings) {
    this.settings = settings;
  }

  public Settings settings() {
    return settings;
  }

  public void settings(Settings settings) {
    this.settings = settings;
  }

  // ---- 受追蹤的世界 ----

  /** 換上新的標記表並指定受追蹤的世界（啟用或 init 之後）。 */
  public void attach(MarkTable table, Collection<World> tracked) {
    this.table = table;
    worlds.clear();
    for (var w : tracked) worlds.put(w.getUID(), table.dimension(dimensionId(w)));
  }

  public void detach() {
    worlds.clear();
    table = null;
  }

  public MarkTable table() {
    return table;
  }

  public static String dimensionId(World world) {
    return world.getKey().toString();
  }

  /** @return 世界不受追蹤時為 null */
  public MarkTable.Dimension dimension(World world) {
    return world == null ? null : worlds.get(world.getUID());
  }

  public boolean isTracked(World world) {
    return dimension(world) != null;
  }

  public long now() {
    var t = table;
    return t == null ? 0 : t.now();
  }

  // ---- 作者 ----

  public Author author(OfflinePlayer player) {
    return authors.computeIfAbsent(player.getUniqueId(), id -> {
      String name = player.getName();
      return new Author(id, name == null ? id.toString() : name);
    });
  }

  public Author author(UUID uuid, String name) {
    var cached = authors.get(uuid);
    if (cached != null && cached.name().equals(name)) return cached;
    var a = new Author(uuid, name);
    authors.put(uuid, a);
    return a;
  }

  // ---- 原因 ----

  /** 目前執行緒的原因；沒有時為 null。 */
  public Author cause() {
    var stack = cause.get();
    return stack.isEmpty() ? null : stack.peek();
  }

  /** 設定原因，直到回傳的範圍關閉。必須在同一執行緒關閉。 */
  public Scope push(Author author) {
    var stack = cause.get();
    stack.push(author);
    return new Scope(stack);
  }

  public static final class Scope implements AutoCloseable {
    private final ArrayDeque<Author> stack;
    private boolean closed;

    private Scope(ArrayDeque<Author> stack) {
      this.stack = stack;
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      stack.pop();
    }
  }

  // ---- 自然變化的抑制 ----

  /** 自然事件即將改變這一格：本刻內不讓連帶歸屬把它算給玩家。 */
  public void suppress(Block block) {
    if (!isTracked(block.getWorld())) return;
    if (suppressedWorld != block.getWorld()) {
      suppressed.clear();
      suppressedWorld = block.getWorld();
    }
    suppressed.add(block.getBlockKey());
  }

  public boolean isSuppressed(World world, int x, int y, int z) {
    return !suppressed.isEmpty() && world == suppressedWorld && suppressed.contains(Block.getBlockKey(x, y, z));
  }

  /** 每刻開始時呼叫。 */
  public void tick() {
    suppressed.clear();
    suppressedWorld = null;
    if (!pendingSpawns.isEmpty()) {
      long now = now();
      pendingSpawns.values().removeIf(p -> p.until < now);
    }
  }

  // ---- 方塊 ----

  /** 玩家直接改變的一格。 */
  public void markBlock(World world, int x, int y, int z, Author author) {
    var d = dimension(world);
    if (d != null) d.markCell(x, y, z, author);
  }

  public void markBlock(Block block, Author author) {
    markBlock(block.getWorld(), block.getX(), block.getY(), block.getZ(), author);
  }

  /**
   * 玩家直接改變的一格。鄰居因此改變的形狀由鄰居更新器（{@code CascadeUpdater}）判斷記錄；
   * 該世界沒掛上更新器時，退回連同 6 個鄰居一起標記（設計 §3.7）。
   */
  public void markAround(Block block, Author author) {
    var d = dimension(block.getWorld());
    if (d == null) return;
    int x = block.getX(), y = block.getY(), z = block.getZ();
    d.markCell(x, y, z, author);
    if (precise.contains(block.getWorld().getUID())) return;
    d.markCell(x - 1, y, z, author);
    d.markCell(x + 1, y, z, author);
    d.markCell(x, y - 1, z, author);
    d.markCell(x, y + 1, z, author);
    d.markCell(x, y, z - 1, author);
    d.markCell(x, y, z + 1, author);
  }

  /** 世界已掛上鄰居更新器，連帶變化能精確判斷。 */
  public void precise(World world, boolean value) {
    if (value) precise.add(world.getUID());
    else precise.remove(world.getUID());
  }

  public void markBox(World world, dev.twme.buildgit.core.model.Box box, Author author) {
    var d = dimension(world);
    if (d != null) d.markBox(box, author);
  }

  public void markSection(World world, int chunkX, int sectionY, int chunkZ, BitSet cells, Author author) {
    var d = dimension(world);
    if (d != null) d.markSection(chunkX, sectionY, chunkZ, cells, author);
  }

  /**
   * 遊戲改變了一格（鄰居更新、形狀更新）。有原因時直接記下；否則看它是否接在最近的玩家變更後面。
   *
   * @return 是否被記錄
   */
  public boolean cascade(MarkTable.Dimension d, World world, int x, int y, int z) {
    var c = cause();
    if (c != null) {
      d.markCell(x, y, z, c);
      return true;
    }
    if (isSuppressed(world, x, y, z)) return false;
    return d.cascade(x, y, z, settings.cascadeWindow(), settings.maxDistance()) != null;
  }

  /** 這一格若稍後真的改變，該記給誰（目前的原因優先，其次是傳染）；不是連帶變化時為 null。 */
  public MarkTable.Probe probe(MarkTable.Dimension d, World world, int x, int y, int z) {
    var c = cause();
    if (c != null) return new MarkTable.Probe(c, false, 0, 0, 0);
    if (isSuppressed(world, x, y, z)) return null;
    return d.probe(x, y, z, settings.cascadeWindow(), settings.maxDistance());
  }

  public void apply(MarkTable.Dimension d, int x, int y, int z, MarkTable.Probe probe) {
    if (probe.hasOrigin()) d.apply(x, y, z, probe);
    else d.markCell(x, y, z, probe.author());
  }

  // ---- 實體 ----

  public void markEntity(Entity entity, Author author) {
    var d = dimension(entity.getWorld());
    if (d == null) return;
    d.markEntity(entity.getUniqueId(), author);
    unloadedEntities.remove(entity.getUniqueId());
  }

  /** 這個實體類型的本身狀態是否值得記錄（設計 §3.4）。 */
  public boolean isBuildEntity(EntityType type) {
    return !settings.ignoredEntities().contains(type);
  }

  /** 被追蹤的實體的作者；沒被追蹤時為 null。 */
  public Author trackedAuthor(Entity entity) {
    var d = dimension(entity.getWorld());
    return d == null ? null : d.entityAuthor(entity.getUniqueId());
  }

  /**
   * 跨維度傳送：實體以同一個 UUID 加入新維度。若它在其他受追蹤維度被追蹤，舊維度記成移除、
   * 新維度以同一位作者繼續追蹤。Paper 不會為換維度發出移除事件，所以在加入時處理。
   */
  public void followMove(Entity entity) {
    if (worlds.size() < 2) return;
    var here = dimension(entity.getWorld());
    if (here == null) return;
    for (var d : worlds.values()) {
      if (d == here) continue;
      var a = d.entityAuthor(entity.getUniqueId());
      if (a == null) continue;
      d.markEntity(entity.getUniqueId(), a);
      markEntity(entity, a);
    }
  }

  /** 實體生成時：目前有原因、或生成在剛被玩家動過的位置，就開始追蹤。 */
  public Author spawnAuthor(Entity entity) {
    var c = cause();
    if (c != null) return c;
    var d = dimension(entity.getWorld());
    if (d == null) return null;
    Location l = entity.getLocation();
    int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
    long window = settings.cascadeWindow();
    var a = d.recentAuthor(x, y, z, window);
    if (a == null) a = d.recentAuthor(x, y - 1, z, window);
    if (a != null) return a;
    var pending = pendingSpawns.get(chunkKey(x >> 4, z >> 4));
    if (pending != null && pending.world.equals(entity.getWorld().getUID()) && pending.until >= now()) return pending.author;
    return null;
  }

  /** 批次寫入（FAWE）在這個 chunk 放了實體，實體稍後才會加入世界。 */
  public void expectSpawns(World world, int chunkX, int chunkZ, Author author) {
    if (!isTracked(world)) return;
    pendingSpawns.put(chunkKey(chunkX, chunkZ), new PendingSpawn(world.getUID(), author, now() + settings.cascadeWindow()));
  }

  private static long chunkKey(int x, int z) {
    return (long) x << 32 | (z & 0xFFFFFFFFL);
  }

  /** 會把原因帶到方塊上的實體（掉落方塊、TNT、投射物）→ 作者。不會進版本，只在記憶體中。 */
  private final Map<UUID, Author> carriers = new ConcurrentHashMap<>();

  public boolean isPropagating(EntityType type) {
    return settings.propagatingEntities().contains(type);
  }

  /** 新實體：依類型開始追蹤本身狀態、或記住它帶著的原因。 */
  public void adopt(Entity entity, Author author) {
    if (isPropagating(entity.getType())) carriers.put(entity.getUniqueId(), author);
    if (isBuildEntity(entity.getType())) markEntity(entity, author);
  }

  /** 這個實體造成的方塊變化該記給誰；沒有時為 null。 */
  public Author carrier(Entity entity) {
    var a = carriers.get(entity.getUniqueId());
    if (a != null) return a;
    return isPropagating(entity.getType()) ? trackedAuthor(entity) : null;
  }

  public void forgetCarrier(UUID uuid) {
    carriers.remove(uuid);
  }

  public void entityUnloaded(UUID uuid, String dimension) {
    unloadedEntities.put(uuid, dimension);
  }

  public void entityLoaded(UUID uuid) {
    unloadedEntities.remove(uuid);
  }

  /** 被追蹤、但目前不在世界中（卸載）的實體。 */
  public boolean isUnloaded(UUID uuid) {
    return unloadedEntities.containsKey(uuid);
  }

  public void forgetUnloaded(UUID uuid) {
    unloadedEntities.remove(uuid);
  }
}
