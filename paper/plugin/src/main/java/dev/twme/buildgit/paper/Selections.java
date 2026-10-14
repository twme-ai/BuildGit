package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.paper.worldedit.WorldEditSelection;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家的選取範圍（設計 §6.4）：依序取自 WorldEdit／FAWE 的選取，再來是 {@code /bg sel pos1|pos2}。
 * Axiom 的選取只存在客戶端，伺服器拿不到。
 */
final class Selections implements Listener {
  record Selection(World world, Box box) {}

  private record Corner(UUID world, BlockPos pos) {}

  private final BuildGitPlugin plugin;
  private final Map<UUID, Corner[]> corners = new HashMap<>();
  private boolean worldEditBroken;

  Selections(BuildGitPlugin plugin) {
    this.plugin = plugin;
  }

  void set(Player player, int index, BlockPos pos) {
    corners.computeIfAbsent(player.getUniqueId(), k -> new Corner[2])[index] = new Corner(player.getWorld().getUID(), pos);
  }

  void clear(Player player) {
    corners.remove(player.getUniqueId());
  }

  Optional<Selection> get(Player player) {
    var pm = Bukkit.getPluginManager();
    if (!worldEditBroken && (pm.isPluginEnabled("WorldEdit") || pm.isPluginEnabled("FastAsyncWorldEdit"))) {
      try {
        var we = WorldEditSelection.of(player);
        if (we != null) {
          var world = Bukkit.getWorld(we.world());
          if (world != null) return Optional.of(new Selection(world, we.box()));
        }
      } catch (LinkageError | RuntimeException e) {
        worldEditBroken = true;
        plugin.getLogger().log(Level.WARNING, "無法讀取 WorldEdit 選取範圍，改用 /bg sel", e);
      }
    }
    var c = corners.get(player.getUniqueId());
    if (c == null || c[0] == null || c[1] == null || !c[0].world().equals(c[1].world())) return Optional.empty();
    var world = Bukkit.getWorld(c[0].world());
    return world == null ? Optional.empty() : Optional.of(new Selection(world, Box.of(c[0].pos(), c[1].pos())));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    corners.remove(e.getPlayer().getUniqueId());
  }
}
