package dev.twme.buildgit.server;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.server.nms.OutlinePackets;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 只給某位玩家看的外框（差異、預覽、衝突）：發光的方塊展示實體，但只以封包送到那位玩家的客戶端，
 * 伺服器上沒有這些實體（不占實體數、不會被追蹤或存檔，也不受 chunk 是否載入影響）。數量太多時合併成 section 大小的包圍盒。
 */
public final class Outlines {
  static final int ADDED = 0x55FF55;
  static final int REMOVED = 0xFF5555;
  static final int MODIFIED = 0xFFFF55;
  static final int CONFLICT = 0xAA00FF;
  static final int BIOME = 0x55FFFF;
  private static final int MAX = 400;

  record Shape(Box box, int color) {}

  /** 改動的一區：相連（含斜角）的 chunk 算一區；{@code box} 是區裡改動格的範圍。 */
  record Area(String dimension, Box box, int chunks, int cells) {}

  private final Platform platform;
  /** 玩家目前看到的外框：所在世界與客戶端上的實體 id。 */
  private record Shown(ServerLevel world, int[] ids) {}

  private final Map<UUID, Shown> shown = new HashMap<>();

  Outlines(Platform platform) {
    this.platform = platform;
  }

  static int color(Workspace.Kind kind) {
    return switch (kind) {
      case ADDED -> ADDED;
      case REMOVED -> REMOVED;
      case MODIFIED -> MODIFIED;
      case CONFLICT, BIOME_CONFLICT -> CONFLICT;
      case BIOME -> BIOME;
    };
  }

  /** 外框往正方向多涵蓋幾格：生態域是 4×4×4 的格子。 */
  private static int extent(Workspace.Kind kind) {
    return kind == Workspace.Kind.BIOME || kind == Workspace.Kind.BIOME_CONFLICT ? 3 : 0;
  }

  /** 把格子變成外框；超過上限時依 section 與顏色合併成包圍盒。 */
  static List<Shape> shapes(Collection<Workspace.Changed> changed, String dimension) {
    var cells = changed.stream().filter(c -> c.dimension().equals(dimension)).toList();
    if (cells.size() <= MAX)
      return cells.stream().map(c -> {
        var p = c.pos();
        int e = extent(c.kind());
        return new Shape(new Box(p.x(), p.y(), p.z(), p.x() + e, p.y() + e, p.z() + e), color(c.kind()));
      }).toList();
    var groups = new LinkedHashMap<String, int[]>();
    var colors = new HashMap<String, Integer>();
    for (var c : cells) {
      var p = c.pos();
      int e = extent(c.kind());
      String key = (p.x() >> 4) + "," + (p.y() >> 4) + "," + (p.z() >> 4) + "," + c.kind();
      colors.putIfAbsent(key, color(c.kind()));
      var b = groups.computeIfAbsent(key, k -> new int[] {p.x(), p.y(), p.z(), p.x(), p.y(), p.z()});
      b[0] = Math.min(b[0], p.x());
      b[1] = Math.min(b[1], p.y());
      b[2] = Math.min(b[2], p.z());
      b[3] = Math.max(b[3], p.x() + e);
      b[4] = Math.max(b[4], p.y() + e);
      b[5] = Math.max(b[5], p.z() + e);
    }
    var result = new ArrayList<Shape>();
    groups.forEach((k, b) -> result.add(new Shape(new Box(b[0], b[1], b[2], b[3], b[4], b[5]), colors.get(k))));
    return result.size() > MAX ? result.subList(0, MAX) : result;
  }

  /** 把改動格依 chunk 分區（相連、含斜角的 chunk 算一區），改得多的在前。遠處的小改動也會自成一區，不會被漏看。 */
  static List<Area> areas(Collection<Workspace.Changed> changed) {
    var byDim = new HashMap<String, Map<Long, int[]>>(); // chunk → 改動格的範圍與格數
    for (var c : changed) {
      var p = c.pos();
      long key = (long) (p.x() >> 4) << 32 | (p.z() >> 4) & 0xffffffffL;
      var b = byDim.computeIfAbsent(c.dimension(), k -> new HashMap<>()).computeIfAbsent(key, k -> new int[] {p.x(), p.y(), p.z(), p.x(), p.y(), p.z(), 0});
      b[0] = Math.min(b[0], p.x());
      b[1] = Math.min(b[1], p.y());
      b[2] = Math.min(b[2], p.z());
      b[3] = Math.max(b[3], p.x());
      b[4] = Math.max(b[4], p.y());
      b[5] = Math.max(b[5], p.z());
      b[6]++;
    }
    var result = new ArrayList<Area>();
    byDim.forEach((dim, chunks) -> {
      var left = new HashSet<>(chunks.keySet());
      while (!left.isEmpty()) {
        var first = left.iterator().next();
        left.remove(first);
        var queue = new ArrayDeque<Long>(List.of(first));
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        int count = 0, cells = 0;
        while (!queue.isEmpty()) {
          long k = queue.poll();
          var b = chunks.get(k);
          count++;
          cells += b[6];
          for (int i = 0; i < 3; i++) {
            box[i] = Math.min(box[i], b[i]);
            box[i + 3] = Math.max(box[i + 3], b[i + 3]);
          }
          int cx = (int) (k >> 32), cz = (int) k;
          for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
              long q = (long) (cx + dx) << 32 | (cz + dz) & 0xffffffffL;
              if (left.remove(q)) queue.add(q);
            }
        }
        result.add(new Area(dim, new Box(box[0], box[1], box[2], box[3], box[4], box[5]), count, cells));
      }
    });
    result.sort(Comparator.comparingInt(Area::cells).reversed());
    return result;
  }

  static List<Shape> cells(Collection<BlockPos> cells, int color) {
    var changed = cells.stream().map(p -> new Workspace.Changed("", p, Workspace.Kind.MODIFIED)).toList();
    return shapes(changed, "").stream().map(s -> new Shape(s.box(), color)).toList();
  }

  /** 顯示外框，{@code seconds} 秒後或玩家換世界時移除；之前的外框先清掉。 */
  void show(ServerPlayer player, ServerLevel world, List<Shape> shapes, int seconds) {
    clear(player);
    if (shapes.isEmpty() || player.level() != world) return;
    var boxes = new ArrayList<OutlinePackets.Box>();
    for (var s : shapes.size() > MAX ? shapes.subList(0, MAX) : shapes) {
      var b = s.box();
      boxes.add(new OutlinePackets.Box(b.minX(), b.minY(), b.minZ(), b.maxX() - b.minX() + 1, b.maxY() - b.minY() + 1, b.maxZ() - b.minZ() + 1,
          s.color()));
    }
    var entry = new Shown(world, OutlinePackets.show(player, boxes));
    var id = player.getUUID();
    shown.put(id, entry);
    platform.later(seconds * 20L, () -> {
      if (shown.get(id) != entry) return;
      var p = platform.server().getPlayerList().getPlayer(id);
      if (p != null) clear(p);
      else shown.remove(id);
    });
  }

  void clear(ServerPlayer player) {
    var entry = shown.remove(player.getUUID());
    // 換了世界的話客戶端已經丟掉那些實體，而且 id 可能被新世界的實體用掉，不能再送移除
    if (entry != null && !player.hasDisconnected() && player.level() == entry.world()) OutlinePackets.remove(player, entry.ids());
  }

  void clearAll() {
    for (var id : List.copyOf(shown.keySet())) {
      var p = platform.server().getPlayerList().getPlayer(id);
      if (p != null) clear(p);
    }
    shown.clear();
  }

  /** 玩家目前看得到的外框數（測試用）。 */
  public int count(UUID player) {
    var entry = shown.get(player);
    return entry == null ? 0 : entry.ids().length;
  }

  /** 玩家換了世界或離線：客戶端已經丟掉外框。 */
  void forget(UUID player) {
    shown.remove(player);
  }
}
