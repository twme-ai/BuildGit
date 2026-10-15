package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.paper.nms.OutlinePackets;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 只給某位玩家看的外框（差異、預覽、衝突）：發光的方塊展示實體，但只以封包送到那位玩家的客戶端，
 * 伺服器上沒有這些實體（不占實體數、不會被追蹤或存檔，也不受 chunk 是否載入影響）。數量太多時合併成 section 大小的包圍盒。
 */
final class Outlines implements Listener {
  static final Color ADDED = Color.fromRGB(0x55FF55);
  static final Color REMOVED = Color.fromRGB(0xFF5555);
  static final Color MODIFIED = Color.fromRGB(0xFFFF55);
  static final Color CONFLICT = Color.fromRGB(0xAA00FF);
  private static final int MAX = 400;

  record Shape(Box box, Color color) {}

  private final BuildGitPlugin plugin;
  /** 玩家目前看到的外框：所在世界與客戶端上的實體 id。 */
  private record Shown(UUID world, int[] ids) {}

  private final Map<UUID, Shown> shown = new HashMap<>();

  Outlines(BuildGitPlugin plugin) {
    this.plugin = plugin;
  }

  static Color color(Workspace.Kind kind) {
    return switch (kind) {
      case ADDED -> ADDED;
      case REMOVED -> REMOVED;
      case MODIFIED -> MODIFIED;
      case CONFLICT -> CONFLICT;
    };
  }

  /** 把格子變成外框；超過上限時依 section 與顏色合併成包圍盒。 */
  static List<Shape> shapes(Collection<Workspace.Changed> changed, String dimension) {
    var cells = changed.stream().filter(c -> c.dimension().equals(dimension)).toList();
    if (cells.size() <= MAX) return cells.stream().map(c -> new Shape(new Box(c.pos().x(), c.pos().y(), c.pos().z(), c.pos().x(), c.pos().y(), c.pos().z()), color(c.kind()))).toList();
    var groups = new LinkedHashMap<String, int[]>();
    var colors = new HashMap<String, Color>();
    for (var c : cells) {
      var p = c.pos();
      String key = (p.x() >> 4) + "," + (p.y() >> 4) + "," + (p.z() >> 4) + "," + c.kind();
      colors.putIfAbsent(key, color(c.kind()));
      var b = groups.computeIfAbsent(key, k -> new int[] {p.x(), p.y(), p.z(), p.x(), p.y(), p.z()});
      b[0] = Math.min(b[0], p.x());
      b[1] = Math.min(b[1], p.y());
      b[2] = Math.min(b[2], p.z());
      b[3] = Math.max(b[3], p.x());
      b[4] = Math.max(b[4], p.y());
      b[5] = Math.max(b[5], p.z());
    }
    var result = new ArrayList<Shape>();
    groups.forEach((k, b) -> result.add(new Shape(new Box(b[0], b[1], b[2], b[3], b[4], b[5]), colors.get(k))));
    return result.size() > MAX ? result.subList(0, MAX) : result;
  }

  static List<Shape> cells(Collection<BlockPos> cells, Color color) {
    var changed = cells.stream().map(p -> new Workspace.Changed("", p, Workspace.Kind.MODIFIED)).toList();
    return shapes(changed, "").stream().map(s -> new Shape(s.box(), color)).toList();
  }

  /** 顯示外框，{@code seconds} 秒後或玩家換世界時移除；之前的外框先清掉。 */
  void show(Player player, World world, List<Shape> shapes, int seconds) {
    clear(player);
    if (shapes.isEmpty() || !player.getWorld().equals(world)) return;
    var boxes = new ArrayList<OutlinePackets.Box>();
    for (var s : shapes.size() > MAX ? shapes.subList(0, MAX) : shapes) {
      var b = s.box();
      boxes.add(new OutlinePackets.Box(b.minX(), b.minY(), b.minZ(), b.maxX() - b.minX() + 1, b.maxY() - b.minY() + 1, b.maxZ() - b.minZ() + 1,
          s.color().asRGB()));
    }
    var entry = new Shown(world.getUID(), OutlinePackets.show(player, boxes));
    shown.put(player.getUniqueId(), entry);
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      if (shown.get(player.getUniqueId()) == entry) clear(player);
    }, seconds * 20L);
  }

  void clear(Player player) {
    var entry = shown.remove(player.getUniqueId());
    // 換了世界的話客戶端已經丟掉那些實體，而且 id 可能被新世界的實體用掉，不能再送移除
    if (entry != null && player.isOnline() && player.getWorld().getUID().equals(entry.world())) OutlinePackets.remove(player, entry.ids());
  }

  void clearAll() {
    for (var id : List.copyOf(shown.keySet())) {
      var p = Bukkit.getPlayer(id);
      if (p != null) clear(p);
    }
    shown.clear();
  }

  /** 玩家目前看得到的外框數（測試用）。 */
  int count(Player player) {
    var entry = shown.get(player.getUniqueId());
    return entry == null ? 0 : entry.ids().length;
  }

  @EventHandler
  public void onChangeWorld(PlayerChangedWorldEvent e) {
    shown.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    shown.remove(e.getPlayer().getUniqueId());
  }
}
