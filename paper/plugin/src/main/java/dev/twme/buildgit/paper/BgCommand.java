package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.repo.Changeset;
import dev.twme.buildgit.core.repo.Commit;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.paper.track.Tracker;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** {@code /bg} 指令（設計 §6、§7）。目前提供主世界的存檔、歷史與檢查。 */
final class BgCommand implements TabExecutor, Listener {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private static final int MAX_MARK = 1 << 22;

  private final BuildGitPlugin plugin;
  private final Set<UUID> inspecting = new HashSet<>();

  BgCommand(BuildGitPlugin plugin) {
    this.plugin = plugin;
  }

  private Messages msg() {
    return plugin.messages();
  }

  private Workspace ws() {
    return plugin.workspace();
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      msg().send(sender, "help");
      return true;
    }
    var sub = args[0].toLowerCase(Locale.ROOT);
    var rest = Arrays.copyOfRange(args, 1, args.length);
    String permission =
        switch (sub) {
          case "init", "reload" -> "buildgit.admin";
          case "save" -> "buildgit.save";
          case "inspect", "i" -> "buildgit.inspect";
          case "mark" -> "buildgit.mark";
          default -> "buildgit.view";
        };
    if (!sender.hasPermission(permission)) {
      msg().send(sender, "no-permission");
      return true;
    }
    if (!sub.equals("init") && !sub.equals("reload") && !ws().isOpen()) {
      msg().send(sender, "not-initialized");
      return true;
    }
    try {
      switch (sub) {
        case "init" -> init(sender);
        case "reload" -> {
          plugin.reload();
          msg().send(sender, "reloaded");
        }
        case "status" -> status(sender);
        case "save" -> save(sender, String.join(" ", rest));
        case "log" -> log(sender, rest.length > 0 ? parseInt(rest[0], 10) : 10);
        case "show" -> show(sender, rest);
        case "inspect", "i" -> toggleInspect(sender);
        case "blame" -> blame(sender);
        case "mark" -> mark(sender, rest);
        default -> msg().send(sender, "help");
      }
    } catch (IOException e) {
      plugin.getLogger().log(Level.WARNING, "/bg " + sub + " 失敗", e);
      sender.sendMessage(e.toString());
    }
    return true;
  }

  private static int parseInt(String s, int fallback) {
    try {
      return Math.max(1, Math.min(100, Integer.parseInt(s)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private void init(CommandSender sender) {
    if (ws().isOpen()) {
      msg().send(sender, "already-initialized");
      return;
    }
    msg().send(sender, "init-start");
    try {
      long size = ws().init(plugin.tracker().author(sender instanceof Player p ? p : Bukkit.getOfflinePlayer(new UUID(0, 0))));
      msg().send(sender, "init-done", "size", size / (1024 * 1024));
    } catch (IOException e) {
      plugin.getLogger().log(Level.SEVERE, "init 失敗", e);
      msg().send(sender, "init-failed", "error", e.getMessage());
    }
  }

  private void status(CommandSender sender) throws IOException {
    var repo = ws().repo();
    msg().send(sender, "status-header", "head", repo.head(Repository.MAIN).abbrev());
    var table = ws().table();
    if (table.isEmpty()) msg().send(sender, "status-clean");
    else {
      msg().send(sender, "status-pending", "cells", table.cellCount(), "entities", table.entityCount());
      table.cellsByAuthor().forEach((a, n) -> msg().send(sender, "status-author", "author", a.name(), "cells", n));
    }
    if (ws().uncleanShutdown()) msg().send(sender, "status-unclean");
  }

  private void save(CommandSender sender, String message) {
    var author = sender instanceof Player p ? plugin.tracker().author(p) : dev.twme.buildgit.core.model.Author.SYSTEM;
    msg().send(sender, "save-start");
    boolean started =
        ws().save(author, message.isBlank() ? "save" : message, 0,
            r -> {
              if (r.commit().isEmpty()) msg().send(sender, "save-nothing");
              else msg().send(sender, "save-done", "id", r.commit().get().abbrev(), "cells", r.cells(), "entities", r.entities());
            },
            e -> {
              plugin.getLogger().log(Level.WARNING, "存檔失敗", e);
              msg().send(sender, "save-failed", "error", String.valueOf(e.getMessage()));
            });
    if (!started) msg().send(sender, "save-busy");
  }

  private void log(CommandSender sender, int limit) throws IOException {
    var repo = ws().repo();
    var ids = repo.history(repo.head(Repository.MAIN), limit);
    if (ids.isEmpty()) msg().send(sender, "log-empty");
    for (var id : ids) {
      var c = repo.commit(id);
      if (c.parents().isEmpty()) continue; // 初始化的空提交
      msg().send(sender, "log-entry", "id", id.abbrev(), "time", TIME.format(Instant.ofEpochMilli(c.time())), "author", c.author().name(),
          "message", c.auto() ? msg().get("log-auto") : net.kyori.adventure.text.Component.text(c.message()));
    }
  }

  private ObjectId resolve(String prefix) throws IOException {
    var repo = ws().repo();
    for (var id : repo.history(repo.head(Repository.MAIN), 10_000)) if (id.hex().startsWith(prefix.toLowerCase(Locale.ROOT))) return id;
    return null;
  }

  private void show(CommandSender sender, String[] args) throws IOException {
    var repo = ws().repo();
    var id = args.length == 0 ? repo.head(Repository.MAIN) : resolve(args[0]);
    if (id == null) {
      msg().send(sender, "unknown-commit", "id", args[0]);
      return;
    }
    Commit c = repo.commit(id);
    msg().send(sender, "show-header", "id", id.abbrev(), "author", c.author().name(), "time", TIME.format(Instant.ofEpochMilli(c.time())),
        "message", c.message());
    var cs = repo.changeset(c.changeset());
    cs.cellsByAuthor().forEach((a, n) -> msg().send(sender, "show-line", "author", a.name(), "cells", n));
    if (!cs.entities().isEmpty()) msg().send(sender, "show-entities", "entities", cs.entities().size());
  }

  private void mark(CommandSender sender, String[] args) {
    if (!(sender instanceof Player p)) {
      msg().send(sender, "players-only");
      return;
    }
    if (args.length != 6) {
      msg().send(sender, "usage-mark");
      return;
    }
    int[] v = new int[6];
    try {
      for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(args[i]);
    } catch (NumberFormatException e) {
      msg().send(sender, "usage-mark");
      return;
    }
    var box = new Box(Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]), Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5]));
    long volume = (long) (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1);
    if (volume > MAX_MARK) {
      msg().send(sender, "mark-too-large", "max", MAX_MARK);
      return;
    }
    if (!plugin.tracker().isTracked(p.getWorld())) {
      msg().send(sender, "inspect-untracked");
      return;
    }
    plugin.tracker().markBox(p.getWorld(), box, plugin.tracker().author(p));
    msg().send(sender, "mark-done", "cells", volume);
  }

  // ---- 檢查 ----

  private void toggleInspect(CommandSender sender) {
    if (!(sender instanceof Player p)) {
      msg().send(sender, "players-only");
      return;
    }
    if (inspecting.remove(p.getUniqueId())) msg().send(p, "inspect-off");
    else {
      inspecting.add(p.getUniqueId());
      msg().send(p, "inspect-on");
    }
  }

  private void blame(CommandSender sender) {
    if (!(sender instanceof Player p)) {
      msg().send(sender, "players-only");
      return;
    }
    var block = p.getTargetBlockExact(10);
    if (block == null) msg().send(p, "blame-no-target");
    else describe(p, block);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onInspect(PlayerInteractEvent e) {
    if (!inspecting.contains(e.getPlayer().getUniqueId()) || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
    if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    e.setCancelled(true);
    // 右鍵看放置面那一格（例如剛放下的方塊旁邊），左鍵看點到的那一格。
    describe(e.getPlayer(), e.getAction() == Action.RIGHT_CLICK_BLOCK ? e.getClickedBlock().getRelative(e.getBlockFace()) : e.getClickedBlock());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    inspecting.remove(e.getPlayer().getUniqueId());
  }

  private void describe(Player player, Block block) {
    var tracker = plugin.tracker();
    var d = tracker.dimension(block.getWorld());
    String pos = block.getX() + " " + block.getY() + " " + block.getZ();
    if (d == null) {
      msg().send(player, "inspect-untracked");
      return;
    }
    var pending = d.authorAt(block.getX(), block.getY(), block.getZ());
    if (pending != null) {
      msg().send(player, "inspect-pending", "pos", pos, "author", pending.name());
      return;
    }
    var repo = ws().repo();
    var dim = Tracker.dimensionId(block.getWorld());
    var at = new BlockPos(block.getX(), block.getY(), block.getZ());
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      try {
        var blame = repo.blame(repo.head(Repository.MAIN), dim, at);
        Bukkit.getScheduler().runTask(plugin, () -> {
          if (blame.isEmpty()) {
            msg().send(player, "inspect-none", "pos", pos);
            return;
          }
          var b = blame.get();
          String time;
          try {
            time = TIME.format(Instant.ofEpochMilli(repo.commit(b.commit()).time()));
          } catch (IOException ex) {
            time = "?";
          }
          msg().send(player, "inspect-blame", "pos", pos, "author", b.author().name(), "id", b.commit().abbrev(), "time", time,
              "kind", msg().get(kind(b.kind())));
        });
      } catch (IOException ex) {
        plugin.getLogger().log(Level.WARNING, "blame 失敗", ex);
      }
    });
  }

  private static String kind(Changeset.Kind kind) {
    return switch (kind.name()) {
      case "ADDED" -> "kind-added";
      case "REMOVED" -> "kind-removed";
      default -> "kind-modified";
    };
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      var all = List.of("status", "save", "log", "show", "inspect", "blame", "mark", "init", "reload");
      return all.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
    return List.of();
  }
}
