package dev.twme.buildgit.paper;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.BlockPos;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.overlay.TreeDiff;
import dev.twme.buildgit.core.repo.Changeset;
import dev.twme.buildgit.core.repo.Commit;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * {@code /bg} 指令（設計 §6、§7），以 Paper 的 Brigadier 指令 API 註冊：子指令依權限顯示，參數有補全。
 *
 * <p>建築師日常用的三步流程（try／publish／back）與底層的分支、合併、還原都在這裡。
 */
final class BgCommand implements Listener {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private static final int MAX_MARK = 1 << 22;
  private static final int SHOW_CELLS = 200;
  private static final int OUTLINE_SECONDS = 60;
  private static final int HERE_RADIUS = 8;

  private interface Handler {
    void run(CommandSender sender, String[] args) throws IOException;
  }

  private interface Suggester {
    List<String> suggest(CommandSender sender, List<String> before);
  }

  private record Sub(List<String> names, String permission, Handler handler, Suggester suggester) {}

  private final BuildGitPlugin plugin;
  private final Set<UUID> inspecting = new HashSet<>();
  /** 進入檢視世界之前的位置，{@code back} 回到這裡。 */
  private final Map<UUID, Location> returnTo = new HashMap<>();
  private final List<Sub> subs = new ArrayList<>();

  BgCommand(BuildGitPlugin plugin) {
    this.plugin = plugin;
    sub("help", null, (s, a) -> msg().send(s, "help"), null);
    sub("init", "buildgit.admin", (s, a) -> init(s), null);
    sub("reload", "buildgit.admin", (s, a) -> {
      plugin.reload();
      msg().send(s, "reloaded");
    }, null);
    sub("status", "buildgit.view", (s, a) -> status(s), null);
    sub("save", "buildgit.save", (s, a) -> save(s, String.join(" ", a)), null);
    sub("commit", "buildgit.commit", (s, a) -> save(s, String.join(" ", a)), null);
    sub("log", "buildgit.view", this::log, (s, b) -> concat(List.of("--all", "--author"), draftNames()));
    sub("show", "buildgit.view", this::show, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of("--cells"));
    sub("diff", "buildgit.view", this::diff, (s, b) -> b.size() < 2 ? recentCommits(s) : List.of());
    sub(List.of("inspect", "i"), "buildgit.inspect", (s, a) -> toggleInspect(s), null);
    sub("blame", "buildgit.view", (s, a) -> blame(s), null);
    sub("mark", "buildgit.mark", this::mark, null);
    sub("sel", "buildgit.view", this::sel, (s, b) -> b.isEmpty() ? List.of("pos1", "pos2", "show", "clear") : List.of());
    sub("who", "buildgit.view", (s, a) -> who(s), null);
    sub("try", "buildgit.try", this::tryDraft, (s, b) -> b.isEmpty() ? draftNames() : List.of());
    sub("go", "buildgit.go", this::go, (s, b) -> b.isEmpty() ? draftNames() : List.of());
    sub("back", "buildgit.go", (s, a) -> back(s), null);
    sub("view", "buildgit.go", this::view, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of());
    sub("publish", "buildgit.publish", this::publish, (s, b) -> concat(List.of("--selection", "confirm"), s instanceof Player ? List.of() : draftNames()));
    sub("branch", "buildgit.view", this::branch, (s, b) -> b.isEmpty() ? List.of("list", "create", "delete")
        : b.size() == 1 && b.getFirst().equals("delete") ? draftNames()
        : b.size() == 2 && b.getFirst().equals("create") ? draftNames()
        : b.size() == 2 && b.getFirst().equals("delete") ? List.of("--force") : List.of());
    sub("merge", null, this::merge, (s, b) -> b.isEmpty() ? concat(List.of("view", "conflicts", "goto", "take", "continue", "abort", "list"), draftNames())
        : b.size() == 1 && b.getFirst().equals("view") ? List.of("ours", "theirs", "result")
        : b.size() == 1 && b.getFirst().equals("take") ? List.of("ours", "theirs")
        : b.size() == 2 && b.getFirst().equals("take") ? List.of("here", "selection", "all") : List.of());
    sub("revert", "buildgit.revert", this::revert, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of());
    sub("restore", "buildgit.restore", this::restore, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of("--selection", "confirm"));
  }

  private void sub(String name, String permission, Handler handler, Suggester suggester) {
    sub(List.of(name), permission, handler, suggester);
  }

  private void sub(List<String> names, String permission, Handler handler, Suggester suggester) {
    subs.add(new Sub(names, permission, handler, suggester));
  }

  private static List<String> concat(List<String> a, List<String> b) {
    var r = new ArrayList<>(a);
    r.addAll(b);
    return r;
  }

  private Messages msg() {
    return plugin.messages();
  }

  private Workspace ws() {
    return plugin.workspace();
  }

  // ---- Brigadier ----

  LiteralCommandNode<CommandSourceStack> build() {
    var root = Commands.literal("bg").executes(ctx -> {
      var sender = ctx.getSource().getSender();
      if (sender instanceof Player p && p.hasPermission("buildgit.view") && ws().isOpen()) plugin.menu().open(p);
      else msg().send(sender, "help");
      return 1;
    });
    for (var sub : subs)
      for (var name : sub.names()) {
        var literal = Commands.literal(name)
            .requires(src -> sub.permission() == null || src.getSender().hasPermission(sub.permission()))
            .executes(ctx -> dispatch(sub, ctx.getSource().getSender(), new String[0]));
        literal.then(Commands.argument("args", StringArgumentType.greedyString())
            .suggests((ctx, builder) -> suggest(sub, ctx, builder))
            .executes(ctx -> dispatch(sub, ctx.getSource().getSender(), StringArgumentType.getString(ctx, "args").trim().split(" +"))));
        root.then(literal);
      }
    return root.build();
  }

  private CompletableFuture<Suggestions> suggest(Sub sub, CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    if (sub.suggester() == null || !ws().isOpen()) return builder.buildFuture();
    var remaining = builder.getRemaining();
    int space = remaining.lastIndexOf(' ');
    var before = space < 0 ? List.<String>of() : Arrays.asList(remaining.substring(0, space).trim().split(" +"));
    var current = remaining.substring(space + 1).toLowerCase(Locale.ROOT);
    var b = builder.createOffset(builder.getStart() + space + 1);
    try {
      for (var option : sub.suggester().suggest(ctx.getSource().getSender(), before))
        if (option.toLowerCase(Locale.ROOT).startsWith(current)) b.suggest(option);
    } catch (RuntimeException e) {
      // 補全失敗不影響指令
    }
    return b.buildFuture();
  }

  private int dispatch(Sub sub, CommandSender sender, String[] args) {
    String name = sub.names().getFirst();
    if (!name.equals("init") && !name.equals("reload") && !name.equals("help") && !ws().isOpen()) {
      msg().send(sender, "not-initialized");
      return 1;
    }
    try {
      sub.handler().run(sender, args);
    } catch (IOException | RuntimeException e) {
      plugin.getLogger().log(Level.WARNING, "/bg " + name + " 失敗", e);
      failed(sender, e);
    }
    return 1;
  }

  private void failed(CommandSender sender, Throwable e) {
    msg().send(sender, "branch-failed", "error", String.valueOf(e.getMessage()));
  }

  private List<String> draftNames() {
    try {
      return List.copyOf(ws().drafts().keySet());
    } catch (IOException e) {
      return List.of();
    }
  }

  private List<String> recentCommits(CommandSender sender) {
    try {
      var repo = ws().repo();
      var result = new ArrayList<String>();
      for (var id : repo.history(repo.head(current(sender).branch()), 20)) result.add(id.abbrev());
      return result;
    } catch (IOException e) {
      return List.of();
    }
  }

  // ---- 共用 ----

  private static int parseInt(String s, int fallback) {
    try {
      return Math.max(1, Math.min(100, Integer.parseInt(s)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** 指令作用的分支：玩家所在世界的分支；主控台、檢視世界或不受控的世界為主分支。 */
  private Worktree current(CommandSender sender) {
    var wt = sender instanceof Player p ? ws().worktree(p.getWorld()) : null;
    return wt != null ? wt : ws().main();
  }

  private Component branchLabel(String branch) {
    if (branch.equals(Repository.MAIN)) return msg().get("branch-main");
    if (Workspace.isScratch(branch)) return msg().get("branch-merge", "name", branch);
    return msg().get("branch-draft", "name", branch);
  }

  private Author author(CommandSender sender) {
    return sender instanceof Player p ? plugin.tracker().author(p) : Author.SYSTEM;
  }

  private static Set<String> flags(String[] args) {
    var set = new HashSet<String>();
    for (var a : args) if (a.startsWith("--") || a.equals("confirm")) set.add(a.toLowerCase(Locale.ROOT));
    return set;
  }

  private static List<String> words(String[] args) {
    var list = new ArrayList<String>();
    for (var a : args) if (!a.isEmpty() && !a.startsWith("--") && !a.equals("confirm")) list.add(a);
    return list;
  }

  private Player player(CommandSender sender) {
    if (sender instanceof Player p) return p;
    msg().send(sender, "players-only");
    return null;
  }

  /** 存檔或分支名稱（分支表示它的最新存檔）。 */
  private ObjectId resolve(String ref) throws IOException {
    var repo = ws().repo();
    var branch = repo.refs().get(ref);
    if (branch.isPresent()) return branch.get();
    var prefix = ref.toLowerCase(Locale.ROOT);
    for (var head : repo.refs().list().values())
      for (var id : repo.history(head, 10_000)) if (id.hex().startsWith(prefix)) return id;
    return null;
  }

  /** 有未存的變更就先自動存檔，存好（或本來就沒有）再繼續。 */
  private void autosaveThen(CommandSender sender, Worktree wt, Runnable next) {
    if (wt.table().isEmpty()) {
      next.run();
      return;
    }
    boolean started = wt.save(author(sender), "autosave", Commit.AUTO, r -> next.run(), e -> {
      plugin.getLogger().log(Level.WARNING, "自動存檔失敗", e);
      msg().send(sender, "save-failed", "error", String.valueOf(e.getMessage()));
    });
    if (!started) msg().send(sender, "save-busy");
  }

  /** 玩家的選取範圍換成版本中的維度與範圍。 */
  private Workspace.Scope selection(Player p) {
    var sel = plugin.selections().get(p);
    if (sel.isEmpty()) {
      msg().send(p, "sel-none");
      return null;
    }
    return new Workspace.Scope(ws().logicalDimension(sel.get().world()), sel.get().box());
  }

  private void outline(CommandSender sender, Collection<Workspace.Changed> changed) {
    if (sender instanceof Player p)
      plugin.outlines().show(p, p.getWorld(), Outlines.shapes(changed, ws().logicalDimension(p.getWorld())), OUTLINE_SECONDS);
  }

  private Component button(String key, String command) {
    return msg().get(key).clickEvent(ClickEvent.runCommand(command));
  }

  private void sendButton(CommandSender sender, String key, String command) {
    if (sender instanceof Player) sender.sendMessage(button(key, command));
    else msg().send(sender, "console-confirm", "command", command);
  }

  private static String pos(BlockPos p) {
    return p.x() + " " + p.y() + " " + p.z();
  }

  // ---- 基本 ----

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
    var wt = current(sender);
    msg().send(sender, "status-header", "branch", branchLabel(wt.branch()), "head", repo.head(wt.branch()).abbrev());
    var table = wt.table();
    if (table.isEmpty()) msg().send(sender, "status-clean");
    else {
      msg().send(sender, "status-pending", "cells", table.cellCount(), "entities", table.entityCount());
      table.cellsByAuthor().forEach((a, n) -> msg().send(sender, "status-author", "author", a.name(), "cells", n));
    }
    if (wt.uncleanShutdown()) msg().send(sender, "status-unclean");
    for (var s : ws().merges().all())
      msg().send(sender, "merge-entry", "id", s.id(), "source", s.source() == null ? s.base().abbrev() : s.source(), "target", s.target());
  }

  private void save(CommandSender sender, String message) {
    msg().send(sender, "save-start");
    boolean started = current(sender).save(author(sender), message.isBlank() ? "save" : message, 0,
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

  /** {@code log [分支] [數量] [--all] [--author <玩家>]}：預設只列里程碑，自動存檔收合成一行。 */
  private void log(CommandSender sender, String[] args) throws IOException {
    var repo = ws().repo();
    String branch = current(sender).branch();
    int limit = 10;
    boolean all = false;
    String author = null;
    for (int i = 0; i < args.length; i++) {
      var a = args[i];
      if (a.isEmpty()) continue;
      if (a.equals("--all")) all = true;
      else if (a.equals("--author") && i + 1 < args.length) author = args[++i];
      else if (a.chars().allMatch(Character::isDigit)) limit = parseInt(a, 10);
      else if (repo.refs().get(a).isPresent()) branch = a;
    }
    var ids = repo.history(repo.head(branch), all && author == null ? limit + 1 : 5000);
    int shown = 0, folded = 0;
    for (var id : ids) {
      var c = repo.commit(id);
      if (c.parents().isEmpty()) continue; // 初始化的空提交
      if (author != null && !wrote(c, author)) continue;
      if (!all && c.auto()) {
        folded++;
        continue;
      }
      if (folded > 0) msg().send(sender, "log-folded", "count", folded);
      folded = 0;
      msg().send(sender, "log-entry", "id", id.abbrev(), "time", TIME.format(Instant.ofEpochMilli(c.time())), "author", c.author().name(),
          "message", c.auto() ? msg().get("log-auto") : Component.text(c.message()));
      if (++shown >= limit) break;
    }
    if (folded > 0) msg().send(sender, "log-folded", "count", folded);
    if (shown == 0 && folded == 0) msg().send(sender, "log-empty");
  }

  private boolean wrote(Commit c, String name) throws IOException {
    if (c.author().name().equalsIgnoreCase(name)) return true;
    for (var a : ws().repo().changeset(c.changeset()).authors()) if (a.name().equalsIgnoreCase(name)) return true;
    return false;
  }

  private void show(CommandSender sender, String[] args) throws IOException {
    var repo = ws().repo();
    boolean listCells = flags(args).contains("--cells");
    var w = words(args);
    var id = w.isEmpty() ? repo.head(current(sender).branch()) : resolve(w.getFirst());
    if (id == null) {
      msg().send(sender, "unknown-commit", "id", w.getFirst());
      return;
    }
    Commit c = repo.commit(id);
    msg().send(sender, "show-header", "id", id.abbrev(), "author", c.author().name(), "time", TIME.format(Instant.ofEpochMilli(c.time())),
        "message", c.message());
    var cs = repo.changeset(c.changeset());
    cs.cellsByAuthor().forEach((a, n) -> msg().send(sender, "show-line", "author", a.name(), "cells", n));
    if (!cs.entities().isEmpty()) msg().send(sender, "show-entities", "entities", cs.entities().size());
    if (listCells) {
      int shown = 0;
      for (var cell : cs.cells()) {
        if (shown++ == SHOW_CELLS) {
          msg().send(sender, "show-more", "count", cs.cells().size() - SHOW_CELLS);
          break;
        }
        msg().send(sender, "show-cell", "pos", pos(cell.pos()), "dimension", cell.dimension(), "author", cs.author(cell.author()).name(),
            "kind", msg().get(kind(cell.kind())));
      }
    }
  }

  /** {@code diff}：未存的變更；{@code diff <存檔>}：那次存檔；{@code diff <a> <b>}：兩個版本之間。在世界裡顯示外框。 */
  private void diff(CommandSender sender, String[] args) throws IOException {
    var w = words(args);
    var repo = ws().repo();
    if (w.isEmpty()) {
      var changed = new ArrayList<Workspace.Changed>();
      current(sender).table().markedSections().forEach((key, cells) -> {
        for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1))
          changed.add(new Workspace.Changed(key.dimension(), key.pos(i), Workspace.Kind.MODIFIED));
      });
      msg().send(sender, "diff-unsaved", "cells", changed.size());
      outline(sender, changed);
      return;
    }
    var a = resolve(w.getFirst());
    var b = w.size() > 1 ? resolve(w.get(1)) : null;
    if (a == null || w.size() > 1 && b == null) {
      msg().send(sender, "unknown-commit", "id", a == null ? w.getFirst() : w.get(1));
      return;
    }
    ObjectId from, to;
    if (b != null) {
      from = repo.commit(a).tree();
      to = repo.commit(b).tree();
    } else {
      var c = repo.commit(a);
      to = c.tree();
      from = c.parents().isEmpty() ? to : repo.commit(c.parents().getFirst()).tree();
    }
    record Counted(List<Workspace.Changed> changed, int[] counts, int entities) {}
    CompletableFuture.supplyAsync(() -> {
      try {
        var diff = TreeDiff.collect(repo.overlays(), from, to);
        var changed = new ArrayList<Workspace.Changed>();
        var cache = new HashMap<dev.twme.buildgit.core.model.SectionKey, dev.twme.buildgit.core.model.Cell[]>();
        int[] counts = new int[3];
        for (var c : diff.cells()) {
          var k = ws().kind(c.dimension(), c.pos(), c.before(), c.after(), cache);
          counts[k.ordinal()]++;
          changed.add(new Workspace.Changed(c.dimension(), c.pos(), k));
        }
        return new Counted(changed, counts, diff.entities().size());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, ws()::async).whenComplete((r, error) -> ws().sync(() -> {
      if (error != null) {
        failed(sender, Workspace.unwrap(error));
        return;
      }
      msg().send(sender, "diff-summary", "added", r.counts()[0], "removed", r.counts()[1], "modified", r.counts()[2], "entities", r.entities());
      outline(sender, r.changed());
    }));
  }

  private void mark(CommandSender sender, String[] args) {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.size() != 6) {
      msg().send(sender, "usage-mark");
      return;
    }
    int[] v = new int[6];
    try {
      for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(w.get(i));
    } catch (NumberFormatException e) {
      msg().send(sender, "usage-mark");
      return;
    }
    var box = Box.of(new BlockPos(v[0], v[1], v[2]), new BlockPos(v[3], v[4], v[5]));
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

  private void sel(CommandSender sender, String[] args) {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    var what = w.isEmpty() ? "show" : w.getFirst();
    switch (what) {
      case "pos1", "pos2" -> {
        var block = p.getTargetBlockExact(8);
        var at = block != null ? new BlockPos(block.getX(), block.getY(), block.getZ()) : Workspace.pos(p.getLocation());
        plugin.selections().set(p, what.equals("pos1") ? 0 : 1, at);
        msg().send(p, "sel-set", "corner", what, "pos", pos(at));
      }
      case "clear" -> {
        plugin.selections().clear(p);
        plugin.outlines().clear(p);
        msg().send(p, "sel-cleared");
      }
      default -> {
        var sel = plugin.selections().get(p);
        if (sel.isEmpty()) {
          msg().send(p, "sel-none");
          return;
        }
        var b = sel.get().box();
        msg().send(p, "sel-current", "world", sel.get().world().getName(), "from", b.minX() + " " + b.minY() + " " + b.minZ(),
            "to", b.maxX() + " " + b.maxY() + " " + b.maxZ());
        if (sel.get().world().equals(p.getWorld()))
          plugin.outlines().show(p, p.getWorld(), List.of(new Outlines.Shape(b, Outlines.MODIFIED)), OUTLINE_SECONDS);
      }
    }
  }

  private void who(CommandSender sender) {
    for (var p : Bukkit.getOnlinePlayers()) {
      var wt = ws().worktree(p.getWorld());
      var view = ws().view(p.getWorld());
      Component where = wt != null ? branchLabel(wt.branch())
          : view != null ? msg().get("place-view", "id", view.commit().abbrev()) : msg().get("place-other", "world", p.getWorld().getName());
      msg().send(sender, "who-entry", "player", p.getName(), "place", where);
    }
  }

  // ---- 檢查 ----

  private void toggleInspect(CommandSender sender) {
    var p = player(sender);
    if (p == null) return;
    if (inspecting.remove(p.getUniqueId())) msg().send(p, "inspect-off");
    else {
      inspecting.add(p.getUniqueId());
      msg().send(p, "inspect-on");
    }
  }

  private void blame(CommandSender sender) {
    var p = player(sender);
    if (p == null) return;
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
    var view = ws().view(block.getWorld());
    var tracker = plugin.tracker();
    var d = tracker.dimension(block.getWorld());
    String pos = block.getX() + " " + block.getY() + " " + block.getZ();
    if (d == null && view == null) {
      msg().send(player, "inspect-untracked");
      return;
    }
    if (d != null) {
      var pending = d.authorAt(block.getX(), block.getY(), block.getZ());
      if (pending != null) {
        msg().send(player, "inspect-pending", "pos", pos, "author", pending.name());
        return;
      }
    }
    var repo = ws().repo();
    var dim = ws().logicalDimension(block.getWorld());
    var at = new BlockPos(block.getX(), block.getY(), block.getZ());
    var wt = ws().worktree(block.getWorld());
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      try {
        var start = view != null ? view.commit() : repo.head(wt.branch());
        var blame = repo.blame(start, dim, at);
        String time = blame.isEmpty() ? "" : TIME.format(Instant.ofEpochMilli(repo.commit(blame.get().commit()).time()));
        ws().sync(() -> {
          if (blame.isEmpty()) {
            msg().send(player, "inspect-none", "pos", pos);
            return;
          }
          var b = blame.get();
          msg().send(player, "inspect-blame", "pos", pos, "author", b.author().name(), "id", b.commit().abbrev(), "time", time,
              "kind", msg().get(kind(b.kind())));
        });
      } catch (IOException ex) {
        plugin.getLogger().log(Level.WARNING, "blame 失敗", ex);
      }
    });
  }

  private static String kind(Changeset.Kind kind) {
    return switch (kind) {
      case ADDED -> "kind-added";
      case REMOVED -> "kind-removed";
      default -> "kind-modified";
    };
  }

  // ---- 草稿（設計 §6.2） ----

  /** 主控台也可以用：從主世界開草稿並載入世界，不傳送。 */
  private void tryDraft(CommandSender sender, String[] args) {
    var w = words(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-try");
      return;
    }
    String name = w.getFirst().toLowerCase(Locale.ROOT);
    var from = current(sender);
    if (Workspace.isScratch(from.branch())) from = ws().worktree(ws().source(from.branch()));
    if (from == null) from = ws().main();
    if (name.equals(Repository.MAIN) || Workspace.isScratch(name) || !Workspace.BRANCH_NAME.matcher(name).matches()) {
      msg().send(sender, "branch-bad-name");
      return;
    }
    var source = from;
    autosaveThen(sender, source, () -> {
      try {
        boolean exists = ws().branches().containsKey(name);
        if (!exists) ws().createBranch(name, source.branch());
        msg().send(sender, exists ? "try-existing" : "try-created", "name", name);
        var draft = ws().openBranch(name);
        if (sender instanceof Player p) ws().teleport(p, draft);
      } catch (IOException | RuntimeException e) {
        plugin.getLogger().log(Level.WARNING, "try 失敗", e);
        failed(sender, e);
      }
    });
  }

  private void go(CommandSender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-go");
      return;
    }
    String name = w.getFirst().toLowerCase(Locale.ROOT);
    if (!ws().drafts().containsKey(name)) {
      msg().send(p, "branch-unknown", "name", name);
      return;
    }
    ws().teleport(p, ws().openBranch(name));
    msg().send(p, "go-done", "branch", branchLabel(name));
  }

  private void back(CommandSender sender) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var world = p.getWorld();
    if (ws().view(world) != null) {
      var session = ws().merges().of(world);
      var saved = returnTo.remove(p.getUniqueId());
      if (session != null) ws().teleport(p, ws().openBranch(session.branch()));
      else if (saved != null && saved.getWorld() != null) p.teleportAsync(saved);
      else ws().teleport(p, ws().main());
      return;
    }
    var wt = ws().worktree(world);
    if (wt == null || wt.branch().equals(Repository.MAIN)) {
      msg().send(p, "not-in-draft");
      return;
    }
    String source = ws().source(wt.branch());
    ws().teleport(p, ws().openBranch(source));
    msg().send(p, "go-done", "branch", branchLabel(source));
  }

  /** {@code view <版本>}：唯讀地看某個版本（設計 §6.5）。 */
  private void view(CommandSender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.size() != 1) {
      msg().send(p, "usage-view");
      return;
    }
    var id = resolve(w.getFirst());
    if (id == null) {
      msg().send(p, "unknown-commit", "id", w.getFirst());
      return;
    }
    if (ws().view(p.getWorld()) == null) returnTo.put(p.getUniqueId(), p.getLocation());
    var world = ws().openView(id, ws().logicalDimension(p.getWorld()));
    Workspace.teleport(p, world);
    msg().send(p, "view-opened", "id", id.abbrev());
  }

  // ---- 併回（設計 §6.2） ----

  /**
   * 玩家：{@code publish [--selection] [confirm]}；主控台：{@code publish <草稿> [confirm]}。
   * 不帶 confirm 時只預覽：數量、作者，並在世界裡顯示會變動的位置。
   */
  private void publish(CommandSender sender, String[] args) throws IOException {
    var flags = flags(args);
    var w = words(args);
    Worktree draft;
    if (sender instanceof Player p) {
      draft = ws().worktree(p.getWorld());
      if (draft != null && Workspace.isScratch(draft.branch())) {
        msg().send(p, "merge-use-continue");
        return;
      }
    } else draft = w.size() == 1 && ws().drafts().containsKey(w.getFirst()) ? ws().openBranch(w.getFirst()) : null;
    if (draft == null || draft.branch().equals(Repository.MAIN)) {
      msg().send(sender, "not-in-draft");
      return;
    }
    Workspace.Scope scope = null;
    if (flags.contains("--selection")) {
      var p = player(sender);
      if (p == null || (scope = selection(p)) == null) return;
    }
    String targetBranch = ws().source(draft.branch());
    var target = ws().openBranch(targetBranch);
    var existing = ws().merges().find(targetBranch, draft.branch());
    if (existing != null) {
      msg().send(sender, "merge-existing", "id", existing.id());
      sendButton(sender, "merge-goto-button", "/bg merge view result");
      return;
    }
    var finalScope = scope;
    var d = draft;
    boolean confirm = flags.contains("confirm");
    msg().send(sender, confirm ? "publish-start" : "publish-preview-start", "name", draft.branch(), "target", branchLabel(targetBranch));
    autosaveThen(sender, draft, () -> autosaveThen(sender, target, () -> {
      if (confirm) merge(sender, target, d.branch(), finalScope, d);
      else preview(sender, target, d.branch(), finalScope, flags.contains("--selection"));
    }));
  }

  private void preview(CommandSender sender, Worktree target, String source, Workspace.Scope scope, boolean selection) {
    ws().preview(target.branch(), source, scope).whenComplete((preview, error) -> ws().sync(() -> {
      if (error != null) {
        failed(sender, Workspace.unwrap(error));
        return;
      }
      if (preview.upToDate()) {
        msg().send(sender, "publish-nothing");
        return;
      }
      msg().send(sender, "publish-preview", "cells", preview.cells(), "entities", preview.entities(), "target", branchLabel(target.branch()));
      preview.authors().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
          .forEach(e -> msg().send(sender, "publish-preview-author", "author", e.getKey(), "cells", e.getValue()));
      int conflicts = preview.result().cellConflicts().size() + preview.result().entityConflicts().size();
      if (conflicts > 0) msg().send(sender, "publish-preview-conflicts", "count", conflicts);
      outline(sender, preview.changed());
      String command = "/bg publish " + (sender instanceof Player ? "" : source + " ") + (selection ? "--selection " : "") + "confirm";
      sendButton(sender, "publish-confirm-button", command);
    }));
  }

  /** 合併並寫入目標世界；有衝突時開始衝突整合。{@code draft} 不為 null 時，完成後把在草稿裡的玩家送回目標世界。 */
  private void merge(CommandSender sender, Worktree target, String source, Workspace.Scope scope, Worktree draft) {
    ws().merge(target, source, scope, author(sender), applied -> {
      if (applied.isEmpty()) {
        msg().send(sender, "publish-nothing");
        return;
      }
      var a = applied.get();
      msg().send(sender, "publish-done", "id", a.commit().abbrev(), "cells", a.cells(), "entities", a.entities(), "target",
          branchLabel(target.branch()));
      if (a.shapes() > 0) msg().send(sender, "shapes-fixed", "cells", a.shapes());
      if (draft != null && sender instanceof Player p && p.isOnline() && ws().worktree(p.getWorld()) == draft) {
        ws().teleport(p, target);
        if (draft.branch().equals(source)) sendButton(p, "draft-delete-button", "/bg branch delete " + source);
      }
    }, error -> {
      if (error instanceof Workspace.ConflictException c)
        startSession(sender, scope == null ? MergeSessions.Kind.MERGE : MergeSessions.Kind.PARTIAL, target.branch(), source, scope, c);
      else {
        plugin.getLogger().log(Level.WARNING, "併回失敗", error);
        failed(sender, error);
      }
    });
  }

  private void startSession(CommandSender sender, MergeSessions.Kind kind, String target, String source, Workspace.Scope scope,
      Workspace.ConflictException c) {
    ws().merges().start(kind, target, source, scope, c, author(sender), session -> {
      msg().send(sender, "merge-started", "cells", c.result.cellConflicts().size(), "entities", c.result.entityConflicts().size());
      msg().send(sender, "merge-help");
      if (sender instanceof Player p) {
        try {
          ws().teleport(p, ws().openBranch(session.branch()));
        } catch (IOException e) {
          failed(p, e);
        }
      }
      listConflicts(sender, session);
    }, e -> {
      plugin.getLogger().log(Level.WARNING, "無法開始衝突整合", e);
      failed(sender, e);
    });
  }

  // ---- 分支 ----

  private void branch(CommandSender sender, String[] args) throws IOException {
    var w = words(args);
    var what = w.isEmpty() ? "list" : w.getFirst().toLowerCase(Locale.ROOT);
    switch (what) {
      case "list" -> {
        for (var e : ws().drafts().entrySet()) {
          var wt = ws().worktree(e.getKey());
          int players = 0;
          if (wt != null) for (var world : wt.worlds().values()) players += world.getPlayers().size();
          msg().send(sender, "branch-entry", "branch", branchLabel(e.getKey()), "head", e.getValue().abbrev(), "players", players);
        }
        for (var s : ws().merges().all())
          msg().send(sender, "merge-entry", "id", s.id(), "source", s.source() == null ? s.base().abbrev() : s.source(), "target", s.target());
      }
      case "create" -> {
        if (!sender.hasPermission("buildgit.branch.create")) {
          msg().send(sender, "no-permission");
          return;
        }
        if (w.size() < 2) {
          msg().send(sender, "usage-branch");
          return;
        }
        var name = w.get(1).toLowerCase(Locale.ROOT);
        var from = w.size() > 2 ? w.get(2) : current(sender).branch();
        if (name.equals(Repository.MAIN) || Workspace.isScratch(name) || !Workspace.BRANCH_NAME.matcher(name).matches()) {
          msg().send(sender, "branch-bad-name");
          return;
        }
        ws().createBranch(name, from);
        msg().send(sender, "branch-created", "name", name, "from", branchLabel(from));
      }
      case "delete" -> {
        if (!sender.hasPermission("buildgit.branch.delete")) {
          msg().send(sender, "no-permission");
          return;
        }
        if (w.size() < 2 || w.get(1).equals(Repository.MAIN) || !ws().drafts().containsKey(w.get(1))) {
          msg().send(sender, "branch-unknown", "name", w.size() < 2 ? "" : w.get(1));
          return;
        }
        ws().deleteBranch(w.get(1), flags(args).contains("--force"));
        msg().send(sender, "branch-deleted", "name", w.get(1));
      }
      default -> msg().send(sender, "usage-branch");
    }
  }

  // ---- 合併與衝突整合（設計 §5.2、§5.3） ----

  private MergeSessions.Session session(CommandSender sender, List<String> w) {
    var merges = ws().merges();
    MergeSessions.Session s = null;
    if (sender instanceof Player p) s = merges.of(p.getWorld());
    if (s == null && w.size() > 1) s = merges.get(w.getLast());
    if (s == null && merges.all().size() == 1) s = merges.all().iterator().next();
    if (s == null) msg().send(sender, "merge-none");
    return s;
  }

  private boolean mayResolve(CommandSender sender) {
    if (sender.hasPermission("buildgit.merge") || sender.hasPermission("buildgit.publish") || sender.hasPermission("buildgit.revert")) return true;
    msg().send(sender, "no-permission");
    return false;
  }

  private void merge(CommandSender sender, String[] args) throws IOException {
    var w = words(args);
    if (w.isEmpty()) {
      msg().send(sender, "usage-merge");
      return;
    }
    var what = w.getFirst();
    switch (what) {
      case "list" -> {
        if (!mayResolve(sender)) return;
        if (ws().merges().all().isEmpty()) msg().send(sender, "merge-none");
        for (var s : ws().merges().all())
          msg().send(sender, "merge-entry", "id", s.id(), "source", s.source() == null ? s.base().abbrev() : s.source(), "target", s.target());
      }
      case "view" -> {
        var p = player(sender);
        if (p == null || !mayResolve(p)) return;
        var s = session(p, w);
        if (s == null) return;
        var side = w.size() > 1 ? w.get(1) : "result";
        var dim = ws().logicalDimension(p.getWorld());
        switch (side) {
          case "ours" -> Workspace.teleport(p, ws().openView(s.ours(), dim));
          case "theirs" -> Workspace.teleport(p, ws().openView(s.theirs(), dim));
          default -> ws().teleport(p, ws().openBranch(s.branch()));
        }
        msg().send(p, "merge-view-" + (side.equals("ours") || side.equals("theirs") ? side : "result"), "target", s.target(), "source",
            String.valueOf(s.source()));
      }
      case "conflicts" -> {
        if (!mayResolve(sender)) return;
        var s = session(sender, w);
        if (s != null) listConflicts(sender, s);
      }
      case "goto" -> {
        var p = player(sender);
        if (p == null || !mayResolve(p)) return;
        var s = session(p, List.of());
        if (s == null) return;
        int n = w.size() > 1 ? parseInt(w.get(1), 1) : 1;
        ws().merges().result(s).whenComplete((result, error) -> ws().sync(() -> {
          if (error != null) {
            failed(p, Workspace.unwrap(error));
            return;
          }
          var clusters = MergeSessions.clusters(result);
          if (n > clusters.size()) {
            msg().send(p, "merge-no-conflicts");
            return;
          }
          var c = clusters.get(n - 1);
          try {
            var world = ws().openBranch(s.branch()).world(c.dimension());
            if (world == null) return;
            var b = c.box();
            p.teleportAsync(new Location(world, (b.minX() + b.maxX()) / 2.0 + .5, b.maxY() + 1, (b.minZ() + b.maxZ()) / 2.0 + .5, p.getYaw(), p.getPitch()));
          } catch (IOException e) {
            failed(p, e);
          }
        }));
      }
      case "take" -> {
        // 玩家：take ours|theirs [here|selection|all]；主控台只能 take ours|theirs all [編號]。
        if (!mayResolve(sender)) return;
        if (w.size() < 2 || !(w.get(1).equals("ours") || w.get(1).equals("theirs"))) {
          msg().send(sender, "usage-merge-take");
          return;
        }
        var range = w.size() > 2 ? w.get(2) : sender instanceof Player ? "here" : "all";
        var p = sender instanceof Player player ? player : null;
        if (p == null && !range.equals("all")) {
          msg().send(sender, "players-only");
          return;
        }
        var s = session(sender, p == null ? w.subList(2, w.size()) : List.of());
        if (s == null) return;
        Workspace.Scope scope = switch (range) {
          case "all" -> null;
          case "selection" -> selection(p);
          default -> {
            var at = Workspace.pos(p.getLocation());
            yield new Workspace.Scope(ws().logicalDimension(p.getWorld()), new Box(at.x() - HERE_RADIUS, at.y() - HERE_RADIUS, at.z() - HERE_RADIUS,
                at.x() + HERE_RADIUS, at.y() + HERE_RADIUS, at.z() + HERE_RADIUS));
          }
        };
        if (range.equals("selection") && scope == null) return;
        var side = w.get(1).equals("ours") ? MergeSessions.Side.OURS : MergeSessions.Side.THEIRS;
        ws().merges().take(s, side, scope, author(sender), n -> msg().send(sender, "merge-taken", "count", n, "side", w.get(1)),
            e -> failed(sender, e));
      }
      case "continue" -> {
        if (!mayResolve(sender)) return;
        var s = session(sender, w);
        if (s == null) return;
        msg().send(sender, "merge-finishing");
        ws().merges().finish(s, author(sender), applied -> {
          if (applied.isEmpty()) msg().send(sender, "merge-finished-empty", "target", branchLabel(s.target()));
          else {
            var a = applied.get();
            msg().send(sender, "publish-done", "id", a.commit().abbrev(), "cells", a.cells(), "entities", a.entities(), "target",
                branchLabel(s.target()));
            if (a.shapes() > 0) msg().send(sender, "shapes-fixed", "cells", a.shapes());
          }
        }, e -> {
          if (e instanceof Workspace.ConflictException c)
            msg().send(sender, "merge-target-moved", "count", c.result.cellConflicts().size() + c.result.entityConflicts().size());
          else failed(sender, e);
        });
      }
      case "abort" -> {
        if (!mayResolve(sender)) return;
        var s = session(sender, w);
        if (s == null) return;
        ws().merges().discard(s);
        msg().send(sender, "merge-aborted");
      }
      default -> {
        // merge <分支>：把它合併進目前所在的分支（底層指令）。
        if (!sender.hasPermission("buildgit.merge")) {
          msg().send(sender, "no-permission");
          return;
        }
        var target = current(sender);
        if (Workspace.isScratch(target.branch()) || ws().repo().refs().get(what).isEmpty() || what.equals(target.branch())) {
          msg().send(sender, "branch-unknown", "name", what);
          return;
        }
        if (ws().merges().find(target.branch(), what) != null) {
          msg().send(sender, "merge-existing", "id", ws().merges().find(target.branch(), what).id());
          return;
        }
        var source = ws().worktree(what);
        msg().send(sender, "publish-start", "name", what, "target", branchLabel(target.branch()));
        Runnable go = () -> autosaveThen(sender, target, () -> merge(sender, target, what, null, null));
        if (source != null) autosaveThen(sender, source, go);
        else go.run();
      }
    }
  }

  private void listConflicts(CommandSender sender, MergeSessions.Session s) {
    ws().merges().result(s).whenComplete((result, error) -> ws().sync(() -> {
      if (error != null) {
        failed(sender, Workspace.unwrap(error));
        return;
      }
      var clusters = MergeSessions.clusters(result);
      if (clusters.isEmpty()) {
        msg().send(sender, "merge-no-conflicts");
        return;
      }
      msg().send(sender, "merge-conflicts-header", "count", result.cellConflicts().size() + result.entityConflicts().size(), "groups",
          clusters.size());
      for (int i = 0; i < Math.min(10, clusters.size()); i++) {
        var c = clusters.get(i);
        var b = c.box();
        var line = msg().get("merge-cluster", "n", i + 1, "cells", c.cells(), "dimension", c.dimension(), "from",
            b.minX() + " " + b.minY() + " " + b.minZ(), "to", b.maxX() + " " + b.maxY() + " " + b.maxZ());
        if (sender instanceof Player) sender.sendMessage(line.clickEvent(ClickEvent.runCommand("/bg merge goto " + (i + 1))));
        else sender.sendMessage(line);
      }
      if (sender instanceof Player p) {
        String dim = ws().logicalDimension(p.getWorld());
        var shapes = new ArrayList<Outlines.Shape>();
        for (var c : result.cellConflicts())
          if (c.dimension().equals(dim)) shapes.add(new Outlines.Shape(new Box(c.pos().x(), c.pos().y(), c.pos().z(), c.pos().x(), c.pos().y(), c.pos().z()), Outlines.CONFLICT));
        if (shapes.size() > 400) {
          shapes.clear();
          for (var c : clusters) if (c.dimension().equals(dim)) shapes.add(new Outlines.Shape(c.box(), Outlines.CONFLICT));
        }
        plugin.outlines().show(p, p.getWorld(), shapes, OUTLINE_SECONDS * 5);
      }
    }));
  }

  // ---- 還原（設計 §5.4） ----

  private void revert(CommandSender sender, String[] args) throws IOException {
    var w = words(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-revert");
      return;
    }
    var id = resolve(w.getFirst());
    if (id == null) {
      msg().send(sender, "unknown-commit", "id", w.getFirst());
      return;
    }
    var wt = current(sender);
    if (Workspace.isScratch(wt.branch())) {
      msg().send(sender, "merge-use-continue");
      return;
    }
    autosaveThen(sender, wt, () -> ws().revert(wt, id, author(sender), applied -> {
      if (applied.isEmpty()) msg().send(sender, "revert-nothing");
      else {
        var a = applied.get();
        msg().send(sender, "revert-done", "id", a.commit().abbrev(), "reverted", id.abbrev(), "cells", a.cells(), "entities", a.entities());
        if (a.shapes() > 0) msg().send(sender, "shapes-fixed", "cells", a.shapes());
      }
    }, e -> {
      if (e instanceof Workspace.ConflictException c) startSession(sender, MergeSessions.Kind.REVERT, wt.branch(), null, null, c);
      else failed(sender, e);
    }));
  }

  private void restore(CommandSender sender, String[] args) throws IOException {
    var w = words(args);
    var flags = flags(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-restore");
      return;
    }
    var id = resolve(w.getFirst());
    if (id == null) {
      msg().send(sender, "unknown-commit", "id", w.getFirst());
      return;
    }
    var wt = current(sender);
    if (Workspace.isScratch(wt.branch())) {
      msg().send(sender, "merge-use-continue");
      return;
    }
    Workspace.Scope scope = null;
    if (flags.contains("--selection")) {
      var p = player(sender);
      if (p == null || (scope = selection(p)) == null) return;
    }
    if (!flags.contains("confirm")) {
      msg().send(sender, scope == null ? "restore-confirm" : "restore-confirm-selection", "branch", branchLabel(wt.branch()), "id", id.abbrev());
      sendButton(sender, "restore-confirm-button", "/bg restore " + id.abbrev() + (scope == null ? "" : " --selection") + " confirm");
      return;
    }
    var finalScope = scope;
    autosaveThen(sender, wt, () -> ws().restore(wt, id, finalScope, author(sender), applied -> {
      if (applied.isEmpty()) msg().send(sender, "restore-nothing");
      else {
        var a = applied.get();
        msg().send(sender, "restore-done", "id", a.commit().abbrev(), "target", id.abbrev(), "cells", a.cells(), "entities", a.entities());
        if (a.shapes() > 0) msg().send(sender, "shapes-fixed", "cells", a.shapes());
      }
    }, e -> failed(sender, e)));
  }

}
