package dev.twme.buildgit.server.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.server.Settings;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 變更追蹤的中心：哪些世界受版本控制、目前執行緒的原因（cause），以及標記的入口。
 *
 * <p>所有掛點（Paper 事件、Fabric mixin、指令、WorldEdit、Axiom、API）都只呼叫這裡，不直接碰標記表。
 * 標記只記位置與作者，從不讀取世界，所以可以在任何執行緒呼叫，成本固定且很低。
 */
public final class Tracker {
  private volatile Settings settings;
  private final java.util.function.LongSupplier clock;
  /** 正在由 BuildGit 自己寫入世界（合併、套用覆寫）的執行緒：這期間的變化不是玩家造成的。 */
  private volatile Thread systemWriter;
  /** 世界 → 標記表的維度。只有受版本控制的世界在這裡。 */
  private final Map<Level, MarkTable.Dimension> levels = new ConcurrentHashMap<>();
  private final Map<UUID, Author> authors = new ConcurrentHashMap<>();

  /** 目前執行緒的原因：指令、API 範圍。可以巢狀。 */
  private final ThreadLocal<ArrayDeque<Author>> cause = ThreadLocal.withInitial(ArrayDeque::new);
  /** 有任何執行緒設定了原因：沒有時不必查執行緒區域變數（Fabric 的寫入掛點每次方塊變化都會問）。 */
  private final java.util.concurrent.atomic.AtomicInteger causes = new java.util.concurrent.atomic.AtomicInteger();

  /** 本刻由自然事件（生長、蔓延、消退）造成的位置：連帶歸屬要略過。只在主執行緒使用。 */
  private final Set<Long> suppressed = new HashSet<>();
  private Level suppressedLevel;
  private final Set<Level> precise = ConcurrentHashMap.newKeySet();

  /** 批次寫入（FAWE）寫入了實體的 chunk：之後短時間內在這裡生成的實體歸給該作者。 */
  private final Map<Long, PendingSpawn> pendingSpawns = new ConcurrentHashMap<>();

  private record PendingSpawn(Level level, Author author, long until) {}

  public Tracker(Settings settings, java.util.function.LongSupplier clock) {
    this.settings = settings;
    this.clock = clock;
  }

  public Settings settings() {
    return settings;
  }

  public void settings(Settings settings) {
    this.settings = settings;
  }

  // ---- 受追蹤的世界 ----

  /**
   * 開始追蹤一個分支的世界，標記寫進該分支的標記表。
   *
   * @param tracked 世界 → 版本中的維度 id（分支世界對應到主世界的維度）
   */
  public void attach(MarkTable table, Map<ServerLevel, String> tracked) {
    tracked.forEach((w, dim) -> levels.put(w, table.dimension(dim)));
  }

  /** 停止追蹤這個標記表的所有世界。 */
  public void detach(MarkTable table) {
    levels.values().removeIf(d -> d.table() == table);
  }

  public void detachAll() {
    levels.clear();
  }

  /** 世界本身的維度 key。版本中的維度用 {@link #logicalDimension}。 */
  public static String dimensionId(Level level) {
    return level.dimension().identifier().toString();
  }

  /** 世界在版本中的維度 id：分支世界對應到主世界的維度。 */
  public String logicalDimension(Level level) {
    var d = levels.get(level);
    return d != null ? d.id() : dimensionId(level);
  }

  /** @return 世界不受追蹤、或目前執行緒正在做系統寫入時為 null */
  public MarkTable.Dimension dimension(Level level) {
    if (level == null || systemWriter == Thread.currentThread()) return null;
    return levels.get(level);
  }

  public boolean isTracked(Level level) {
    return dimension(level) != null;
  }

  /** 世界屬於哪個標記表（不受系統寫入影響）。 */
  public MarkTable tableOf(Level level) {
    var d = level == null ? null : levels.get(level);
    return d == null ? null : d.table();
  }

  /** 在目前執行緒做系統寫入：期間不標記任何變化。不可巢狀於其他執行緒的系統寫入。 */
  public void systemWrite(Runnable body) {
    var previous = systemWriter;
    systemWriter = Thread.currentThread();
    try {
      body.run();
    } finally {
      systemWriter = previous;
    }
  }

  public long now() {
    return clock.getAsLong();
  }

  // ---- 作者 ----

  public Author author(ServerPlayer player) {
    return author(player.getUUID(), player.getScoreboardName());
  }

  public Author author(UUID uuid, String name) {
    var cached = authors.get(uuid);
    if (cached != null && cached.name().equals(name)) return cached;
    var a = new Author(uuid, name == null ? uuid.toString() : name);
    authors.put(uuid, a);
    return a;
  }

  // ---- 原因 ----

  /** 目前執行緒的原因；沒有時為 null。 */
  public Author cause() {
    if (causes.get() == 0) return null;
    var stack = cause.get();
    return stack.isEmpty() ? null : stack.peek();
  }

  /** 設定原因，直到回傳的範圍關閉。必須在同一執行緒關閉。 */
  public Scope push(Author author) {
    var stack = cause.get();
    stack.push(author);
    causes.incrementAndGet();
    return new Scope(stack);
  }

  public final class Scope implements AutoCloseable {
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
      causes.decrementAndGet();
    }
  }

  // ---- 自然變化的抑制 ----

  /** 自然事件即將改變這一格：本刻內不讓連帶歸屬把它算給玩家。 */
  public void suppress(Level level, int x, int y, int z) {
    if (!isTracked(level)) return;
    if (suppressedLevel != level) {
      suppressed.clear();
      suppressedLevel = level;
    }
    suppressed.add(BlockPos.asLong(x, y, z));
  }

  public boolean isSuppressed(Level level, int x, int y, int z) {
    return !suppressed.isEmpty() && level == suppressedLevel && suppressed.contains(BlockPos.asLong(x, y, z));
  }

  /** 每刻開始時呼叫。 */
  public void tick() {
    suppressed.clear();
    suppressedLevel = null;
    if (!removedCarriers.isEmpty()) {
      for (var uuid : removedCarriers) carriers.remove(uuid);
      removedCarriers.clear();
    }
    if (!pendingSpawns.isEmpty()) {
      long now = now();
      pendingSpawns.values().removeIf(p -> p.until < now);
    }
  }

  // ---- 方塊 ----

  /** 玩家直接改變的一格。 */
  public void markBlock(Level level, int x, int y, int z, Author author) {
    var d = dimension(level);
    if (d != null) d.markCell(x, y, z, author);
  }

  /**
   * 玩家直接改變的一格。鄰居因此改變的形狀由鄰居更新器（{@code CascadeUpdater}）判斷記錄；
   * 該世界沒掛上更新器時，退回連同 6 個鄰居一起標記（設計 §3.7）。
   */
  public void markAround(Level level, int x, int y, int z, Author author) {
    var d = dimension(level);
    if (d == null) return;
    d.markCell(x, y, z, author);
    if (precise.contains(level)) return;
    d.markCell(x - 1, y, z, author);
    d.markCell(x + 1, y, z, author);
    d.markCell(x, y - 1, z, author);
    d.markCell(x, y + 1, z, author);
    d.markCell(x, y, z - 1, author);
    d.markCell(x, y, z + 1, author);
  }

  /** 世界已掛上鄰居更新器，連帶變化能精確判斷。 */
  public void precise(Level level, boolean value) {
    if (value) precise.add(level);
    else precise.remove(level);
  }

  public void markBox(Level level, dev.twme.buildgit.core.model.Box box, Author author) {
    var d = dimension(level);
    if (d != null) d.markBox(box, author);
  }

  public void markSection(Level level, int chunkX, int sectionY, int chunkZ, BitSet cells, Author author) {
    var d = dimension(level);
    if (d != null) d.markSection(chunkX, sectionY, chunkZ, cells, author);
  }

  /**
   * 遊戲改變了一格（鄰居更新、形狀更新）。有原因時直接記下；否則看它是否接在最近的玩家變更後面。
   *
   * @return 是否被記錄
   */
  public boolean cascade(MarkTable.Dimension d, Level level, int x, int y, int z) {
    var c = cause();
    if (c != null) {
      d.markCell(x, y, z, c);
      return true;
    }
    if (isSuppressed(level, x, y, z)) return false;
    return d.cascade(x, y, z, settings.cascadeWindow(), settings.maxDistance()) != null;
  }

  /** 這一格若稍後真的改變，該記給誰（目前的原因優先，其次是傳染）；不是連帶變化時為 null。 */
  public MarkTable.Probe probe(MarkTable.Dimension d, Level level, int x, int y, int z) {
    var c = cause();
    if (c != null) return new MarkTable.Probe(c, false, 0, 0, 0);
    if (isSuppressed(level, x, y, z)) return null;
    return d.probe(x, y, z, settings.cascadeWindow(), settings.maxDistance());
  }

  public void apply(MarkTable.Dimension d, int x, int y, int z, MarkTable.Probe probe) {
    if (probe.hasOrigin()) d.apply(x, y, z, probe);
    else d.markCell(x, y, z, probe.author());
  }

  /** 這一格附近剛被誰動過（目前的原因優先）；用於活塞、流體等以方塊為來源的變化。 */
  public Author recent(Level level, int x, int y, int z) {
    var c = cause();
    if (c != null) return c;
    var d = dimension(level);
    return d == null ? null : d.recentAuthor(x, y, z, settings.cascadeWindow());
  }

  // ---- 實體 ----

  private static String typeId(EntityType<?> type) {
    return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
  }

  public void markEntity(Entity entity, Author author) {
    var d = dimension(entity.level());
    if (d == null) return;
    d.markEntity(entity.getUUID(), author);
    if (!entity.isRemoved()) d.entityPresent(entity.getUUID());
  }

  /** 這個實體類型的本身狀態是否值得記錄（設計 §3.4）。 */
  public boolean isBuildEntity(EntityType<?> type) {
    return !settings.ignoredEntities().contains(typeId(type));
  }

  /** 同上，以類型 id 判斷；不認得的類型為 false。 */
  public boolean isBuildEntity(String id) {
    var key = net.minecraft.resources.Identifier.tryParse(id);
    return key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key) && !settings.ignoredEntities().contains(key.toString());
  }

  /** 被追蹤的實體的作者；沒被追蹤時為 null。 */
  public Author trackedAuthor(Entity entity) {
    var d = dimension(entity.level());
    return d == null ? null : d.entityAuthor(entity.getUUID());
  }

  /** 玩家動了這個實體：被追蹤的或建築用實體記下。 */
  public void touch(Entity entity, Author author) {
    if (author == null || !isTracked(entity.level())) return;
    if (isBuildEntity(entity.getType()) || trackedAuthor(entity) != null) markEntity(entity, author);
  }

  /**
   * 跨維度傳送：實體以同一個 UUID 加入新維度。若它在其他受追蹤維度被追蹤，舊維度記成移除、
   * 新維度以同一位作者繼續追蹤。
   */
  public void followMove(Entity entity) {
    if (levels.size() < 2) return;
    var here = dimension(entity.level());
    if (here == null) return;
    for (var d : levels.values()) {
      if (d == here || d.table() != here.table()) continue;
      var a = d.entityAuthor(entity.getUUID());
      if (a == null) continue;
      d.markEntityRemoved(entity.getUUID(), a);
      markEntity(entity, a);
    }
  }

  /** 實體生成時：目前有原因、或生成在剛被玩家動過的位置，就開始追蹤。 */
  public Author spawnAuthor(Entity entity) {
    var c = cause();
    if (c != null) return c;
    var d = dimension(entity.level());
    if (d == null) return null;
    var p = entity.blockPosition();
    int x = p.getX(), y = p.getY(), z = p.getZ();
    long window = settings.cascadeWindow();
    var a = d.recentAuthor(x, y, z, window);
    if (a == null) a = d.recentAuthor(x, y - 1, z, window);
    if (a != null) return a;
    var pending = pendingSpawns.get(chunkKey(x >> 4, z >> 4));
    if (pending != null && pending.level == entity.level() && pending.until >= now()) return pending.author;
    return null;
  }

  /** 批次寫入（FAWE）在這個 chunk 放了實體，實體稍後才會加入世界。 */
  public void expectSpawns(Level level, int chunkX, int chunkZ, Author author) {
    if (!isTracked(level)) return;
    pendingSpawns.put(chunkKey(chunkX, chunkZ), new PendingSpawn(level, author, now() + settings.cascadeWindow()));
  }

  private static long chunkKey(int x, int z) {
    return (long) x << 32 | (z & 0xFFFFFFFFL);
  }

  /** 會把原因帶到方塊上的實體（掉落方塊、TNT、投射物）→ 作者。不會進版本，只在記憶體中。 */
  private final Map<UUID, Author> carriers = new ConcurrentHashMap<>();
  /** 已移除、下一刻才忘掉的帶原因實體：TNT 先移除自己再爆炸，爆炸時還要查得到。 */
  private final List<UUID> removedCarriers = new ArrayList<>();

  public boolean isPropagating(EntityType<?> type) {
    return settings.propagatingEntities().contains(typeId(type));
  }

  /** 新實體：依類型開始追蹤本身狀態、或記住它帶著的原因。 */
  public void adopt(Entity entity, Author author) {
    if (isPropagating(entity.getType())) carriers.put(entity.getUUID(), author);
    if (isBuildEntity(entity.getType())) markEntity(entity, author);
  }

  /** 這個實體造成的方塊變化該記給誰；沒有時為 null。 */
  public Author carrier(Entity entity) {
    var a = carriers.get(entity.getUUID());
    if (a != null) return a;
    return isPropagating(entity.getType()) ? trackedAuthor(entity) : null;
  }

  public void forgetCarrier(UUID uuid) {
    carriers.remove(uuid);
  }

  /** 實體加入世界（載入、生成、換維度抵達）。 */
  public void entityLoaded(Entity entity) {
    var d = dimension(entity.level());
    if (d != null) d.entityPresent(entity.getUUID());
    // 跨維度傳送：新維度的實體以同一個 UUID 加入（先於舊的移除），沿用原作者繼續追蹤。
    followMove(entity);
  }

  /**
   * 實體離開世界。
   *
   * @param unloaded true 表示只是卸載（chunk 卸載、玩家離線、換維度），實體還在；false 表示死亡、被拆除、被撿起
   */
  public void entityRemoved(Entity entity, boolean unloaded) {
    var d = dimension(entity.level());
    if (d == null || unloaded) return; // 卸載的實體存檔時找不到，但沒有「已移除」記號，會留到下次存檔
    var author = d.entityAuthor(entity.getUUID());
    // 被追蹤的實體在下次存檔記成移除。
    // 沒被追蹤（例如已提交、這次還沒碰過）但正由玩家造成（/kill 等指令）時也記。
    var by = cause();
    if (author != null) d.markEntityRemoved(entity.getUUID(), by != null ? by : author);
    else if (by != null && isBuildEntity(entity.getType())) d.markEntityRemoved(entity.getUUID(), by);
    if (carriers.containsKey(entity.getUUID())) removedCarriers.add(entity.getUUID());
  }
}
