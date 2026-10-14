package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 只給某位玩家看的外框（差異、預覽、衝突）。以發光的方塊展示實體顯示：只對該玩家可見、不存檔，
 * 並在系統寫入期間建立，所以不會被追蹤。數量太多時合併成 section 大小的包圍盒。
 */
final class Outlines implements Listener {
  static final Color ADDED = Color.fromRGB(0x55FF55);
  static final Color REMOVED = Color.fromRGB(0xFF5555);
  static final Color MODIFIED = Color.fromRGB(0xFFFF55);
  static final Color CONFLICT = Color.fromRGB(0xAA00FF);
  private static final int MAX = 400;

  record Shape(Box box, Color color) {}

  private final BuildGitPlugin plugin;
  private final Map<UUID, List<Entity>> shown = new HashMap<>();

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
    var list = new ArrayList<Entity>();
    plugin.tracker().systemWrite(() -> {
      for (var s : shapes.size() > MAX ? shapes.subList(0, MAX) : shapes) {
        var b = s.box();
        var loc = new Location(world, b.minX(), b.minY(), b.minZ());
        if (!world.isChunkLoaded(b.minX() >> 4, b.minZ() >> 4)) continue;
        float sx = b.maxX() - b.minX() + 1, sy = b.maxY() - b.minY() + 1, sz = b.maxZ() - b.minZ() + 1;
        var display = world.spawn(loc, BlockDisplay.class, d -> {
          d.setVisibleByDefault(false);
          d.setPersistent(false);
          d.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
          d.setTransformation(new Transformation(new Vector3f(-0.01f), new AxisAngle4f(), new Vector3f(sx + 0.02f, sy + 0.02f, sz + 0.02f),
              new AxisAngle4f()));
          d.setGlowing(true);
          d.setGlowColorOverride(s.color());
          d.setBrightness(new Display.Brightness(15, 15));
          d.setViewRange(4f);
        });
        player.showEntity(plugin, display);
        list.add(display);
      }
    });
    shown.put(player.getUniqueId(), list);
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      if (shown.get(player.getUniqueId()) == list) clear(player);
    }, seconds * 20L);
  }

  void clear(Player player) {
    var list = shown.remove(player.getUniqueId());
    if (list != null) plugin.tracker().systemWrite(() -> list.forEach(Entity::remove));
  }

  void clearAll() {
    for (var id : List.copyOf(shown.keySet())) {
      var p = Bukkit.getPlayer(id);
      if (p != null) clear(p);
    }
    shown.clear();
  }

  @EventHandler
  public void onChangeWorld(PlayerChangedWorldEvent e) {
    clear(e.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    clear(e.getPlayer());
  }
}
