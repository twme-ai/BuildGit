package dev.twme.buildgit.core.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.model.SectionKey;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * 一個工作世界的標記：自上次提交後被玩家動過的格子與實體，以及最後動它的作者（設計 §3.6）。
 * 標記只會增加，提交時以 {@link #drain()} 一次取走。執行緒安全。
 *
 * <p>效能考量：遊戲執行緒每次鄰居更新都可能查詢這張表，FAWE 的工作執行緒則一次標記整個 section。
 * 所以每個維度用以 long 為鍵的雜湊表，section 只有一位作者時不配置逐格作者陣列。
 */
public final class MarkTable {
  private final LongSupplier clock;
  private final List<Author> authors = new ArrayList<>();
  private final Map<Author, Integer> authorIndex = new HashMap<>();
  private final Map<String, Dimension> dimensions = new HashMap<>();

  /** 連帶判斷的結果：作者與因果鏈的起點。 */
  public record Probe(Author author, boolean hasOrigin, int originX, int originZ, long since) {}
  /** 最後一次標記真的有變化的時刻；自動存檔以它判斷玩家是否停手。 */
  private volatile long lastChange = Long.MIN_VALUE;

  /** 以遊戲刻為單位的時鐘；連帶變化的時間窗以它計算。 */
  public MarkTable(LongSupplier clock) {
    this.clock = clock;
  }

  /** 預設時鐘：以 50 毫秒為一刻。 */
  public MarkTable() {
    this(() -> System.nanoTime() / 50_000_000L);
  }

  public long now() {
    return clock.getAsLong();
  }

  /** 最後一次有新標記的時刻；沒有標記過時為 {@link Long#MIN_VALUE}。 */
  public long lastChange() {
    return lastChange;
  }

  /** 取得維度的標記。平台應保存回傳值，避免熱路徑上的字串查詢。 */
  public synchronized Dimension dimension(String id) {
    return dimensions.computeIfAbsent(id, Dimension::new);
  }

  private short author(Author author) {
    var index = authorIndex.get(author);
    if (index != null) return index.shortValue();
    if (authors.size() >= Short.MAX_VALUE) throw new IllegalStateException("作者數量超過上限");
    authors.add(author);
    authorIndex.put(author, authors.size() - 1);
    return (short) (authors.size() - 1);
  }

  // ---- section 鍵 ----

  static long key(int chunkX, int sectionY, int chunkZ) {
    return ((long) chunkX & 0x3FFFFFFL) << 38 | ((long) chunkZ & 0x3FFFFFFL) << 12 | (sectionY & 0xFFFL);
  }

  static int keyX(long key) {
    return (int) (key >> 38);
  }

  static int keyZ(long key) {
    return (int) (key << 26 >> 38);
  }

  static int keyY(long key) {
    return (int) (key << 52 >> 52);
  }

  private static final int NO_ORIGIN = Integer.MIN_VALUE;

  private static final class SectionMarks {
    final BitSet cells = new BitSet(4096);
    short uniform = -1;
    short[] authors;
    long touched = Long.MIN_VALUE;
    int originX = NO_ORIGIN, originZ = NO_ORIGIN;
    boolean dirty;

    /** @return 是否為新標記或作者改變 */
    boolean set(int cell, short author) {
      boolean had = cells.get(cell);
      if (authors == null) {
        if (uniform == author) {
          if (had) return false;
          cells.set(cell);
          return true;
        }
        if (uniform < 0 || (had && cells.cardinality() == 1)) {
          uniform = author;
          cells.set(cell);
          return true;
        }
        authors = new short[4096];
        Arrays.fill(authors, uniform);
      }
      if (had && authors[cell] == author) return false;
      cells.set(cell);
      authors[cell] = author;
      return true;
    }

    short author(int cell) {
      return authors == null ? uniform : authors[cell];
    }

    void origin(int x, int z) {
      originX = x;
      originZ = z;
    }

    boolean hasOrigin() {
      return originX != NO_ORIGIN;
    }
  }

  /** 一個維度的標記。所有方法以外層表為鎖。 */
  public final class Dimension {
    private final String id;
    private LongMap<SectionMarks> sections = new LongMap<>();
    private Map<UUID, Short> entities = new HashMap<>();
    private final Set<UUID> dirtyEntities = new HashSet<>();
    /** 最近一次標記的時刻；不加鎖讀取，讓沒有人在蓋時熱路徑能直接返回。 */
    private volatile long lastTouch = Long.MIN_VALUE;
    private long lastKey = Long.MIN_VALUE;
    private SectionMarks lastSection;

    private Dimension(String id) {
      this.id = id;
    }

    public MarkTable table() {
      return MarkTable.this;
    }

    public String id() {
      return id;
    }

    private SectionMarks section(long key) {
      if (key == lastKey) return lastSection;
      var s = sections.computeIfAbsent(key, k -> new SectionMarks());
      lastKey = key;
      lastSection = s;
      return s;
    }

    private SectionMarks peek(int x, int y, int z) {
      long key = key(x >> 4, y >> 4, z >> 4);
      return key == lastKey ? lastSection : sections.get(key);
    }

    private boolean markLocked(int x, int y, int z, short author, long now) {
      var s = section(key(x >> 4, y >> 4, z >> 4));
      boolean changed = s.set(((y & 15) << 8) | ((z & 15) << 4) | (x & 15), author);
      s.touched = now;
      s.dirty |= changed;
      if (changed) lastChange = now;
      return changed;
    }

    /** 玩家直接造成的變更。這一格也成為連帶變化的新起點。 */
    public boolean markCell(int x, int y, int z, Author author) {
      synchronized (MarkTable.this) {
        long now = now();
        boolean changed = markLocked(x, y, z, MarkTable.this.author(author), now);
        lastSection.origin(x, z);
        lastTouch = now;
        return changed;
      }
    }

    public boolean markCell(BlockPos pos, Author author) {
      return markCell(pos.x(), pos.y(), pos.z(), author);
    }

    /** 一次標記一個 section 裡的多格（FAWE、Axiom 等批次寫入）。{@code cells} 的索引同 {@link BlockPos#sectionIndex()}。 */
    public void markSection(int chunkX, int sectionY, int chunkZ, BitSet cells, Author author) {
      if (cells.isEmpty()) return;
      synchronized (MarkTable.this) {
        long now = now();
        short a = MarkTable.this.author(author);
        var s = section(key(chunkX, sectionY, chunkZ));
        boolean changed = false;
        if (s.authors == null && (s.uniform < 0 || s.uniform == a)) {
          s.uniform = a;
          int before = s.cells.cardinality();
          s.cells.or(cells);
          changed = s.cells.cardinality() != before;
        } else {
          for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) changed |= s.set(i, a);
        }
        s.touched = now;
        s.dirty |= changed;
        if (changed) lastChange = now;
        s.origin((chunkX << 4) + 8, (chunkZ << 4) + 8);
        lastTouch = now;
      }
    }

    /** 標記整個範圍（指令、選取範圍補標記）。 */
    public void markBox(Box box, Author author) {
      var bits = new BitSet(4096);
      for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++)
        for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++)
          for (int sy = box.minY() >> 4; sy <= box.maxY() >> 4; sy++) {
            bits.clear();
            int x0 = Math.max(box.minX(), cx << 4), x1 = Math.min(box.maxX(), (cx << 4) + 15);
            int y0 = Math.max(box.minY(), sy << 4), y1 = Math.min(box.maxY(), (sy << 4) + 15);
            int z0 = Math.max(box.minZ(), cz << 4), z1 = Math.min(box.maxZ(), (cz << 4) + 15);
            for (int y = y0; y <= y1; y++)
              for (int z = z0; z <= z1; z++) {
                int base = ((y & 15) << 8) | ((z & 15) << 4);
                bits.set(base | (x0 & 15), (base | (x1 & 15)) + 1);
              }
            markSection(cx, sy, cz, bits, author);
          }
    }

    /**
     * 連帶變化：這一格或 6 個鄰居在 {@code window} 內被標記過、且離起點不超過 {@code maxDistance}，
     * 就把這一格記給同一位作者。
     *
     * @return 作者；不是連帶變化時為 null
     */
    public Author cascade(int x, int y, int z, long window, int maxDistance) {
      var p = probe(x, y, z, window, maxDistance);
      if (p == null) return null;
      apply(x, y, z, p);
      return p.author();
    }

    /**
     * 只判斷、不標記：這一格若現在改變，是否算連帶變化。用於「先記下候選、確認真的改變後再標記」，
     * 避免只是收到更新通知、內容沒變的格子也成為傳染源。
     */
    public Probe probe(int x, int y, int z, long window, int maxDistance) {
      long now = now();
      if (lastTouch < now - window) return null;
      synchronized (MarkTable.this) {
        long since = now - window;
        for (int d = 0; d < 7; d++) {
          int nx = x, ny = y, nz = z;
          switch (d) {
            case 1 -> nx--;
            case 2 -> nx++;
            case 3 -> ny--;
            case 4 -> ny++;
            case 5 -> nz--;
            case 6 -> nz++;
            default -> {}
          }
          var s = peek(nx, ny, nz);
          if (s == null || s.touched < since) continue;
          int cell = ((ny & 15) << 8) | ((nz & 15) << 4) | (nx & 15);
          if (!s.cells.get(cell)) continue;
          if (s.hasOrigin() && Math.max(Math.abs(x - s.originX), Math.abs(z - s.originZ)) > maxDistance) continue;
          return new Probe(authors.get(s.author(cell)), s.hasOrigin(), s.originX, s.originZ, since);
        }
        return null;
      }
    }

    /** 以 {@link #probe} 的結果標記：沿用來源的作者與起點，距離上限才會沿著因果鏈累計。 */
    public void apply(int x, int y, int z, Probe p) {
      synchronized (MarkTable.this) {
        long now = now();
        var target = section(key(x >> 4, y >> 4, z >> 4));
        // 目標 section 沒有起點、或它的起點已過時，就沿用來源的起點。
        if (p.hasOrigin() && (!target.hasOrigin() || target.touched < p.since())) target.origin(p.originX(), p.originZ());
        markLocked(x, y, z, author(p.author()), now);
        lastTouch = now;
      }
    }

    /** 這一格最近是否被標記過（實體生成位置等判斷用）。 */
    public Author recentAuthor(int x, int y, int z, long window) {
      long now = now();
      if (lastTouch < now - window) return null;
      synchronized (MarkTable.this) {
        var s = peek(x, y, z);
        int cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        if (s == null || s.touched < now - window || !s.cells.get(cell)) return null;
        return authors.get(s.author(cell));
      }
    }

    public boolean markEntity(UUID uuid, Author author) {
      synchronized (MarkTable.this) {
        short a = MarkTable.this.author(author);
        var previous = entities.put(uuid, a);
        if (previous != null && previous == a) return false;
        dirtyEntities.add(uuid);
        lastChange = now();
        return true;
      }
    }

    public boolean isMarked(int x, int y, int z) {
      synchronized (MarkTable.this) {
        var s = peek(x, y, z);
        return s != null && s.cells.get(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
      }
    }

    /** 尚未提交的標記作者；沒有標記時回傳 null。供檢查工具使用。 */
    public Author authorAt(int x, int y, int z) {
      synchronized (MarkTable.this) {
        var s = peek(x, y, z);
        int cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        return s == null || !s.cells.get(cell) ? null : authors.get(s.author(cell));
      }
    }

    public Author entityAuthor(UUID uuid) {
      synchronized (MarkTable.this) {
        var index = entities.get(uuid);
        return index == null ? null : authors.get(index);
      }
    }

    public boolean isTracked(UUID uuid) {
      synchronized (MarkTable.this) {
        return entities.containsKey(uuid);
      }
    }
  }

  // ---- 以維度名稱操作（測試與低頻呼叫） ----

  public boolean markCell(String dimension, BlockPos pos, Author author) {
    return dimension(dimension).markCell(pos, author);
  }

  public boolean markEntity(String dimension, UUID uuid, Author author) {
    return dimension(dimension).markEntity(uuid, author);
  }

  public boolean isMarked(String dimension, BlockPos pos) {
    return dimension(dimension).isMarked(pos.x(), pos.y(), pos.z());
  }

  public Author authorAt(String dimension, BlockPos pos) {
    return dimension(dimension).authorAt(pos.x(), pos.y(), pos.z());
  }

  public Author entityAuthor(String dimension, UUID uuid) {
    return dimension(dimension).entityAuthor(uuid);
  }

  // ---- 統計 ----

  public synchronized boolean isEmpty() {
    for (var d : dimensions.values()) if (!d.sections.isEmpty() || !d.entities.isEmpty()) return false;
    return true;
  }

  public synchronized int cellCount() {
    int[] n = {0};
    for (var d : dimensions.values()) d.sections.forEach((k, s) -> n[0] += s.cells.cardinality());
    return n[0];
  }

  public synchronized int entityCount() {
    int n = 0;
    for (var d : dimensions.values()) n += d.entities.size();
    return n;
  }

  /** 每位作者標記的格子數，供 status 與 bossbar 使用。 */
  public synchronized Map<Author, Integer> cellsByAuthor() {
    var counts = new int[authors.size()];
    for (var d : dimensions.values())
      d.sections.forEach((k, s) -> {
        if (s.authors == null) counts[s.uniform] += s.cells.cardinality();
        else for (int i = s.cells.nextSetBit(0); i >= 0; i = s.cells.nextSetBit(i + 1)) counts[s.authors[i]]++;
      });
    var result = new LinkedHashMap<Author, Integer>();
    for (int i = 0; i < counts.length; i++) if (counts[i] > 0) result.put(authors.get(i), counts[i]);
    return result;
  }

  /** 目前被標記的格子（複本，不取走），給顯示用。 */
  public synchronized SortedMap<SectionKey, BitSet> markedSections() {
    var result = new TreeMap<SectionKey, BitSet>();
    for (var d : dimensions.values())
      d.sections.forEach((k, s) -> {
        if (!s.cells.isEmpty()) result.put(new SectionKey(d.id, keyX(k), keyY(k), keyZ(k)), (BitSet) s.cells.clone());
      });
    return result;
  }

  // ---- 提交 ----

  /** 取走目前全部標記，換上空表。提交失敗時用 {@link #restore} 放回。 */
  public synchronized Marks drain() {
    var sectionSnapshot = new TreeMap<SectionKey, Marks.Section>();
    var entitySnapshot = new TreeMap<String, SortedMap<UUID, Integer>>();
    for (var d : dimensions.values()) {
      d.sections.forEach((k, s) -> {
        if (!s.cells.isEmpty())
          sectionSnapshot.put(new SectionKey(d.id, keyX(k), keyY(k), keyZ(k)), new Marks.Section(s.cells, s.authors, s.uniform));
      });
      if (!d.entities.isEmpty()) {
        var m = new TreeMap<UUID, Integer>();
        d.entities.forEach((u, a) -> m.put(u, (int) a));
        entitySnapshot.put(d.id, m);
      }
      d.sections = new LongMap<>();
      d.entities = new HashMap<>();
      d.dirtyEntities.clear();
      d.lastKey = Long.MIN_VALUE;
      d.lastSection = null;
    }
    return new Marks(authors, sectionSnapshot, entitySnapshot);
  }

  /** 把取走的標記放回。取走之後又被標記的格子，保留較新的作者。 */
  public synchronized void restore(Marks marks) {
    merge(marks, false);
  }

  /**
   * 合併標記。
   *
   * @param overwrite true 時以 {@code marks} 的作者覆蓋現有標記（重播日誌）；false 時保留現有標記（提交失敗放回）
   */
  synchronized void merge(Marks marks, boolean overwrite) {
    marks.sections().forEach((key, s) -> {
      var d = dimension(key.dimension());
      var target = d.section(key(key.chunkX(), key.sectionY(), key.chunkZ()));
      for (int i = s.cells().nextSetBit(0); i >= 0; i = s.cells().nextSetBit(i + 1)) {
        if (!overwrite && target.cells.get(i)) continue;
        target.set(i, author(marks.authors().get(s.authorIndex(i))));
      }
      target.dirty = true;
    });
    marks.entities().forEach((dim, m) -> {
      var d = dimension(dim);
      m.forEach((uuid, a) -> {
        if (overwrite || !d.entities.containsKey(uuid)) d.entities.put(uuid, author(marks.authors().get(a)));
        d.dirtyEntities.add(uuid);
      });
    });
  }

  /** 自上次呼叫後變動過的標記（完整的 section 內容），供日誌寫入。 */
  synchronized Marks takeDirty() {
    var sectionSnapshot = new TreeMap<SectionKey, Marks.Section>();
    var entitySnapshot = new TreeMap<String, SortedMap<UUID, Integer>>();
    for (var d : dimensions.values()) {
      d.sections.forEach((k, s) -> {
        if (!s.dirty) return;
        s.dirty = false;
        sectionSnapshot.put(new SectionKey(d.id, keyX(k), keyY(k), keyZ(k)),
            new Marks.Section((BitSet) s.cells.clone(), s.authors == null ? null : s.authors.clone(), s.uniform));
      });
      if (!d.dirtyEntities.isEmpty()) {
        var m = new TreeMap<UUID, Integer>();
        for (var u : d.dirtyEntities) {
          var a = d.entities.get(u);
          if (a != null) m.put(u, (int) a);
        }
        d.dirtyEntities.clear();
        if (!m.isEmpty()) entitySnapshot.put(d.id, m);
      }
    }
    return new Marks(authors, sectionSnapshot, entitySnapshot);
  }

  /** 讓所有標記在下一次 {@link #takeDirty()} 重新寫出（日誌輪替後使用）。 */
  synchronized void markAllDirty() {
    for (var d : dimensions.values()) {
      d.sections.forEach((k, s) -> s.dirty = !s.cells.isEmpty());
      d.dirtyEntities.addAll(d.entities.keySet());
    }
  }
}
