package dev.twme.buildgit.server;

import dev.twme.buildgit.core.sync.Bundle;
import dev.twme.buildgit.core.sync.Frame;
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
import dev.twme.buildgit.core.repo.Graph;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /bg} 指令（設計 §6、§7），Brigadier 指令樹：子指令依權限顯示，參數有補全。
 * 指令來源的型別由平台決定（Paper 的 {@code CommandSourceStack} 介面、原版的類別），所以以泛型建構。
 *
 * <p>建築師日常用的三步流程（try／publish／back）與底層的分支、合併、還原都在這裡。
 */
public final class BgCommand {
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private static final int MAX_MARK = 1 << 22;
  private static final int SHOW_CELLS = 200;
  private static final int OUTLINE_SECONDS = 60;
  private static final int HERE_RADIUS = 8;
  /** 不需要版本庫已經開啟的子指令。 */
  private static final Set<String> WORLDLESS = Set.of("init", "reload", "help", "load", "new", "open", "home", "worlds", "world", "backup");

  private interface Handler {
    void run(Sender sender, String[] args) throws IOException;
  }

  private interface Suggester {
    List<String> suggest(Sender sender, List<String> before);
  }

  private record Sub(List<String> names, String permission, Handler handler, Suggester suggester) {}

  private record Location(ServerLevel level, double x, double y, double z, float yaw, float pitch) {}

  private final BuildGit bg;
  private final Set<UUID> inspecting = new HashSet<>();
  /** 進入檢視世界之前的位置，{@code back} 回到這裡。 */
  private final Map<UUID, Location> returnTo = new HashMap<>();
  private final List<Sub> subs = new ArrayList<>();

  BgCommand(BuildGit bg) {
    this.bg = bg;
    sub("help", null, (s, a) -> msg().send(s, "help"), null);
    sub("init", "buildgit.init", (s, a) -> init(s), null);
    sub("reload", "buildgit.reload", (s, a) -> {
      bg.reload();
      msg().send(s, "reloaded");
    }, null);
    sub("status", "buildgit.status", (s, a) -> status(s), null);
    sub("save", "buildgit.save", (s, a) -> save(s, String.join(" ", a)), null);
    sub("commit", "buildgit.commit", (s, a) -> save(s, String.join(" ", a)), null);
    sub("log", "buildgit.log", this::log, (s, b) -> concat(List.of("--all", "--author"), draftNames(s)));
    sub("graph", "buildgit.graph", this::graph, (s, b) -> List.of("--all"));
    sub("show", "buildgit.show", this::show, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of("--cells"));
    sub("diff", "buildgit.diff", this::diff, (s, b) -> b.isEmpty() ? concat(List.of("off"), recentCommits(s)) : b.size() < 2 ? recentCommits(s) : List.of());
    sub(List.of("inspect", "i"), "buildgit.inspect", (s, a) -> toggleInspect(s), null);
    sub("blame", "buildgit.blame", (s, a) -> blame(s), null);
    sub("mark", "buildgit.mark", this::mark, this::suggestMark);
    sub("sel", "buildgit.sel", this::sel, (s, b) -> b.isEmpty() ? List.of("pos1", "pos2", "show", "clear") : List.of());
    sub("who", "buildgit.who", (s, a) -> who(s), null);
    sub("disk", "buildgit.disk", (s, a) -> disk(s), null);
    sub("genesis", "buildgit.genesis", this::genesis, (s, b) -> b.isEmpty() ? List.of("upgrade") : List.of());
    sub("backup", "buildgit.backup", this::backup, (s, b) -> b.isEmpty() ? List.of("status", "now", "list", "restore", "fetch")
        : b.size() == 1 && List.of("now", "list", "restore").contains(b.getFirst()) ? bg.backup().jobs().stream().map(j -> j.name).toList() : List.of());
    sub("try", "buildgit.try", this::tryDraft, (s, b) -> b.isEmpty() ? draftNames(s) : List.of());
    sub("go", "buildgit.go", this::go, (s, b) -> b.isEmpty() ? draftNames(s) : List.of());
    sub("back", "buildgit.back", (s, a) -> back(s), null);
    sub("pull", "buildgit.pull", this::pull, null);
    sub("push", "buildgit.push", this::push, (s, b) -> b.isEmpty() && s.player() instanceof ServerPlayer p ? bg.sync().names(p) : List.of());
    sub("load", null, this::load, (s, b) -> b.isEmpty() && bg.platform().syncFolder() != null ? LocalSync.names(bg.platform().syncFolder()) : List.of());
    sub("view", "buildgit.view", this::view, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of());
    sub("publish", "buildgit.publish", this::publish, (s, b) -> concat(List.of("--selection", "confirm", "cancel"), s.isPlayer() ? List.of() : draftNames(s)));
    sub("branch", "buildgit.branch.list|buildgit.branch.create|buildgit.branch.delete", this::branch, (s, b) -> b.isEmpty() ? List.of("list", "create", "delete")
        : b.size() == 1 && b.getFirst().equals("delete") ? draftNames(s)
        : b.size() == 2 && b.getFirst().equals("create") ? draftNames(s)
        : b.size() == 1 && b.getFirst().equals("list") ? List.of("--all")
        : b.size() == 2 && b.getFirst().equals("delete") ? List.of("--force") : List.of());
    sub("merge", "buildgit.merge|buildgit.merge.resolve|buildgit.publish|buildgit.revert", this::merge, (s, b) -> b.isEmpty() ? concat(List.of("view", "conflicts", "goto", "take", "continue", "abort", "list"), draftNames(s))
        : b.size() == 1 && b.getFirst().equals("view") ? List.of("ours", "theirs", "result")
        : b.size() == 1 && b.getFirst().equals("take") ? List.of("ours", "theirs")
        : b.size() == 2 && b.getFirst().equals("take") ? List.of("here", "selection", "all") : List.of());
    sub("revert", "buildgit.revert", this::revert, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of());
    sub("restore", "buildgit.restore", this::restore, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of("--selection", "confirm"));
    // 建築世界（03-worlds）：進出、列出所有人都能用，能不能進去看成員名單與可見性
    sub("new", "buildgit.world.create|buildgit.world.admin", this::newWorld, (s, b) -> List.of("type=overworld", "type=nether", "type=end",
        "terrain=normal", "terrain=large-biomes", "terrain=amplified", "terrain=flat", "terrain=void", "rules=build", "rules=vanilla",
        "seed=", "layers=", "block=", "template=", "confirm"));
    sub("submit", "buildgit.world.create|buildgit.world.admin", this::submit, null);
    sub("fork", "buildgit.world.create|buildgit.world.admin", this::fork, (s, b) -> b.isEmpty() ? worldNames(s) : List.of());
    sub("open", null, this::openWorld, (s, b) -> b.isEmpty() ? worldNames(s) : List.of());
    sub("home", null, (s, a) -> home(s), null);
    sub("worlds", null, this::worlds, (s, b) -> b.isEmpty() ? Menu.WORLD_TABS : List.of());
    sub("world", null, this::world, (s, b) -> b.isEmpty() ? List.of("settings", "members", "submissions", "visibility", "rename", "transfer", "delete", "undelete", "unlist", "relist")
        : b.size() == 1 && b.getFirst().equals("transfer") ? onlineNames()
        : b.size() == 1 && b.getFirst().equals("undelete") ? deletedNames(s)
        : b.size() == 1 && (b.getFirst().equals("unlist") || b.getFirst().equals("relist")) ? worldNames(s)
        : b.size() == 1 && b.getFirst().equals("settings") ? List.copyOf(Menu.SETTINGS.keySet())
        : b.size() == 2 && b.getFirst().equals("settings") ? b.get(1).startsWith("kit-") ? List.of("clear") : Menu.SETTINGS.getOrDefault(b.get(1), List.of())
        : b.size() == 1 && b.getFirst().equals("members") ? List.of("list", "add", "remove")
        : b.size() == 1 && b.getFirst().equals("visibility") ? List.of("public", "private")
        : b.size() == 2 && b.get(1).equals("add") ? onlineNames()
        : b.size() == 2 && b.get(1).equals("remove") ? memberNames(s)
        : b.size() == 3 && b.get(1).equals("add") ? List.of("builder", "manager") : List.of());
  }

  /** {@code permissions}：以 {@code |} 分隔，有其中一個就能用（子指令各有權限時）。 */
  private static boolean anyPermission(Sender sender, String permissions) {
    for (var p : permissions.split("\\|")) if (sender.hasPermission(p)) return true;
    return false;
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
    return bg.messages();
  }

  /** 指令作用的版本庫：玩家所在世界的（建築世界或伺服器世界）；主控台是伺服器世界的。 */
  private Workspace ws(Sender sender) {
    return sender.player() instanceof ServerPlayer p ? bg.workspace(p.level()) : bg.workspace();
  }

  // ---- Brigadier ----

  /** @param sender 把平台的指令來源轉成 {@link Sender} */
  public <S> LiteralCommandNode<S> build(Function<S, Sender> sender) {
    var root = LiteralArgumentBuilder.<S>literal("bg").executes(ctx -> {
      var s = sender.apply(ctx.getSource());
      if (s.player() != null && s.hasPermission("buildgit.menu") && ws(s).isOpen()) bg.menu().open(s.player());
      else msg().send(s, "help");
      return 1;
    });
    for (var sub : subs)
      for (var name : sub.names()) {
        // 每個參數一個節點，玩家輸入時看得到要填什麼（名稱在語言檔 params-<子指令>）；最後一個吃下剩下的全部
        var params = msg().raw("params-" + sub.names().getFirst()).trim();
        var names = params.isEmpty() ? new String[0] : params.split(" +");
        ArgumentBuilder<S, ?> next = null;
        for (int i = names.length - 1; i >= 0; i--) {
          var arg = RequiredArgumentBuilder.<S, String>argument(names[i], i == names.length - 1 ? StringArgumentType.greedyString() : StringArgumentType.word())
              .suggests((ctx, builder) -> suggest(sub, argsStart(ctx, name), sender.apply(ctx.getSource()), builder))
              .executes(ctx -> dispatch(sub, sender.apply(ctx.getSource()), args(ctx, name)));
          if (next != null) arg.then(next);
          next = arg;
        }
        var literal = LiteralArgumentBuilder.<S>literal(name)
            .requires(src -> sub.permission() == null || anyPermission(sender.apply(src), sub.permission()))
            .executes(ctx -> dispatch(sub, sender.apply(ctx.getSource()), new String[0]));
        if (next != null) literal.then(next);
        root.then(literal);
      }
    return root.build();
  }

  /** 指令輸入中子指令之後的參數。 */
  private static String[] args(CommandContext<?> ctx, String name) {
    int at = argsStart(ctx, name);
    var rest = at < 0 ? "" : ctx.getInput().substring(at).trim();
    return rest.isEmpty() ? new String[0] : rest.split(" +");
  }

  /** 輸入中子指令名稱之後的位置；找不到時 -1。 */
  private static int argsStart(CommandContext<?> ctx, String name) {
    for (var n : ctx.getNodes())
      if (n.getNode() instanceof LiteralCommandNode<?> l && l.getLiteral().equals(name)) return n.getRange().getEnd();
    return -1;
  }

  private CompletableFuture<Suggestions> suggest(Sub sub, int start, Sender sender, SuggestionsBuilder builder) {
    if (sub.suggester() == null || !ws(sender).isOpen() && !sub.names().contains("load")) return builder.buildFuture();
    var input = builder.getInput();
    if (start < 0 || start > input.length()) return builder.buildFuture();
    var typed = input.substring(start);
    int space = typed.lastIndexOf(' ');
    var done = typed.substring(0, Math.max(0, space)).trim();
    var before = done.isEmpty() ? List.<String>of() : Arrays.asList(done.split(" +"));
    var current = typed.substring(space + 1).toLowerCase(Locale.ROOT);
    var b = builder.createOffset(start + space + 1);
    try {
      for (var option : sub.suggester().suggest(sender, before))
        if (option.toLowerCase(Locale.ROOT).startsWith(current)) b.suggest(option);
    } catch (RuntimeException e) {
      // 補全失敗不影響指令
    }
    return b.buildFuture();
  }

  private int dispatch(Sub sub, Sender sender, String[] args) {
    String name = sub.names().getFirst();
    if (!WORLDLESS.contains(name) && !ws(sender).isOpen()) {
      if (bg.openError() != null) msg().send(sender, "open-failed", "error", bg.openError());
      else msg().send(sender, "not-initialized");
      return 1;
    }
    try {
      sub.handler().run(sender, args);
    } catch (IOException | RuntimeException e) {
      if (!(e instanceof Workspace.Refused)) bg.platform().log().log(Level.WARNING, "/bg " + name + " 失敗", e);
      failed(sender, e);
    }
    return 1;
  }

  private void failed(Sender sender, Throwable e) {
    done(sender);
    msg().send(sender, "branch-failed", "error", String.valueOf(e.getMessage()));
  }

  /** 花時間的動作：在執行的玩家的 bossbar 上顯示進度（{@code progress} 為 null 時只顯示秒數），直到 {@link #done}。 */
  private void progress(Sender sender, Progress progress, String key, Object... placeholders) {
    bg.hud().start(sender, msg().get(key, placeholders), progress);
  }

  private void done(Sender sender) {
    bg.hud().end(sender);
  }

  /** 玩家所在世界的草稿與主世界（主控台：全部）。 */
  private List<String> draftNames(Sender sender) {
    var ws = ws(sender);
    try {
      var dim = here(sender);
      return ws.drafts().keySet().stream().filter(name -> dim == null || ws.covers(name, dim)).toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  /** 玩家所在的世界（版本中的維度 id）；主控台為 null。 */
  private String here(Sender sender) {
    var ws = ws(sender);
    return sender.player() instanceof ServerPlayer p ? ws.logicalDimension(p.level()) : null;
  }

  private List<String> recentCommits(Sender sender) {
    var ws = ws(sender);
    try {
      var repo = ws.repo();
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
  private Worktree current(Sender sender) {
    var ws = ws(sender);
    var wt = sender.player() instanceof ServerPlayer p ? ws.worktree(p.level()) : null;
    return wt != null ? wt : ws.main();
  }

  private Component branchLabel(String branch) {
    if (branch.equals(Repository.MAIN)) return msg().get("branch-main");
    if (Workspace.isScratch(branch)) return msg().get("branch-merge", "name", branch);
    return msg().get("branch-draft", "name", branch);
  }

  private Author author(Sender sender) {
    return sender.player() instanceof ServerPlayer p ? bg.tracker().author(p) : Author.SYSTEM;
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

  private ServerPlayer player(Sender sender) {
    if (sender.player() instanceof ServerPlayer p) return p;
    msg().send(sender, "players-only");
    return null;
  }

  private Sender s(ServerPlayer p) {
    return bg.platform().sender(p);
  }

  /** 存檔或分支名稱（分支表示它的最新存檔）。 */
  private ObjectId resolve(Workspace ws, String ref) throws IOException {
    var repo = ws.repo();
    var branch = repo.refs().get(ref);
    if (branch.isPresent()) return branch.get();
    var prefix = ref.toLowerCase(Locale.ROOT);
    for (var head : repo.refs().list().values())
      for (var id : repo.history(head, 10_000)) if (id.hex().startsWith(prefix)) return id;
    return null;
  }

  /** 有未存的變更就先自動存檔，存好（或本來就沒有）再繼續。 */
  private void autosaveThen(Sender sender, Worktree wt, Runnable next) {
    if (wt.table().isEmpty()) {
      next.run();
      return;
    }
    boolean started = wt.save(author(sender), "autosave", Commit.AUTO, r -> next.run(), e -> {
      bg.platform().log().log(Level.WARNING, "自動存檔失敗", e);
      done(sender);
      msg().send(sender, "save-failed", "error", String.valueOf(e.getMessage()));
    });
    if (!started) {
      done(sender);
      msg().send(sender, "save-busy");
    }
  }

  /** 玩家的選取範圍換成版本中的維度與範圍。 */
  private Workspace.Scope selection(ServerPlayer p) {
    var sel = bg.selections().get(p);
    if (sel.isEmpty()) {
      msg().send(s(p), "sel-none");
      return null;
    }
    return new Workspace.Scope(bg.workspace(sel.get().level()).logicalDimension(sel.get().level()), sel.get().box());
  }

  /** 在玩家目前的世界標出外框；外框在背景算，主執行緒只送出。 */
  private void outline(Sender sender, Collection<Workspace.Changed> changed) {
    var ws = ws(sender);
    if (!(sender.player() instanceof ServerPlayer p)) return;
    var level = p.level();
    String dimension = ws.logicalDimension(level);
    ws.async(() -> {
      var shapes = Outlines.shapes(changed, dimension);
      ws.sync(() -> {
        if (p.level() == level) bg.outlines().show(p, level, shapes, OUTLINE_SECONDS);
      });
    });
  }

  /** diff 的結果；玩家的後面接著關閉外框的按鈕。 */
  private void sendDiff(Sender sender, Component text) {
    sender.send(msg().prefixed(sender.isPlayer() ? text.append(Component.text(" ")).append(button("diff-off-button", "/bg diff off")) : text));
  }

  private Component button(String key, String command) {
    return msg().get(key).clickEvent(ClickEvent.runCommand(command));
  }

  private void sendButton(Sender sender, String key, String command) {
    if (sender.isPlayer()) sender.send(button(key, command));
    else msg().send(sender, "console-confirm", "command", command);
  }

  private static String pos(BlockPos p) {
    return p.x() + " " + p.y() + " " + p.z();
  }

  // ---- 基本 ----

  /** 玩家所在的世界開始受版本控制；主控台為預設世界。 */
  private void init(Sender sender) {
    var ws = ws(sender);
    var p = sender.player();
    var world = p != null ? p.level() : bg.platform().server().overworld();
    if (ws.tracked(world)) {
      msg().send(sender, "already-initialized", "world", bg.platform().levelName(world));
      return;
    }
    String name = bg.platform().levelName(world);
    try {
      var copy = ws.init(p != null ? bg.tracker().author(p) : bg.tracker().author(new UUID(0, 0), null), world);
      if (copy == null) {
        msg().send(sender, "init-lazy", "world", name);
        return;
      }
      msg().send(sender, "init-started", "world", name);
      progress(sender, copy.progress, "progress-init", "world", name);
      copy.whenDone(size -> ws.sync(() -> {
        done(sender);
        msg().send(sender, "init-done", "world", name, "size", size / (1024 * 1024));
      }), error -> ws.sync(() -> {
        done(sender);
        msg().send(sender, "init-failed", "error", String.valueOf(error.getMessage()));
      }));
    } catch (Workspace.Refused e) {
      msg().send(sender, "init-failed", "error", e.getMessage());
    } catch (IOException e) {
      bg.platform().log().log(Level.SEVERE, "init 失敗", e);
      msg().send(sender, "init-failed", "error", e.getMessage());
    }
  }

  private void status(Sender sender) throws IOException {
    var ws = ws(sender);
    var repo = ws.repo();
    var wt = current(sender);
    msg().send(sender, "status-header", "branch", branchLabel(wt.branch()), "head", repo.head(wt.branch()).abbrev());
    var table = wt.table();
    if (table.isEmpty()) msg().send(sender, "status-clean");
    else {
      msg().send(sender, "status-pending", "cells", table.cellCount(), "entities", table.entityCount());
      table.cellsByAuthor().forEach((a, n) -> msg().send(sender, "status-author", "author", a.name(), "cells", n));
    }
    if (wt.uncleanShutdown()) msg().send(sender, "status-unclean");
    for (var s : ws.merges().all())
      msg().send(sender, "merge-entry", "id", s.id(), "source", s.source() == null ? s.base().abbrev() : s.source(), "target", s.target());
  }

  private void save(Sender sender, String message) {
    msg().send(sender, "save-start");
    var wt = current(sender);
    progress(sender, wt.progress(), "progress-save");
    boolean started = wt.save(author(sender), message.isBlank() ? "save" : message, 0,
        r -> {
          done(sender);
          if (r.commit().isEmpty()) msg().send(sender, "save-nothing");
          else {
            msg().send(sender, "save-done", "id", r.commit().get().abbrev(), "cells", r.cells(), "entities", r.entities());
            if (r.biomes() > 0) msg().send(sender, "biomes-changed", "count", r.biomes());
          }
        },
        e -> {
          done(sender);
          bg.platform().log().log(Level.WARNING, "存檔失敗", e);
          msg().send(sender, "save-failed", "error", String.valueOf(e.getMessage()));
        });
    if (!started) {
      done(sender);
      msg().send(sender, "save-busy");
    }
  }

  /** {@code log [分支] [頁數] [--all] [--author <玩家>]}：預設只列里程碑，自動存檔收合成一行；分頁顯示。 */
  private void log(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var repo = ws.repo();
    String branch = current(sender).branch();
    int page = 1;
    boolean all = false;
    String author = null;
    for (int i = 0; i < args.length; i++) {
      var a = args[i];
      if (a.isEmpty()) continue;
      if (a.equals("--all")) all = true;
      else if (a.equals("--author") && i + 1 < args.length) author = args[++i];
      else if (a.chars().allMatch(Character::isDigit)) page = Math.min(200, Math.max(1, parseInt(a, 1)));
      else if (repo.refs().get(a).isPresent()) branch = a;
    }
    var head = repo.head(branch);
    final int p = page;
    final boolean showAll = all;
    final String who = author;
    var command = "/bg log " + branch + "%s" + (all ? " --all" : "") + (author != null ? " --author " + author : "");
    bg.platform().async(() -> {
      // 每一行（存檔或收合的自動存檔）各算一列；多收一列判斷有沒有下一頁
      var lines = new ArrayList<Component>();
      int want = p * PAGE + 1;
      try {
        int folded = 0;
        for (var id : repo.history(head, 5000)) {
          if (lines.size() >= want) break;
          var c = repo.commit(id);
          if (c.parents().isEmpty()) continue; // 初始化的空提交
          if (who != null && !wrote(repo, c, who)) continue;
          if (!showAll && c.auto()) {
            folded++;
            continue;
          }
          if (folded > 0) lines.add(msg().get("log-folded", "count", folded));
          folded = 0;
          lines.add(msg().get("log-entry", "id", commitLink(id), "time", TIME.format(Instant.ofEpochMilli(c.time())), "author", c.author().name(),
              "message", c.auto() ? msg().get("log-auto") : Component.text(c.message()), "coauthors", coauthors(repo, c)));
        }
        if (folded > 0 && lines.size() < want) lines.add(msg().get("log-folded", "count", folded));
      } catch (IOException | RuntimeException e) {
        failed(sender, e);
        return;
      }
      showPage(sender, lines, p, command);
    });
  }

  /** 每頁幾列：玩家聊天室窗沒打開時只顯示 10 行，留一行給翻頁。 */
  private static final int PAGE = 8;

  /** 可點的存檔編號：點了就是 /bg show。 */
  private Component commitLink(ObjectId id) {
    return msg().get("commit-link", "id", id.abbrev()).clickEvent(ClickEvent.runCommand("/bg show " + id.abbrev()));
  }

  /**
   * 顯示第 {@code page} 頁並附上翻頁按鈕。{@code lines} 是從第一頁開始的所有列，最多多一列（用來判斷有沒有下一頁）。
   *
   * @param command 翻頁指令，{@code %s} 換成「 頁數」
   */
  private void showPage(Sender sender, List<Component> lines, int page, String command) {
    int from = (page - 1) * PAGE;
    if (lines.size() <= from) {
      if (lines.isEmpty()) msg().send(sender, "log-empty");
      else msg().send(sender, "page-none", "page", page);
      return;
    }
    var prefix = msg().get("prefix");
    for (var line : lines.subList(from, Math.min(lines.size(), page * PAGE))) sender.send(prefix.append(line));
    boolean more = lines.size() > page * PAGE;
    if (page == 1 && !more) return;
    var prev = page > 1 ? msg().get("page-prev").clickEvent(ClickEvent.runCommand(command.formatted(" " + (page - 1)))) : Component.empty();
    var next = more ? msg().get("page-next").clickEvent(ClickEvent.runCommand(command.formatted(" " + (page + 1)))) : Component.empty();
    msg().send(sender, "page-footer", "page", page, "prev", prev, "next", next);
  }

  /** 所有草稿的分支線用等寬字型畫，才會對齊。 */
  private static final net.kyori.adventure.key.Key MONO = net.kyori.adventure.key.Key.key("minecraft", "uniform");

  /** {@code graph [頁數] [--all]}：像 git log --graph 的分支歷史圖，分頁顯示；預設略過自動存檔。 */
  private void graph(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    boolean all = flags(args).contains("--all");
    var w = words(args);
    int page = Math.min(200, Math.max(1, w.isEmpty() ? 1 : parseInt(w.getFirst(), 1)));
    // 分支線要從最上面一路排下來才正確，所以每頁都從頭算，只顯示這一頁；多算一列判斷有沒有下一頁
    int limit = page * PAGE + 1;
    var heads = new LinkedHashMap<ObjectId, List<String>>();
    for (var e : ws.drafts().entrySet()) heads.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
    var repo = ws.repo();
    bg.platform().async(() -> {
      List<Graph.Row> rows;
      try {
        rows = Graph.build(repo, heads.keySet(), c -> !c.parents().isEmpty() && (all || !c.auto()), limit);
      } catch (IOException | RuntimeException e) {
        failed(sender, e);
        return;
      }
      var lines = new ArrayList<Component>();
      for (var r : rows) {
        var refs = Component.text();
        for (var name : heads.getOrDefault(r.id(), List.of())) refs.append(Component.space()).append(msg().get("graph-ref", "branch", branchLabel(name)));
        var c = r.commit();
        lines.add(msg().get("graph-entry", "graph", Component.text(r.graph()).font(MONO), "id", commitLink(r.id()), "refs", refs.build(),
            "time", TIME.format(Instant.ofEpochMilli(c.time())), "author", c.author().name(),
            "message", c.auto() ? msg().get("log-auto") : Component.text(c.message())));
      }
      showPage(sender, lines, page, "/bg graph%s" + (all ? " --all" : ""));
    });
  }

  /** 存檔裡改過東西、但不是存檔者的玩家（最多列 3 位）；沒有時是空的。 */
  private Component coauthors(Repository repo, Commit c) throws IOException {
    var names = new LinkedHashSet<String>();
    for (var a : repo.changeset(c.changeset()).authors())
      if (!a.uuid().equals(c.author().uuid()) && !a.equals(Author.MERGE) && !a.equals(Author.SYSTEM)) names.add(a.name());
    if (names.isEmpty()) return Component.empty();
    var shown = Component.join(net.kyori.adventure.text.JoinConfiguration.separator(msg().get("log-coauthors-separator")),
        names.stream().limit(3).map(Component::text).toList());
    return names.size() > 3
        ? msg().get("log-coauthors-more", "names", shown, "count", names.size())
        : msg().get("log-coauthors", "names", shown);
  }

  private boolean wrote(Repository repo, Commit c, String name) throws IOException {
    if (c.author().name().equalsIgnoreCase(name)) return true;
    for (var a : repo.changeset(c.changeset()).authors()) if (a.name().equalsIgnoreCase(name)) return true;
    return false;
  }

  private void show(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var repo = ws.repo();
    boolean listCells = flags(args).contains("--cells");
    var w = words(args);
    var id = w.isEmpty() ? repo.head(current(sender).branch()) : resolve(ws, w.getFirst());
    if (id == null) {
      msg().send(sender, "unknown-commit", "id", w.getFirst());
      return;
    }
    // 變更紀錄可能很大，在背景讀
    record Shown(Commit commit, Changeset changeset) {}
    CompletableFuture.supplyAsync(() -> {
      try {
        var c = repo.commit(id);
        return new Shown(c, repo.changeset(c.changeset()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, ws::async).whenComplete((r, error) -> ws.sync(() -> {
      if (error != null) failed(sender, Workspace.unwrap(error));
      else show(sender, id, r.commit(), r.changeset(), listCells);
    }));
  }

  private void show(Sender sender, ObjectId id, Commit c, Changeset cs, boolean listCells) {
    var ws = ws(sender);
    msg().send(sender, "show-header", "id", id.abbrev(), "author", c.author().name(), "time", TIME.format(Instant.ofEpochMilli(c.time())),
        "message", c.message());
    cs.cellsByAuthor().forEach((a, n) -> msg().send(sender, "show-line", "author", a.name(), "cells", n));
    if (!cs.entities().isEmpty()) msg().send(sender, "show-entities", "entities", cs.entities().size());
    if (!cs.biomes().isEmpty()) msg().send(sender, "biomes-changed", "count", cs.biomes().size());
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

  /**
   * {@code diff}：未存的變更；{@code diff <存檔>}：那次存檔；{@code diff <a> <b>}：兩個版本之間。在世界裡顯示外框。
   * {@code diff off}：清掉外框。
   */
  private void diff(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var w = words(args);
    var repo = ws.repo();
    if (w.size() == 1 && w.getFirst().equals("off")) {
      if (sender.player() instanceof ServerPlayer p) bg.outlines().clear(p);
      msg().send(sender, "diff-off");
      return;
    }
    if (w.isEmpty()) {
      var table = current(sender).table();
      ws.async(() -> {
        var changed = new ArrayList<Workspace.Changed>();
        table.markedSections().forEach((key, cells) -> {
          for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1))
            changed.add(new Workspace.Changed(key.dimension(), key.pos(i), Workspace.Kind.MODIFIED));
        });
        ws.sync(() -> {
          sendDiff(sender, msg().get("diff-unsaved", "cells", changed.size()));
          outline(sender, changed);
        });
      });
      return;
    }
    var a = resolve(ws, w.getFirst());
    var b = w.size() > 1 ? resolve(ws, w.get(1)) : null;
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
    record Counted(List<Workspace.Changed> changed, int[] counts, int entities, int biomes) {}
    CompletableFuture.supplyAsync(() -> {
      try {
        var diff = TreeDiff.collect(repo.overlays(), from, to);
        var changed = new ArrayList<Workspace.Changed>();
        var cache = new HashMap<dev.twme.buildgit.core.model.SectionKey, dev.twme.buildgit.core.model.Cell[]>();
        int[] counts = new int[3];
        for (var c : diff.cells()) {
          var k = ws.kind(c.dimension(), c.pos(), c.before(), c.after(), cache);
          counts[k.ordinal()]++;
          changed.add(new Workspace.Changed(c.dimension(), c.pos(), k));
        }
        for (var bc : diff.biomes()) changed.add(new Workspace.Changed(bc.key().dimension(), bc.corner(), Workspace.Kind.BIOME));
        return new Counted(changed, counts, diff.entities().size(), diff.biomes().size());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, ws::async).whenComplete((r, error) -> ws.sync(() -> {
      if (error != null) {
        failed(sender, Workspace.unwrap(error));
        return;
      }
      sendDiff(sender, msg().get("diff-summary", "added", r.counts()[0], "removed", r.counts()[1], "modified", r.counts()[2], "entities", r.entities()));
      if (r.biomes() > 0) msg().send(sender, "biomes-outlined", "count", r.biomes());
      outline(sender, r.changed());
    }));
  }

  private void mark(Sender sender, String[] args) {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    Box box;
    if (w.size() == 1 && w.getFirst().equalsIgnoreCase("sel")) {
      var sel = bg.selections().get(p);
      if (sel.isEmpty()) {
        msg().send(sender, "sel-none");
        return;
      }
      if (sel.get().level() != p.level()) {
        msg().send(sender, "mark-sel-elsewhere", "world", bg.platform().levelName(sel.get().level()));
        return;
      }
      box = sel.get().box();
    } else {
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
      box = Box.of(new BlockPos(v[0], v[1], v[2]), new BlockPos(v[3], v[4], v[5]));
    }
    long volume = (long) (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1);
    if (volume > MAX_MARK) {
      msg().send(sender, "mark-too-large", "max", MAX_MARK);
      return;
    }
    if (!bg.tracker().isTracked(p.level())) {
      msg().send(sender, "inspect-untracked");
      return;
    }
    bg.tracker().markBox(p.level(), box, bg.tracker().author(p));
    msg().send(sender, "mark-done", "cells", volume);
    bg.outlines().show(p, p.level(), List.of(new Outlines.Shape(box, Outlines.MODIFIED)), OUTLINE_SECONDS);
  }

  /** 第一個參數可以是 {@code sel}；每個座標都補上選取範圍（WorldEdit 或 /bg sel）對應的值，按 Tab 就能一路填完。 */
  private List<String> suggestMark(Sender sender, List<String> before) {
    var p = sender.player();
    if (p == null || before.size() >= 6 || !before.isEmpty() && before.getFirst().equalsIgnoreCase("sel")) return List.of();
    var result = new ArrayList<String>();
    if (before.isEmpty()) result.add("sel");
    var sel = bg.selections().get(p);
    if (sel.isPresent() && sel.get().level() == p.level()) {
      var b = sel.get().box();
      int[] v = {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
      result.add(Integer.toString(v[before.size()]));
    }
    return result;
  }

  private void sel(Sender sender, String[] args) {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    var what = w.isEmpty() ? "show" : w.getFirst();
    switch (what) {
      case "pos1", "pos2" -> {
        var block = target(p, 8);
        var at = block != null ? block : Workspace.pos(p);
        bg.selections().set(p, what.equals("pos1") ? 0 : 1, at);
        msg().send(s(p), "sel-set", "corner", what, "pos", pos(at));
      }
      case "clear" -> {
        bg.selections().clear(p);
        bg.outlines().clear(p);
        msg().send(s(p), "sel-cleared");
      }
      default -> {
        var sel = bg.selections().get(p);
        if (sel.isEmpty()) {
          msg().send(s(p), "sel-none");
          return;
        }
        var b = sel.get().box();
        msg().send(s(p), "sel-current", "world", bg.platform().levelName(sel.get().level()), "from", b.minX() + " " + b.minY() + " " + b.minZ(),
            "to", b.maxX() + " " + b.maxY() + " " + b.maxZ());
        if (sel.get().level() == p.level())
          bg.outlines().show(p, p.level(), List.of(new Outlines.Shape(b, Outlines.MODIFIED)), OUTLINE_SECONDS);
      }
    }
  }

  private void who(Sender sender) {
    for (var p : bg.platform().server().getPlayerList().getPlayers()) {
      var ws = bg.workspace(p.level());
      var wt = ws.worktree(p.level());
      var view = ws.view(p.level());
      Component where = wt != null ? branchLabel(wt.branch())
          : view != null ? msg().get("place-view", "id", view.commit().abbrev())
          : msg().get("place-other", "world", bg.platform().levelName(p.level()));
      msg().send(sender, "who-entry", "player", p.getScoreboardName(), "place", where);
    }
  }

  /** 玩家看著的方塊（不含流體）；沒有時為 null。 */
  private static BlockPos target(ServerPlayer p, double distance) {
    var hit = p.pick(distance, 1f, false);
    if (hit.getType() != HitResult.Type.BLOCK) return null;
    var b = ((BlockHitResult) hit).getBlockPos();
    return new BlockPos(b.getX(), b.getY(), b.getZ());
  }

  // ---- 檢查 ----

  private void toggleInspect(Sender sender) {
    var p = player(sender);
    if (p == null) return;
    if (inspecting.remove(p.getUUID())) msg().send(s(p), "inspect-off");
    else {
      inspecting.add(p.getUUID());
      msg().send(s(p), "inspect-on");
    }
  }

  private void blame(Sender sender) {
    var p = player(sender);
    if (p == null) return;
    var block = target(p, 10);
    if (block == null) msg().send(s(p), "blame-no-target");
    else describe(p, p.level(), block);
  }

  /**
   * 檢查模式下玩家點了方塊（主手）。回傳 true 表示已處理，平台要取消這次互動。
   * 右鍵看放置面那一格（例如剛放下的方塊旁邊），左鍵看點到的那一格。
   */
  public boolean inspectClick(ServerPlayer p, net.minecraft.core.BlockPos clicked, Direction face, boolean right) {
    if (!inspecting.contains(p.getUUID())) return false;
    var b = right ? clicked.relative(face) : clicked;
    describe(p, p.level(), new BlockPos(b.getX(), b.getY(), b.getZ()));
    return true;
  }

  /** 玩家在檢查模式中（Fabric 用來連副手的點擊一起擋下）。 */
  public boolean isInspecting(ServerPlayer p) {
    return inspecting.contains(p.getUUID());
  }

  void quit(UUID player) {
    inspecting.remove(player);
  }

  private void describe(ServerPlayer player, ServerLevel world, BlockPos at) {
    var ws = bg.workspace(world);
    var view = ws.view(world);
    var tracker = bg.tracker();
    var d = tracker.dimension(world);
    String pos = at.x() + " " + at.y() + " " + at.z();
    if (d == null && view == null) {
      msg().send(s(player), "inspect-untracked");
      return;
    }
    if (d != null) {
      var pending = d.authorAt(at.x(), at.y(), at.z());
      if (pending != null) {
        msg().send(s(player), "inspect-pending", "pos", pos, "author", pending.name());
        return;
      }
    }
    var repo = ws.repo();
    var dim = ws.logicalDimension(world);
    var wt = ws.worktree(world);
    bg.platform().async(() -> {
      try {
        var start = view != null ? view.commit() : repo.head(wt.branch());
        var blame = repo.blame(start, dim, at);
        String time = blame.isEmpty() ? "" : TIME.format(Instant.ofEpochMilli(repo.commit(blame.get().commit()).time()));
        ws.sync(() -> {
          if (blame.isEmpty()) {
            msg().send(s(player), "inspect-none", "pos", pos);
            return;
          }
          var b = blame.get();
          msg().send(s(player), "inspect-blame", "pos", pos, "author", b.author().name(), "id", b.commit().abbrev(), "time", time,
              "kind", msg().get(kind(b.kind())));
        });
      } catch (IOException ex) {
        bg.platform().log().log(Level.WARNING, "blame 失敗", ex);
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
  private void tryDraft(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var w = words(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-try");
      return;
    }
    String name = w.getFirst().toLowerCase(Locale.ROOT);
    var from = current(sender);
    if (Workspace.isScratch(from.branch())) from = ws.worktree(ws.source(from.branch()));
    if (from == null) from = ws.main();
    if (name.equals(Repository.MAIN) || Workspace.isScratch(name) || !Workspace.BRANCH_NAME.matcher(name).matches()) {
      msg().send(sender, "branch-bad-name");
      return;
    }
    var source = from;
    String dimension = draftWorld(sender, source.branch());
    if (dimension == null) return;
    autosaveThen(sender, source, () -> {
      try {
        boolean exists = ws.branches().containsKey(name);
        if (!exists) ws.createBranch(name, source.branch(), dimension);
        msg().send(sender, exists ? "try-existing" : "try-created", "name", name);
        var draft = ws.openBranch(name);
        if (sender.player() instanceof ServerPlayer p) ws.teleport(p, draft);
      } catch (IOException | RuntimeException e) {
        bg.platform().log().log(Level.WARNING, "try 失敗", e);
        failed(sender, e);
      }
    });
  }

  /**
   * 新草稿所屬的世界：玩家所在的世界；主控台沿用來源草稿的世界（來源是主世界時為預設世界）。
   * 不受版本控制的世界回傳 null 並告知。
   */
  private String draftWorld(Sender sender, String from) throws IOException {
    var ws = ws(sender);
    var dim = here(sender);
    if (dim == null) dim = ws.dimension(from);
    if (dim == null) dim = dev.twme.buildgit.server.track.Tracker.dimensionId(ws.mainWorlds().getFirst());
    if (!ws.trackedDimensions().contains(dim)) {
      msg().send(sender, "inspect-untracked");
      return null;
    }
    return dim;
  }

  // ---- 與單人世界同步（設計 §6.7） ----

  /**
   * {@code pull [名稱]}：把範圍送到自己的用戶端。有選取範圍就用它；沒有時在草稿裡取草稿改過的範圍，
   * 其他時候取玩家周圍（{@code sync.radius}）。沒給名稱時用草稿名稱或玩家名稱，用過就加編號。
   */
  private void pull(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    String given = w.size() == 1 ? w.getFirst().toLowerCase(Locale.ROOT) : null;
    if (w.size() > 1 || given != null && !Frame.NAME.matcher(given).matches()) {
      msg().send(s(p), "usage-pull");
      return;
    }
    if (!bg.platform().canSync(p)) {
      msg().send(s(p), "sync-no-client");
      return;
    }
    var sel = bg.selections().get(p);
    var level = sel.isPresent() ? sel.get().level() : p.level();
    var wt = ws.worktree(level);
    if (wt == null) {
      msg().send(s(p), "sync-not-branch");
      return;
    }
    if (ws.buildWorld() != null) throw new Workspace.Refused("建築世界還不能帶到單人世界");
    var selected = sel.isPresent() ? sel.get().box() : null;
    if (selected != null && Bundle.volume(selected) > bg.settings().syncMaxBlocks()) {
      msg().send(s(p), "sync-too-big", "max", bg.settings().syncMaxBlocks());
      return;
    }
    String dimension = ws.logicalDimension(level);
    if (ws.copying(dimension)) throw new Workspace.Refused("這個世界的起點世界還在背景複製，請稍後再試");
    String base = given != null ? given : wt != ws.main() ? wt.branch() : p.getScoreboardName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    String name = given != null ? given : bg.sync().freeName(p, base);
    int px = p.getBlockX(), pz = p.getBlockZ(), minY = level.getMinY(), maxY = level.getMaxY();
    autosaveThen(sender, wt, () -> {
      if (selected != null) {
        bg.sync().pull(p, name, wt.branch(), dimension, selected);
        return;
      }
      ws.async(() -> {
        try {
          var changed = ws.changedArea(wt.branch(), dimension);
          int r = bg.settings().syncRadius(), m = 8;
          var box = changed == null ? new Box(px - r, minY, pz - r, px + r, maxY, pz + r)
              : new Box(changed.minX() - m, Math.max(minY, changed.minY() - m), changed.minZ() - m,
                  changed.maxX() + m, Math.min(maxY, changed.maxY() + m), changed.maxZ() + m);
          ws.sync(() -> {
            if (Bundle.volume(box) > bg.settings().syncMaxBlocks()) {
              msg().send(s(p), changed == null ? "sync-too-big" : "sync-draft-too-big", "max", bg.settings().syncMaxBlocks());
              return;
            }
            bg.sync().pull(p, name, wt.branch(), dimension, box);
          });
        } catch (IOException | RuntimeException e) {
          ws.sync(() -> failed(sender, e));
        }
      });
    });
  }

  /** {@code push <名稱> [草稿名稱]}：收回單人世界改過的範圍，存成草稿。 */
  private void push(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    String name = w.isEmpty() ? "" : w.getFirst().toLowerCase(Locale.ROOT);
    if (w.size() > 2 || !Frame.NAME.matcher(name).matches()) {
      msg().send(s(p), "usage-push");
      return;
    }
    String draft = w.size() == 2 ? w.get(1).toLowerCase(Locale.ROOT) : name;
    // 沒指定草稿名稱：同一個範圍可能已經送回過，加上編號
    for (int i = 2; w.size() == 1 && ws.branches().containsKey(draft); i++) draft = name + "-" + i;
    if (draft.equals(Repository.MAIN) || Workspace.isScratch(draft) || !Workspace.BRANCH_NAME.matcher(draft).matches()) {
      msg().send(s(p), "branch-bad-name");
      return;
    }
    if (!bg.platform().canSync(p)) {
      msg().send(s(p), "sync-no-client");
      return;
    }
    bg.sync().push(p, name, draft);
  }

  /** {@code load <名稱>}（單人世界）：把取出的範圍放進目前的世界。 */
  private void load(Sender sender, String[] args) throws IOException {
    var folder = bg.platform().syncFolder();
    if (folder == null) {
      msg().send(sender, "sync-load-singleplayer");
      return;
    }
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    String name = w.size() == 1 ? w.getFirst().toLowerCase(Locale.ROOT) : "";
    if (!Frame.NAME.matcher(name).matches()) {
      msg().send(s(p), "usage-load");
      return;
    }
    bg.localSync().load(p, name, folder);
  }

  private void go(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-go");
      return;
    }
    String name = w.getFirst().toLowerCase(Locale.ROOT);
    if (!ws.drafts().containsKey(name)) {
      msg().send(s(p), "branch-unknown", "name", name);
      return;
    }
    ws.teleport(p, ws.openBranch(name));
    msg().send(s(p), "go-done", "branch", branchLabel(name));
  }

  private void back(Sender sender) throws IOException {
    var ws = ws(sender);
    var p = player(sender);
    if (p == null) return;
    var world = p.level();
    if (ws.view(world) != null) {
      var session = ws.merges().of(world);
      var saved = returnTo.remove(p.getUUID());
      if (session != null) ws.teleport(p, ws.openBranch(session.branch()));
      else if (saved != null && ws.loaded(saved.level()))
        bg.platform().teleport(p, saved.level(), saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
      else ws.teleport(p, ws.main());
      return;
    }
    var wt = ws.worktree(world);
    if (wt == null || wt.branch().equals(Repository.MAIN)) {
      msg().send(s(p), "not-in-draft");
      return;
    }
    String source = ws.source(wt.branch());
    ws.teleport(p, ws.openBranch(source));
    msg().send(s(p), "go-done", "branch", branchLabel(source));
  }

  /** {@code view <版本>}：唯讀地看某個版本（設計 §6.5）。 */
  private void view(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.size() != 1) {
      msg().send(s(p), "usage-view");
      return;
    }
    var id = resolve(ws, w.getFirst());
    if (id == null) {
      msg().send(s(p), "unknown-commit", "id", w.getFirst());
      return;
    }
    if (ws.view(p.level()) == null) returnTo.put(p.getUUID(), new Location(p.level(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
    var world = ws.openView(id, ws.logicalDimension(p.level()));
    ws.teleport(p, world);
    msg().send(s(p), "view-opened", "id", id.abbrev());
  }

  // ---- 併回（設計 §6.2） ----

  /**
   * 玩家：{@code publish [--selection] [confirm|cancel]}；主控台：{@code publish <草稿> [confirm|cancel]}。
   * 不帶 confirm 時只預覽：數量、作者，並在世界裡顯示會變動的位置；cancel 不併回，清掉預覽的外框。
   */
  private void publish(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var flags = flags(args);
    var w = words(args);
    boolean cancel = w.size() > (sender.isPlayer() ? 0 : 1) && w.getLast().equals("cancel");
    if (cancel) w.removeLast();
    Worktree draft;
    if (sender.player() instanceof ServerPlayer p) {
      draft = ws.worktree(p.level());
      if (draft != null && Workspace.isScratch(draft.branch())) {
        msg().send(s(p), "merge-use-continue");
        return;
      }
    } else draft = w.size() == 1 && ws.drafts().containsKey(w.getFirst()) ? ws.openBranch(w.getFirst()) : null;
    if (draft == null || draft.branch().equals(Repository.MAIN)) {
      msg().send(sender, "not-in-draft");
      return;
    }
    if (cancel) {
      // 預覽的外框：執行的人，以及草稿裡一起看預覽的玩家
      if (sender.player() instanceof ServerPlayer p) bg.outlines().clear(p);
      for (var world : draft.worlds().values()) for (var p : world.players()) bg.outlines().clear(p);
      msg().send(sender, "publish-cancelled");
      return;
    }
    Workspace.Scope scope = null;
    if (flags.contains("--selection")) {
      var p = player(sender);
      if (p == null || (scope = selection(p)) == null) return;
    }
    String targetBranch = ws.source(draft.branch());
    var target = ws.openBranch(targetBranch);
    var existing = ws.merges().find(targetBranch, draft.branch());
    if (existing != null) {
      msg().send(sender, "merge-existing", "id", existing.id());
      sendButton(sender, "merge-goto-button", "/bg merge view result");
      return;
    }
    var finalScope = scope;
    var d = draft;
    boolean confirm = flags.contains("confirm");
    msg().send(sender, confirm ? "publish-start" : "publish-preview-start", "name", draft.branch(), "target", branchLabel(targetBranch));
    progress(sender, confirm ? target.progress() : null, confirm ? "progress-publish" : "progress-preview", "name", draft.branch());
    autosaveThen(sender, draft, () -> autosaveThen(sender, target, () -> {
      if (confirm) merge(sender, target, d.branch(), finalScope, d);
      else preview(sender, target, d.branch(), finalScope, flags.contains("--selection"));
    }));
  }

  private void preview(Sender sender, Worktree target, String source, Workspace.Scope scope, boolean selection) {
    var ws = target.workspace();
    ws.preview(target.branch(), source, scope).whenComplete((preview, error) -> ws.sync(() -> {
      done(sender);
      if (error != null) {
        failed(sender, Workspace.unwrap(error));
        return;
      }
      if (preview.upToDate()) {
        msg().send(sender, "publish-nothing");
        return;
      }
      // 草稿裡的其他玩家也看得到同一份預覽，方便一起討論；確認按鈕只給執行的人。
      var viewers = new ArrayList<Sender>(List.of(sender));
      var draft = ws.worktree(source);
      if (draft != null)
        for (var world : draft.worlds().values())
          for (var p : world.players())
            if (p != sender.player()) {
              viewers.add(s(p));
              msg().send(s(p), "publish-preview-shared", "player", author(sender).name(), "name", source);
            }
      int conflicts = preview.result().cellConflicts().size() + preview.result().entityConflicts().size();
      for (var viewer : viewers) {
        msg().send(viewer, "publish-preview", "cells", preview.cells(), "entities", preview.entities(), "target", branchLabel(target.branch()));
        preview.authors().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
            .forEach(e -> msg().send(viewer, "publish-preview-author", "author", e.getKey(), "cells", e.getValue()));
        if (preview.biomes() > 0) msg().send(viewer, "biomes-outlined", "count", preview.biomes());
        if (conflicts > 0) msg().send(viewer, "publish-preview-conflicts", "count", conflicts);
        if (!preview.result().biomeConflicts().isEmpty())
          msg().send(viewer, "publish-preview-biome-conflicts", "count", preview.result().biomeConflicts().size(), "target", branchLabel(target.branch()));
        outline(viewer, preview.changed());
      }
      String command = "/bg publish " + (sender.isPlayer() ? "" : source + " ") + (selection ? "--selection " : "") + "confirm";
      if (sender.isPlayer())
        sender.send(button("publish-confirm-button", command).append(Component.text(" ")).append(button("publish-cancel-button", "/bg publish cancel")));
      else sendButton(sender, "publish-confirm-button", command);
    }));
  }

  /** 合併並寫入目標世界；有衝突時開始衝突整合。{@code draft} 不為 null 時，完成後把在草稿裡的玩家送回目標世界。 */
  private void merge(Sender sender, Worktree target, String source, Workspace.Scope scope, Worktree draft) {
    var ws = target.workspace();
    ws.merge(target, source, scope, author(sender), applied -> {
      done(sender);
      if (applied.isEmpty()) {
        msg().send(sender, "publish-nothing");
        return;
      }
      var a = applied.get();
      msg().send(sender, "publish-done", "id", a.commit().abbrev(), "cells", a.cells(), "entities", a.entities(), "target",
          branchLabel(target.branch()));
      if (a.biomes() > 0) msg().send(sender, "biomes-changed", "count", a.biomes());
      if (draft != null && sender.player() instanceof ServerPlayer p && !p.hasDisconnected() && ws.worktree(p.level()) == draft) {
        ws.teleport(p, target);
        if (scope == null && draft.branch().equals(source) && !source.startsWith(Workspace.SUBMIT_PREFIX))
          sendButton(s(p), "draft-delete-button", "/bg branch delete " + source);
      }
      // 整份併回的提出：等傳送完再收掉
      if (source.startsWith(Workspace.SUBMIT_PREFIX)) bg.platform().later(40, () -> bg.submissions().settle(ws));
    }, error -> {
      done(sender);
      if (error instanceof Workspace.ConflictException c)
        startSession(ws, sender, scope == null ? MergeSessions.Kind.MERGE : MergeSessions.Kind.PARTIAL, target.branch(), source, scope, c);
      else {
        bg.platform().log().log(Level.WARNING, "併回失敗", error);
        failed(sender, error);
      }
    });
  }

  private void startSession(Workspace ws, Sender sender, MergeSessions.Kind kind, String target, String source, Workspace.Scope scope,
      Workspace.ConflictException c) {
    progress(sender, null, "progress-merge-start");
    ws.merges().start(kind, target, source, scope, c, author(sender), session -> {
      done(sender);
      msg().send(sender, "merge-started", "cells", c.result.cellConflicts().size(), "entities", c.result.entityConflicts().size());
      msg().send(sender, "merge-help");
      if (!(sender.player() instanceof ServerPlayer p)) {
        listConflicts(ws, sender, session);
        return;
      }
      try {
        // 外框要等玩家到了整合世界才顯示（換世界時會清掉外框）。
        ws.teleport(p, ws.openBranch(session.branch())).thenRun(() -> ws.sync(() -> listConflicts(ws, s(p), session)));
      } catch (IOException e) {
        failed(s(p), e);
      }
    }, e -> {
      bg.platform().log().log(Level.WARNING, "無法開始衝突整合", e);
      failed(sender, e);
    });
  }

  // ---- 分支 ----

  /** 版本庫與 BuildGit 建立的世界各占多少磁碟（背景執行緒計算）。 */
  /** {@code /bg genesis upgrade}：把起點世界裡較舊版本存的 chunk 改寫成目前的格式。 */
  private void genesis(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    if (args.length != 1 || !args[0].equals("upgrade")) {
      msg().send(sender, "usage-genesis");
      return;
    }
    var task = ws.upgradeGenesis();
    msg().send(sender, "genesis-upgrade-started");
    progress(sender, task.progress(), "progress-genesis-upgrade");
    task.done().thenAccept(count -> ws.sync(() -> {
      done(sender);
      msg().send(sender, "genesis-upgrade-done", "files", count[0], "chunks", count[1]);
      if (count[2] > 0) msg().send(sender, "genesis-upgrade-failed", "count", count[2]);
    }));
  }

  /**
   * {@code /bg backup [status|now [工作]|list [工作]|restore <工作> [快照]|fetch <分支> [新名稱]] [world=<世界>]}：備份工作
   * （設計 §2.5～§2.7）。{@code world=} 指定建築世界（03-worlds §6.3），沒給是伺服器世界。
   */
  private void backup(Sender sender, String[] args) {
    String world = null;
    var rest = new ArrayList<String>();
    for (var a : args)
      if (a.startsWith("world=")) world = a.substring(6);
      else rest.add(a);
    args = rest.toArray(String[]::new);
    var ws = bg.workspace();
    var backup = bg.backup();
    String action = args.length == 0 ? "status" : args[0];
    int max = switch (action) {
      case "restore", "fetch" -> 3;
      case "now", "list" -> 2;
      default -> 1;
    };
    if (args.length > max || !List.of("status", "now", "list", "restore", "fetch").contains(action)
        || List.of("restore", "fetch").contains(action) && args.length < 2) {
      msg().send(sender, "usage-backup");
      return;
    }
    if (backup.jobs().isEmpty()) {
      msg().send(sender, "backup-disabled", "file", Backup.FILE);
      return;
    }
    var jobs = backup.jobs();
    if (List.of("now", "list", "restore").contains(action) && args.length > 1) {
      var job = backup.job(args[1]);
      if (job == null) {
        msg().send(sender, "backup-unknown-job", "job", args[1], "jobs", String.join(", ", jobs.stream().map(j -> j.name).toList()));
        return;
      }
      jobs = List.of(job);
    }
    if (world != null && !List.of("list", "restore", "fetch").contains(action)) {
      msg().send(sender, "usage-backup");
      return;
    }
    // 還沒有版本庫（新主機）或開不了時，只能看狀態、列快照、還原伺服器世界（不必先 /bg init）
    if (!ws.isOpen() && !(List.of("status", "list").contains(action) && world == null || action.equals("restore") && world == null)) {
      if (bg.openError() != null) msg().send(sender, "open-failed", "error", bg.openError());
      else msg().send(sender, "not-initialized");
      return;
    }
    Workspace target = null;
    if (world != null && !action.equals("restore")) {
      target = bg.worlds().find(world, sender.isPlayer() ? sender.player().getUUID() : null);
      if (target == null) {
        msg().send(sender, "world-unknown", "world", world);
        return;
      }
      if (target.buildWorld() == null) target = null;
    }
    if (world != null && action.equals("restore")) {
      if (sender.isPlayer()) {
        msg().send(sender, "backup-restore-console");
        return;
      }
      var job = jobs.getFirst();
      msg().send(sender, "backup-restore-started");
      backup.restoreWorld(job, world, args.length > 2 ? args[2] : null).whenComplete((p, e) -> ws.sync(() -> {
        try {
          if (e != null) throw Workspace.unwrap(e);
          var live = bg.worlds().byId(p.entry().id());
          if (live != null) bg.realms().evictAll(live, "world-restoring", "world", live.label());
          bg.worlds().restore(p).whenComplete((w, e2) -> ws.sync(() -> {
            if (e2 != null) msg().send(sender, "backup-restore-failed", "error", String.valueOf(Workspace.unwrap(e2).getMessage()));
            else msg().send(sender, "backup-restore-world-done", "world", w.label(), "snapshot", p.result().snapshot(), "objects",
                p.result().objects(), "files", p.result().files());
          }));
        } catch (Throwable t) {
          msg().send(sender, "backup-restore-failed", "error", String.valueOf(t.getMessage()));
        }
      }));
      return;
    }
    switch (action) {
      case "status" -> {
        for (var job : jobs) {
          if (job.store == null) {
            msg().send(sender, "backup-status-unset", "job", job.name, "problem", job.problem);
            continue;
          }
          String when = (job.schedule == null ? "" : job.schedule.toString())
              + (job.idleMinutes == 0 ? "" : (job.schedule == null ? "" : " + ") + "idle " + job.idleMinutes + "m");
          msg().send(sender, job.what == Backup.What.HISTORY ? "backup-status-history" : "backup-status-world", "job", job.name,
              "target", job.target + "/" + job.prefix, "when", when, "keep", job.keep);
          long last = job.lastSuccess();
          if (last > 0) msg().send(sender, "backup-last", "time", TIME.format(java.time.Instant.ofEpochMilli(last)));
          else msg().send(sender, "backup-never");
          if (job.lastError != null) msg().send(sender, "backup-last-error", "error", job.lastError);
          if (!job.incomplete.isEmpty()) msg().send(sender, "backup-incomplete", "dims", String.join(", ", job.incomplete));
        }
      }
      case "now" -> {
        for (var job : jobs) {
          if (job.store == null) continue;
          msg().send(sender, "backup-started", "job", job.name);
          backup.now(job).whenComplete((r, e) -> ws.sync(() -> {
            if (e != null) msg().send(sender, "backup-failed", "job", job.name, "error", String.valueOf(Workspace.unwrap(e).getMessage()));
            else if (r.snapshot() == null) msg().send(sender, "backup-unchanged", "job", job.name);
            else if (job.what == Backup.What.HISTORY)
              msg().send(sender, "backup-done", "job", job.name, "snapshot", r.snapshot(), "objects", r.objects(), "files", r.files());
            else msg().send(sender, "backup-world-done", "job", job.name, "snapshot", r.snapshot(), "files", r.files(), "kept", r.kept(),
                "skipped", r.skipped().size());
            if (e == null && !job.incomplete.isEmpty()) msg().send(sender, "backup-incomplete", "dims", String.join(", ", job.incomplete));
          }));
        }
      }
      case "fetch" -> {
        var into = target != null ? target : ws;
        String branch = args[1], name = args.length > 2 ? args[2] : args[1];
        try {
          if (!Workspace.BRANCH_NAME.matcher(name).matches() || Workspace.isScratch(name)) throw new Workspace.Refused("名稱只能用小寫英數、底線、連字號：" + name);
          if (into.branches().containsKey(name)) throw new Workspace.Refused("已經有分支 " + name + "：請給一個新名稱，例如 /bg backup fetch " + branch + " " + branch + "-backup");
        } catch (IOException e) {
          msg().send(sender, "backup-fetch-failed", "error", String.valueOf(e.getMessage()));
          return;
        }
        msg().send(sender, "backup-fetch-started", "branch", branch);
        backup.fetch(into, branch).whenComplete((r, e) -> into.sync(() -> {
          try {
            if (e != null) throw Workspace.unwrap(e);
            String from = into.branches().containsKey(r.source()) ? r.source() : Repository.MAIN;
            into.createBranch(name, from, r.head(), false, r.dimension());
            msg().send(sender, "backup-fetch-done", "branch", name, "snapshot", r.snapshot(), "objects", r.objects());
          } catch (Throwable t) {
            msg().send(sender, "backup-fetch-failed", "error", String.valueOf(t.getMessage()));
          }
        }));
      }
      case "restore" -> {
        if (sender.isPlayer()) {
          msg().send(sender, "backup-restore-console");
          return;
        }
        var job = jobs.getFirst();
        msg().send(sender, "backup-restore-started");
        backup.restore(job, args.length > 2 ? args[2] : null).whenComplete((r, e) -> ws.sync(() -> {
          if (e != null) {
            msg().send(sender, "backup-restore-failed", "error", String.valueOf(Workspace.unwrap(e).getMessage()));
            return;
          }
          for (var w : r.warnings()) msg().send(sender, "backup-restore-warning", "warning", w);
          msg().send(sender, job.what == Backup.What.HISTORY ? "backup-restore-ready" : "backup-restore-world-ready", "snapshot", r.snapshot(),
              "objects", r.objects(), "files", r.files(), "folder", Restore.staging(bg.platform().repoDir()).getFileName().toString());
        }));
      }
      default -> {
        for (var job : jobs) {
          if (job.store == null) continue;
          var list = target == null ? backup.snapshots(job)
              : job.what == Backup.What.HISTORY ? backup.snapshots(job, target.buildWorld().id()) : null;
          if (list == null) continue;
          list.whenComplete((names, e) -> ws.sync(() -> {
            if (e != null) {
              msg().send(sender, "backup-failed", "job", job.name, "error", String.valueOf(Workspace.unwrap(e).getMessage()));
              return;
            }
            msg().send(sender, "backup-list-header", "job", job.name, "count", names.size());
            for (var n : names.subList(Math.max(0, names.size() - 10), names.size())) msg().send(sender, "backup-list-entry", "snapshot", n);
          }));
        }
      }
    }
  }

  private void disk(Sender sender) throws IOException {
    var ws = ws(sender);
    var dir = ws.dir();
    var objectsDir = ws.objectsDir();
    var genesisRoot = ws.genesisRoot();
    var dims = ws.trackedDimensions();
    var branches = new ArrayList<>(ws.branches().keySet());
    branches.remove(Repository.MAIN);
    var platform = bg.platform();
    platform.async(() -> {
      try {
        long objects = folderSize(objectsDir);
        long genesis = folderSize(genesisRoot);
        var root = dir.toAbsolutePath().normalize();
        long total = folderSize(dir) + (genesisRoot.toAbsolutePath().normalize().startsWith(root) ? 0 : genesis)
            + (objectsDir.toAbsolutePath().normalize().startsWith(root) ? 0 : objects);
        var lines = new ArrayList<Component>();
        lines.add(msg().get("disk-entry", "name", msg().get("disk-objects"), "size", megabytes(objects)));
        lines.add(msg().get("disk-entry", "name", msg().get("disk-genesis"), "size", megabytes(genesis)));
        lines.add(msg().get("disk-entry", "name", msg().get("disk-other"), "size", megabytes(total - objects - genesis)));
        for (var branch : branches) {
          long size = 0;
          for (var dim : dims) size += folderSize(platform.levelFolder(ws.worldName(branch, dim)));
          total += size;
          lines.add(msg().get("disk-entry", "name", branchLabel(branch), "size", megabytes(size)));
        }
        long views = 0;
        for (var name : platform.levelFolders())
          if (name.startsWith(ws.viewPrefix())) views += folderSize(platform.levelFolder(name));
        total += views;
        if (views > 0) lines.add(msg().get("disk-entry", "name", msg().get("disk-views"), "size", megabytes(views)));
        msg().send(sender, "disk-header", "size", megabytes(total));
        lines.forEach(sender::send);
      } catch (IOException | RuntimeException e) {
        failed(sender, e);
      }
    });
  }

  /** 資料夾裡所有檔案的大小；計算途中消失的檔案略過。 */
  private static long folderSize(java.nio.file.Path folder) throws IOException {
    if (!java.nio.file.Files.isDirectory(folder)) return 0;
    long[] sum = {0};
    java.nio.file.Files.walkFileTree(folder, new java.nio.file.SimpleFileVisitor<>() {
      @Override
      public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
        sum[0] += attrs.size();
        return java.nio.file.FileVisitResult.CONTINUE;
      }

      @Override
      public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path file, IOException e) {
        return java.nio.file.FileVisitResult.CONTINUE;
      }
    });
    return sum[0];
  }

  private static String megabytes(long bytes) {
    return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
  }

  private void branch(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var w = words(args);
    var what = w.isEmpty() ? "list" : w.getFirst().toLowerCase(Locale.ROOT);
    switch (what) {
      case "list" -> {
        if (!sender.hasPermission("buildgit.branch.list")) {
          msg().send(sender, "no-permission");
          return;
        }
        var dim = flags(args).contains("--all") ? null : here(sender);
        for (var e : ws.drafts().entrySet()) {
          if (dim != null && !ws.covers(e.getKey(), dim)) continue;
          var wt = ws.worktree(e.getKey());
          int players = 0;
          if (wt != null) for (var world : wt.worlds().values()) players += world.players().size();
          msg().send(sender, "branch-entry", "branch", branchLabel(e.getKey()), "head", e.getValue().abbrev(),
              "time", TIME.format(Instant.ofEpochMilli(ws.repo().commit(e.getValue()).time())), "players", players);
        }
        for (var s : ws.merges().all())
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
        String dimension = draftWorld(sender, from);
        if (dimension == null) return;
        ws.createBranch(name, from, dimension);
        msg().send(sender, "branch-created", "name", name, "from", branchLabel(from));
      }
      case "delete" -> {
        if (!sender.hasPermission("buildgit.branch.delete")) {
          msg().send(sender, "no-permission");
          return;
        }
        if (w.size() < 2 || w.get(1).equals(Repository.MAIN) || !ws.drafts().containsKey(w.get(1))) {
          msg().send(sender, "branch-unknown", "name", w.size() < 2 ? "" : w.get(1));
          return;
        }
        ws.deleteBranch(w.get(1), flags(args).contains("--force"));
        msg().send(sender, "branch-deleted", "name", w.get(1));
      }
      default -> msg().send(sender, "usage-branch");
    }
  }

  // ---- 合併與衝突整合（設計 §5.2、§5.3） ----

  private MergeSessions.Session session(Sender sender, List<String> w) {
    var ws = ws(sender);
    var merges = ws.merges();
    MergeSessions.Session s = null;
    if (sender.player() instanceof ServerPlayer p) s = merges.of(p.level());
    if (s == null && w.size() > 1) s = merges.get(w.getLast());
    if (s == null && merges.all().size() == 1) s = merges.all().iterator().next();
    if (s == null) msg().send(sender, "merge-none");
    return s;
  }

  private boolean mayResolve(Sender sender) {
    if (anyPermission(sender, "buildgit.merge.resolve|buildgit.merge|buildgit.publish|buildgit.revert")) return true;
    msg().send(sender, "no-permission");
    return false;
  }

  private void merge(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var w = words(args);
    if (w.isEmpty()) {
      msg().send(sender, "usage-merge");
      return;
    }
    var what = w.getFirst();
    switch (what) {
      case "list" -> {
        if (!mayResolve(sender)) return;
        if (ws.merges().all().isEmpty()) msg().send(sender, "merge-none");
        for (var s : ws.merges().all())
          msg().send(sender, "merge-entry", "id", s.id(), "source", s.source() == null ? s.base().abbrev() : s.source(), "target", s.target());
      }
      case "view" -> {
        var p = player(sender);
        if (p == null || !mayResolve(s(p))) return;
        var s = session(s(p), w);
        if (s == null) return;
        var side = w.size() > 1 ? w.get(1) : "result";
        var dim = ws.logicalDimension(p.level());
        switch (side) {
          case "ours" -> ws.teleport(p, ws.openView(s.ours(), dim));
          case "theirs" -> ws.teleport(p, ws.openView(s.theirs(), dim));
          default -> ws.teleport(p, ws.openBranch(s.branch()));
        }
        msg().send(s(p), "merge-view-" + (side.equals("ours") || side.equals("theirs") ? side : "result"), "target", s.target(), "source",
            String.valueOf(s.source()));
      }
      case "conflicts" -> {
        if (!mayResolve(sender)) return;
        var s = session(sender, w);
        if (s != null) listConflicts(ws, sender, s);
      }
      case "goto" -> {
        var p = player(sender);
        if (p == null || !mayResolve(s(p))) return;
        var s = session(s(p), List.of());
        if (s == null) return;
        int n = w.size() > 1 ? parseInt(w.get(1), 1) : 1;
        ws.merges().result(s).whenComplete((result, error) -> ws.sync(() -> {
          if (error != null) {
            failed(s(p), Workspace.unwrap(error));
            return;
          }
          var clusters = MergeSessions.clusters(result);
          if (n > clusters.size()) {
            msg().send(s(p), "merge-no-conflicts");
            return;
          }
          var c = clusters.get(n - 1);
          try {
            var world = ws.openBranch(s.branch()).world(c.dimension());
            if (world == null) return;
            var b = c.box();
            bg.platform().teleport(p, world, (b.minX() + b.maxX()) / 2.0 + .5, b.maxY() + 1, (b.minZ() + b.maxZ()) / 2.0 + .5, p.getYRot(), p.getXRot())
                .thenRun(() -> ws.sync(() -> showConflicts(p, result, clusters)));
          } catch (IOException e) {
            failed(s(p), e);
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
        var range = w.size() > 2 ? w.get(2) : sender.isPlayer() ? "here" : "all";
        var p = sender.player() instanceof ServerPlayer player ? player : null;
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
            var at = Workspace.pos(p);
            yield new Workspace.Scope(ws.logicalDimension(p.level()), new Box(at.x() - HERE_RADIUS, at.y() - HERE_RADIUS, at.z() - HERE_RADIUS,
                at.x() + HERE_RADIUS, at.y() + HERE_RADIUS, at.z() + HERE_RADIUS));
          }
        };
        if (range.equals("selection") && scope == null) return;
        var side = w.get(1).equals("ours") ? MergeSessions.Side.OURS : MergeSessions.Side.THEIRS;
        ws.merges().take(s, side, scope, author(sender), n -> msg().send(sender, "merge-taken", "count", n, "side", w.get(1)),
            e -> failed(sender, e));
      }
      case "continue" -> {
        if (!mayResolve(sender)) return;
        var s = session(sender, w);
        if (s == null) return;
        msg().send(sender, "merge-finishing");
        progress(sender, ws.openBranch(s.target()).progress(), "progress-merge-finish");
        ws.merges().finish(s, author(sender), applied -> {
          done(sender);
          if (applied.isEmpty()) msg().send(sender, "merge-finished-empty", "target", branchLabel(s.target()));
          else {
            var a = applied.get();
            msg().send(sender, "publish-done", "id", a.commit().abbrev(), "cells", a.cells(), "entities", a.entities(), "target",
                branchLabel(s.target()));
            if (a.biomes() > 0) msg().send(sender, "biomes-changed", "count", a.biomes());
            if (s.source() != null && s.source().startsWith(Workspace.SUBMIT_PREFIX)) bg.platform().later(40, () -> bg.submissions().settle(ws));
          }
        }, e -> {
          done(sender);
          if (e instanceof Workspace.ConflictException c)
            msg().send(sender, "merge-target-moved", "count", c.result.cellConflicts().size() + c.result.entityConflicts().size());
          else failed(sender, e);
        });
      }
      case "abort" -> {
        if (!mayResolve(sender)) return;
        var s = session(sender, w);
        if (s == null) return;
        ws.merges().discard(s);
        msg().send(sender, "merge-aborted");
      }
      default -> {
        // merge <分支>：把它合併進目前所在的分支（底層指令）。
        if (!sender.hasPermission("buildgit.merge")) {
          msg().send(sender, "no-permission");
          return;
        }
        var target = current(sender);
        if (Workspace.isScratch(target.branch()) || ws.repo().refs().get(what).isEmpty() || what.equals(target.branch())) {
          msg().send(sender, "branch-unknown", "name", what);
          return;
        }
        if (ws.merges().find(target.branch(), what) != null) {
          msg().send(sender, "merge-existing", "id", ws.merges().find(target.branch(), what).id());
          return;
        }
        var source = ws.worktree(what);
        msg().send(sender, "publish-start", "name", what, "target", branchLabel(target.branch()));
        Runnable go = () -> autosaveThen(sender, target, () -> merge(sender, target, what, null, null));
        if (source != null) autosaveThen(sender, source, go);
        else go.run();
      }
    }
  }

  private void listConflicts(Workspace ws, Sender sender, MergeSessions.Session s) {
    ws.merges().result(s).whenComplete((result, error) -> ws.sync(() -> {
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
        if (sender.isPlayer()) sender.send(line.clickEvent(ClickEvent.runCommand("/bg merge goto " + (i + 1))));
        else sender.send(line);
      }
      if (sender.player() instanceof ServerPlayer p) showConflicts(p, result, clusters);
    }));
  }

  /** 在玩家目前的世界標出衝突格；太多時改標每一群的包圍盒。 */
  private void showConflicts(ServerPlayer p, dev.twme.buildgit.core.overlay.ThreeWayMerge.Result result, List<MergeSessions.Cluster> clusters) {
    var ws = bg.workspace(p.level());
    String dim = ws.logicalDimension(p.level());
    var shapes = new ArrayList<Outlines.Shape>();
    for (var c : result.cellConflicts())
      if (c.dimension().equals(dim)) shapes.add(new Outlines.Shape(new Box(c.pos().x(), c.pos().y(), c.pos().z(), c.pos().x(), c.pos().y(), c.pos().z()), Outlines.CONFLICT));
    if (shapes.size() > 400) {
      shapes.clear();
      for (var c : clusters) if (c.dimension().equals(dim)) shapes.add(new Outlines.Shape(c.box(), Outlines.CONFLICT));
    }
    bg.outlines().show(p, p.level(), shapes, OUTLINE_SECONDS * 5);
  }

  // ---- 還原（設計 §5.4） ----

  private void revert(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var w = words(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-revert");
      return;
    }
    var id = resolve(ws, w.getFirst());
    if (id == null) {
      msg().send(sender, "unknown-commit", "id", w.getFirst());
      return;
    }
    var wt = current(sender);
    if (Workspace.isScratch(wt.branch())) {
      msg().send(sender, "merge-use-continue");
      return;
    }
    progress(sender, wt.progress(), "progress-revert");
    autosaveThen(sender, wt, () -> ws.revert(wt, id, author(sender), applied -> {
      done(sender);
      if (applied.isEmpty()) msg().send(sender, "revert-nothing");
      else {
        var a = applied.get();
        msg().send(sender, "revert-done", "id", a.commit().abbrev(), "reverted", id.abbrev(), "cells", a.cells(), "entities", a.entities());
        if (a.biomes() > 0) msg().send(sender, "biomes-changed", "count", a.biomes());
      }
    }, e -> {
      done(sender);
      if (e instanceof Workspace.ConflictException c) startSession(ws, sender, MergeSessions.Kind.REVERT, wt.branch(), null, null, c);
      else failed(sender, e);
    }));
  }

  private void restore(Sender sender, String[] args) throws IOException {
    var ws = ws(sender);
    var w = words(args);
    var flags = flags(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-restore");
      return;
    }
    var id = resolve(ws, w.getFirst());
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
    progress(sender, wt.progress(), "progress-restore");
    autosaveThen(sender, wt, () -> ws.restore(wt, id, finalScope, author(sender), applied -> {
      done(sender);
      if (applied.isEmpty()) msg().send(sender, "restore-nothing");
      else {
        var a = applied.get();
        msg().send(sender, "restore-done", "id", a.commit().abbrev(), "target", id.abbrev(), "cells", a.cells(), "entities", a.entities());
        if (a.biomes() > 0) msg().send(sender, "biomes-changed", "count", a.biomes());
      }
    }, e -> failed(sender, e)));
  }

  // ---- 建築世界（03-worlds） ----

  /** 這個人進得去的世界（主控台是全部）。 */
  private List<String> worldNames(Sender sender) {
    var names = new ArrayList<String>();
    names.add(bg.worlds().serverWorldName());
    for (var ws : visibleWorlds(sender)) names.add(ws.label());
    return names;
  }

  private List<Workspace> visibleWorlds(Sender sender) {
    var all = bg.worlds().buildWorlds();
    if (!(sender.player() instanceof ServerPlayer p)) return List.copyOf(all);
    return all.stream().filter(ws -> bg.realms().canEnter(p, ws)).toList();
  }

  private List<String> onlineNames() {
    return bg.platform().server().getPlayerList().getPlayers().stream().map(ServerPlayer::getScoreboardName).toList();
  }

  private List<String> memberNames(Sender sender) {
    var config = ws(sender).config();
    return config == null ? List.of() : config.members.values().stream().map(WorldConfig.Member::name).toList();
  }

  /** {@code new <名稱>}：建立自己的建築世界並進去。 */
  /**
   * {@code new [名稱] [type=…] [terrain=…] [rules=…] [seed=…] [layers=…] [block=…] [confirm]}：沒有 {@code confirm} 時打開建立選單，
   * 選單裡點選就是帶著改過的選項再執行一次；按「建立」才真的建立並傳送進去。
   */
  private void newWorld(Sender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var o = parseNew(sender, args);
    if (o == null) return;
    var pw = bg.settings().playerWorlds();
    boolean admin = sender.hasPermission("buildgit.world.admin");
    if (!flags(args).contains("confirm")) {
      // 預設的類型、地形沒開放時，選單從開放的第一個開始
      if (!admin && o.template() == null && !pw.allowed(o.type()))
        o = o.withType(Arrays.stream(WorldConfig.Type.values()).filter(pw::allowed).findFirst().orElse(o.type()));
      if (!admin && o.template() == null && !pw.allowed(o.terrain())) {
        var type = o.type();
        o = o.withTerrain(Arrays.stream(WorldConfig.Terrain.values()).filter(t -> t != WorldConfig.Terrain.SERVER && pw.allowed(t)
            && (type == WorldConfig.Type.OVERWORLD || t != WorldConfig.Terrain.LARGE_BIOMES && t != WorldConfig.Terrain.AMPLIFIED)).findFirst().orElse(o.terrain()));
      }
      bg.menu().openCreate(p, o);
      return;
    }
    if (o.template() != null) {
      var spec = o.template();
      var template = bg.menu().templates().stream().filter(t -> t.label().equals(spec)).findFirst()
          .orElseThrow(() -> new Workspace.Refused("沒有叫 " + spec + " 的範本"));
      forked(sender, p, bg.worlds().fork(template, o.name(), p, admin, true));
      return;
    }
    if (!admin && !pw.allowed(o.type())) throw new Workspace.Refused("這台伺服器沒有開放 " + WorldConfig.key(o.type()) + " 類型");
    if (!admin && !pw.allowed(o.terrain())) throw new Workspace.Refused("這台伺服器沒有開放 " + WorldConfig.key(o.terrain()) + " 地形");
    var ws = bg.worlds().create(o.name(), p, newConfig(o), sender.hasPermission("buildgit.world.admin"));
    msg().send(sender, "world-created", "world", ws.label(), "folder", ws.buildWorld().folder());
    bg.realms().enter(p, ws);
  }

  /**
   * {@code /bg fork <擁有者/名稱> [新名稱]}（03-worlds §5.3）：複製成自己的私人世界，歷史一起帶過來。成員都可以；訪客要擁有者允許複製。
   */
  private void fork(Sender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.isEmpty() || w.size() > 2) {
      msg().send(sender, "usage-fork");
      return;
    }
    var source = bg.worlds().find(w.getFirst(), p.getUUID());
    if (source == null || source.buildWorld() == null || !bg.realms().canEnter(p, source)) {
      msg().send(sender, "world-unknown", "world", w.getFirst());
      return;
    }
    if (!bg.realms().canFork(p, source)) throw new Workspace.Refused("這個世界的擁有者沒有允許複製");
    var name = w.size() == 2 ? w.get(1).toLowerCase(Locale.ROOT) : null;
    msg().send(sender, "world-forking", "world", source.label());
    forked(sender, p, bg.worlds().fork(source, name, p, bg.realms().admin(p), false));
  }

  /** 提出說明的字數上限。 */
  private static final int SUBMIT_MESSAGE_MAX = 120;

  /**
   * {@code /bg submit [說明]}（03-worlds §5.4）：在 fork 的世界把所在分支（主世界或草稿）最後一次存檔送回來源；再提出一次就更新
   * 同一個提出。fork 的擁有者與管理者可以用。
   */
  private void submit(Sender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var ws = bg.workspace(p.level());
    if (ws.buildWorld() == null || ws.config() == null) throw new Workspace.Refused("要在 fork 來的建築世界裡才能提出");
    var role = ws.role(p.getUUID());
    if (role != WorldConfig.Role.OWNER && role != WorldConfig.Role.MANAGER && !bg.realms().admin(p))
      throw new Workspace.Refused("只有這個世界的擁有者與管理者能提出");
    var wt = ws.worktree(p.level());
    if (wt == null || Workspace.isScratch(wt.branch())) throw new Workspace.Refused("要在主世界或草稿裡提出");
    var message = String.join(" ", args).strip();
    if (message.codePointCount(0, message.length()) > SUBMIT_MESSAGE_MAX) throw new Workspace.Refused("說明最多 " + SUBMIT_MESSAGE_MAX + " 個字");
    if (message.chars().anyMatch(ch -> ch == '§' || Character.isISOControl(ch))) throw new Workspace.Refused("不能有格式碼或控制字元");
    if (!wt.table().isEmpty()) msg().send(sender, "submit-unsaved");
    msg().send(sender, "submit-sending");
    bg.submissions().submit(p, ws, wt.branch(), message).whenComplete((branch, ex) -> {
      if (ex != null) msg().send(sender, "branch-failed", "error", String.valueOf(Workspace.unwrap(ex).getMessage()));
      else msg().send(sender, "submit-sent", "branch", branch, "world", bg.worlds().byId(ws.config().forkId).label());
    });
  }

  /** {@code /bg world submissions [list|reject <分支> [理由]]}：來源世界的擁有者與管理者處理提出。不帶參數開選單。 */
  private void submissions(Sender sender, ServerPlayer p, Workspace ws, String[] args) throws IOException {
    var subs = bg.submissions();
    subs.settle(ws);
    var list = subs.list(ws);
    var w = words(args);
    switch (w.size() < 2 ? "menu" : w.get(1)) {
      case "menu" -> bg.menu().openSubmissions(p, ws, list);
      case "list" -> {
        if (list.isEmpty()) msg().send(sender, "submissions-none");
        for (var s : list)
          msg().send(sender, "submissions-entry", "branch", s.branch(), "player", s.name(), "time", TIME.format(Instant.ofEpochMilli(s.time())),
              "message", s.message());
      }
      case "reject" -> {
        var s = w.size() >= 3 ? list.stream().filter(x -> x.branch().equals(w.get(2))).findFirst().orElse(null) : null;
        if (s == null) throw new Workspace.Refused("沒有這個提出：" + (w.size() >= 3 ? w.get(2) : ""));
        // 理由是分支名稱後面所有的字（保留大小寫）
        var raw = String.join(" ", args);
        var reason = raw.substring(raw.indexOf(s.branch()) + s.branch().length()).strip();
        subs.reject(ws, s, reason, p.getScoreboardName());
        msg().send(sender, "submissions-rejected", "branch", s.branch(), "player", s.name());
      }
      default -> msg().send(sender, "usage-world");
    }
  }

  /** fork、範本建立完成時（主執行緒）：告訴他並傳送進去。 */
  private void forked(Sender sender, ServerPlayer p, java.util.concurrent.CompletableFuture<Workspace> future) {
    future.whenComplete((ws, ex) -> {
      if (ex != null) {
        msg().send(sender, "branch-failed", "error", String.valueOf(Workspace.unwrap(ex).getMessage()));
        return;
      }
      msg().send(sender, "world-created", "world", ws.label(), "folder", ws.buildWorld().folder());
      if (p.isRemoved()) return;
      try {
        bg.realms().enter(p, ws);
      } catch (IOException e) {
        failed(sender, e);
      }
    });
  }

  /** {@code new} 的選項；種子、層、方塊照輸入的樣子保留，選單點選時原樣帶著走。{@code template} 是範本的 {@code 擁有者/名稱}。 */
  record NewWorld(String name, WorldConfig.Type type, WorldConfig.Terrain terrain, WorldConfig.Rules rules, String seed, String layers, String block,
      String template) {
    NewWorld withType(WorldConfig.Type t) {
      // 大型生態域與巨型化只有主世界類型有
      var keep = t == WorldConfig.Type.OVERWORLD || terrain != WorldConfig.Terrain.LARGE_BIOMES && terrain != WorldConfig.Terrain.AMPLIFIED;
      return new NewWorld(name, t, keep ? terrain : WorldConfig.Terrain.NORMAL, rules, seed, layers, block, null);
    }

    NewWorld withTerrain(WorldConfig.Terrain t) {
      return new NewWorld(name, type, t, rules, seed, layers, block, null);
    }

    NewWorld withRules(WorldConfig.Rules r) {
      return new NewWorld(name, type, terrain, r, seed, layers, block, null);
    }

    NewWorld withTemplate(String t) {
      return new NewWorld(name, type, terrain, rules, seed, layers, block, t);
    }

    /** 再執行一次 {@code new} 用的參數。 */
    String args() {
      var sb = new StringJoiner(" ");
      if (name != null) sb.add(name);
      sb.add("type=" + WorldConfig.key(type)).add("terrain=" + WorldConfig.key(terrain)).add("rules=" + WorldConfig.key(rules));
      if (seed != null) sb.add("seed=" + seed);
      if (layers != null) sb.add("layers=" + layers);
      if (block != null) sb.add("block=" + block);
      if (template != null) sb.add("template=" + template);
      return sb.toString();
    }
  }

  private NewWorld parseNew(Sender sender, String[] args) {
    String name = null, seed = null, layers = null, block = null, template = null;
    var type = WorldConfig.Type.OVERWORLD;
    var terrain = WorldConfig.Terrain.NORMAL;
    var rules = WorldConfig.Rules.BUILD;
    for (var a : words(args)) {
      int eq = a.indexOf('=');
      if (eq < 0) {
        if (name != null) {
          msg().send(sender, "usage-new");
          return null;
        }
        name = a.toLowerCase(Locale.ROOT);
        continue;
      }
      var value = a.substring(eq + 1);
      boolean ok = true;
      switch (a.substring(0, eq).toLowerCase(Locale.ROOT)) {
        case "type" -> ok = (type = WorldConfig.parse(WorldConfig.Type.class, value)) != null;
        case "terrain" -> ok = (terrain = WorldConfig.parse(WorldConfig.Terrain.class, value)) != null && terrain != WorldConfig.Terrain.SERVER;
        case "rules" -> ok = (rules = WorldConfig.parse(WorldConfig.Rules.class, value)) != null;
        case "seed" -> seed = value.isEmpty() ? null : value;
        case "layers" -> layers = value.isEmpty() ? null : value;
        case "block" -> block = value.isEmpty() ? null : value;
        case "template" -> template = value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
        default -> ok = false;
      }
      if (!ok) {
        msg().send(sender, "world-bad-option", "option", a);
        return null;
      }
    }
    return new NewWorld(name, type, terrain, rules, seed, layers, block, template);
  }

  /** 把選項變成建築世界的設定，檢查方塊與生態域存在、層數放得下。 */
  private WorldConfig newConfig(NewWorld o) throws Workspace.Refused {
    if (o.type() != WorldConfig.Type.OVERWORLD && (o.terrain() == WorldConfig.Terrain.LARGE_BIOMES || o.terrain() == WorldConfig.Terrain.AMPLIFIED))
      throw new Workspace.Refused("大型生態域與巨型化只有主世界類型有");
    var c = new WorldConfig();
    c.type = o.type();
    c.terrain = o.terrain();
    c.preset(o.rules());
    if (o.seed() == null) c.seed = new Random().nextLong();
    else {
      try {
        c.seed = Long.parseLong(o.seed());
      } catch (NumberFormatException e) {
        c.seed = o.seed().hashCode(); // 和原版一樣：不是數字就用文字的雜湊
      }
    }
    c.biome = WorldConfig.defaultBiome(c.type, c.terrain);
    if (c.terrain == WorldConfig.Terrain.FLAT) {
      if (o.layers() == null) c.layers = WorldConfig.defaultLayers(c.type);
      else {
        var biome = new StringBuilder();
        try {
          c.layers = WorldConfig.parseLayers(o.layers(), biome);
        } catch (IllegalArgumentException e) {
          throw new Workspace.Refused("層的寫法不對：" + o.layers() + "（例如 minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block;minecraft:plains）");
        }
        if (!biome.isEmpty()) c.biome = biome.toString();
      }
      int height = c.type == WorldConfig.Type.OVERWORLD ? 384 : 256;
      if (c.layers.stream().mapToInt(WorldConfig.Layer::count).sum() > height) throw new Workspace.Refused("層加起來超過世界的高度 " + height);
      for (var l : c.layers) requireBlock(l.block());
    }
    if (c.terrain == WorldConfig.Terrain.VOID) {
      c.block = WorldConfig.id(o.block() == null ? "stone" : o.block());
      requireBlock(c.block);
      if (c.block.equals("minecraft:air")) throw new Workspace.Refused("腳下那一格不能是空氣");
    }
    var biome = net.minecraft.resources.Identifier.tryParse(c.biome);
    if (biome == null || bg.platform().server().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
        .get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, biome)).isEmpty())
      throw new Workspace.Refused("沒有這個生態域：" + c.biome);
    return c;
  }

  private static void requireBlock(String id) throws Workspace.Refused {
    var key = net.minecraft.resources.Identifier.tryParse(id);
    if (key == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(key)) throw new Workspace.Refused("沒有這個方塊：" + id);
  }

  /** {@code open <世界>}：自己的寫名稱，別人的寫「擁有者/名稱」，伺服器世界寫它的名稱。 */
  private void openWorld(Sender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-open");
      return;
    }
    var ws = bg.worlds().find(w.getFirst(), p.getUUID());
    if (ws == null) {
      msg().send(sender, "world-unknown", "world", w.getFirst());
      return;
    }
    if (ws.buildWorld() == null) {
      home(sender);
      return;
    }
    if (!ws.isOpen()) throw new Workspace.Refused("這個建築世界的版本庫無法開啟，請管理員看主控台");
    // 私人世界對不是成員的人當作不存在
    if (!bg.realms().canEnter(p, ws)) {
      msg().send(sender, "world-unknown", "world", w.getFirst());
      return;
    }
    bg.realms().enter(p, ws);
  }

  /** {@code home}：回到進入建築世界之前的位置；不知道時到伺服器主世界的 (0, 0)。 */
  private void home(Sender sender) {
    var p = player(sender);
    if (p == null) return;
    if (bg.realms().home(p)) msg().send(sender, "world-home", "world", bg.worlds().serverWorldName());
  }

  private enum Visibility { PUBLIC, PRIVATE }

  /**
   * {@code world settings …}、{@code world members [list|add <玩家> [builder|manager]|remove <玩家>]}、{@code world visibility public|private}：
   * 管理自己所在的建築世界（03-worlds §3、§4）。擁有者全部可以；管理者能改設定、只能加、移除建造者；管理員都可以。
   */
  private void world(Sender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    var first = w.isEmpty() ? "" : w.getFirst();
    // 成員選單裡點的（設定自己處理 menu）：做完再打開成員選單
    boolean menu = !first.equals("settings") && w.size() > 1 && w.getLast().equals("menu");
    if (menu) w = new ArrayList<>(w.subList(0, w.size() - 1));
    if (first.equals("undelete")) {
      undelete(sender, p, w);
      return;
    }
    if (first.equals("unlist") || first.equals("relist")) {
      unlist(sender, p, w);
      return;
    }
    var ws = bg.workspace(p.level());
    // 伺服器世界（03-worlds §4.2）只有成員與可見性；管理員當擁有者
    boolean server = ws.buildWorld() == null;
    if (server && !bg.realms().inServerWorld(p.level())) throw new Workspace.Refused("要在建築世界或伺服器世界裡才能管理它");
    if (!server && ws.config() == null) throw new Workspace.Refused("這個建築世界的版本庫無法開啟，請管理員看主控台");
    var role = server ? ws.serverRole(p.getUUID()) : ws.role(p.getUUID());
    boolean owner = role == WorldConfig.Role.OWNER || bg.realms().admin(p);
    boolean manager = owner || role == WorldConfig.Role.MANAGER;
    var config = ws.access();
    var what = w.isEmpty() ? (server ? "members" : "") : w.getFirst();
    if (server && !what.equals("members") && !what.equals("visibility"))
      throw new Workspace.Refused("伺服器世界只能設定成員與可見性（/bg world members、/bg world visibility）");
    if (what.equals("settings")) {
      if (!manager) throw new Workspace.Refused("只有擁有者與管理者能改設定");
      settings(sender, p, ws, w);
      return;
    }
    if (what.equals("submissions")) {
      if (!manager) throw new Workspace.Refused("只有擁有者與管理者能處理提出");
      submissions(sender, p, ws, args);
      return;
    }
    if (what.equals("visibility")) {
      var v = w.size() == 2 ? WorldConfig.parse(Visibility.class, w.get(1)) : null;
      if (v == null) {
        msg().send(sender, "usage-world");
        return;
      }
      if (!owner) throw new Workspace.Refused(server ? "只有管理員能改伺服器世界的可見性" : "只有擁有者能改可見性");
      if (config.unlisted && v == Visibility.PUBLIC && !bg.realms().admin(p))
        throw new Workspace.Refused("這個世界被管理員下架了，不能改成公開；請找管理員");
      if (v == Visibility.PRIVATE && server && bg.realms().lobbyProblem() != null)
        throw new Workspace.Refused("伺服器世界要有大廳才能設成私人：" + bg.realms().lobbyProblem());
      if (v == Visibility.PRIVATE && ws == bg.realms().lobbyWorld()) throw new Workspace.Refused("這個世界是大廳，必須是公開的");
      config.open = v == Visibility.PUBLIC;
      ws.saveConfig();
      msg().send(sender, (server ? "server-" : "") + (config.open ? "world-public" : "world-private"), "world", ws.label());
      roleChanged(ws);
      if (menu) bg.menu().openMembers(p, ws);
      return;
    }
    switch (what) {
      case "rename" -> {
        if (w.size() != 2) {
          msg().send(sender, "usage-world");
          return;
        }
        if (!owner) throw new Workspace.Refused("只有擁有者能改名");
        var old = ws.label();
        var e = ws.buildWorld();
        bg.worlds().checkRelocate(ws, w.get(1), e.owner(), e.ownerName());
        bg.realms().evictAll(ws, "world-moving", "world", old);
        bg.worlds().rename(ws, w.get(1)).whenComplete((renamed, ex) -> {
          if (ex != null) msg().send(sender, "branch-failed", "error", String.valueOf(Workspace.unwrap(ex).getMessage()));
          else msg().send(sender, "world-renamed", "old", old, "world", renamed.label());
        });
        return;
      }
      case "transfer" -> {
        if (w.size() != 2) {
          msg().send(sender, "usage-world");
          return;
        }
        if (!flags(args).contains("confirm")) {
          msg().send(sender, "world-transfer-confirm", "world", ws.label(), "name", w.get(1));
          return;
        }
        if (!owner) throw new Workspace.Refused("只有擁有者能轉讓");
        var target = bg.platform().server().getPlayerList().getPlayerByName(w.get(1));
        if (target == null) throw new Workspace.Refused("找不到線上玩家 " + w.get(1) + "（轉讓時對方要在線上）");
        var e = ws.buildWorld();
        if (e.owner().equals(target.getUUID())) throw new Workspace.Refused(target.getScoreboardName() + " 已經是擁有者");
        var pw = bg.settings().playerWorlds();
        long owned = bg.worlds().buildWorlds().stream().filter(o -> o.buildWorld().owner().equals(target.getUUID())).count();
        if (owned >= pw.maxPerPlayer() && !bg.realms().admin(target))
          throw new Workspace.Refused(target.getScoreboardName() + " 已經有 " + owned + " 個建築世界（每人最多 " + pw.maxPerPlayer() + " 個）");
        bg.worlds().checkRelocate(ws, e.name(), target.getUUID(), target.getScoreboardName());
        // 原本的擁有者留下來當管理者
        config.members.remove(target.getUUID());
        config.members.put(e.owner(), new WorldConfig.Member(e.ownerName(), WorldConfig.Role.MANAGER));
        ws.saveConfig();
        var old = ws.label();
        bg.realms().evictAll(ws, "world-moving", "world", old);
        bg.worlds().transfer(ws, target.getUUID(), target.getScoreboardName()).whenComplete((moved, ex) -> {
          if (ex != null) {
            msg().send(sender, "branch-failed", "error", String.valueOf(Workspace.unwrap(ex).getMessage()));
            return;
          }
          msg().send(sender, "world-transferred", "old", old, "world", moved.label(), "name", target.getScoreboardName());
          if (target != p) msg().send(bg.platform().sender(target), "world-received", "world", moved.label(), "from", p.getScoreboardName());
        });
        return;
      }
      case "delete" -> {
        if (!owner) throw new Workspace.Refused("只有擁有者能刪除");
        if (!flags(args).contains("confirm")) {
          msg().send(sender, "world-delete-confirm", "world", ws.label(), "days", bg.settings().playerWorlds().trashDays());
          return;
        }
        var label = ws.label();
        var name = ws.buildWorld().name();
        bg.realms().evictAll(ws, "world-deleted-evict", "world", label);
        bg.worlds().delete(ws).whenComplete((v, ex) -> {
          if (ex != null) msg().send(sender, "branch-failed", "error", String.valueOf(Workspace.unwrap(ex).getMessage()));
          else msg().send(sender, "world-deleted", "world", label, "days", bg.settings().playerWorlds().trashDays(), "name", name);
        });
        return;
      }
      case "members" -> {}
      default -> {
        msg().send(sender, "usage-world");
        return;
      }
    }
    switch (w.size() < 2 ? "menu" : w.get(1)) {
      case "menu" -> bg.menu().openMembers(p, ws);
      case "edit" -> {
        if (!manager) throw new Workspace.Refused("只有擁有者與管理者能調整成員");
        var entry = w.size() == 3 ? member(config, w.get(2)) : null;
        if (entry == null) throw new Workspace.Refused((w.size() == 3 ? w.get(2) : "") + " 不是這個世界的成員");
        bg.menu().openMember(p, ws, entry.getKey());
      }
      case "list" -> {
        msg().send(sender, "members-header", "world", ws.label(), "owner", server ? msg().raw("members-server-owner") : ws.buildWorld().ownerName(),
            "visibility", msg().get(config.open ? "visibility-public" : "visibility-private"));
        for (var m : config.members.values()) msg().send(sender, "members-entry", "name", m.name(), "role", roleName(m.role()));
      }
      case "add" -> {
        var newRole = w.size() == 4 ? WorldConfig.parse(WorldConfig.Role.class, w.get(3)) : WorldConfig.Role.BUILDER;
        if (w.size() < 3 || w.size() > 4 || newRole != WorldConfig.Role.BUILDER && newRole != WorldConfig.Role.MANAGER) {
          msg().send(sender, "usage-world");
          return;
        }
        if (!manager) throw new Workspace.Refused("只有擁有者與管理者能邀人");
        var target = bg.platform().server().getPlayerList().getPlayerByName(w.get(2));
        // 已經是成員的人不在線上也能改角色
        var existing = target == null ? member(config, w.get(2)) : null;
        if (target == null && existing == null) throw new Workspace.Refused("找不到線上玩家 " + w.get(2) + "（邀請時對方要在線上）");
        var uuid = target != null ? target.getUUID() : existing.getKey();
        var name = target != null ? target.getScoreboardName() : existing.getValue().name();
        if (!server && ws.buildWorld().owner().equals(uuid)) throw new Workspace.Refused(name + " 是擁有者");
        var old = config.members.get(uuid);
        if (!owner && (newRole == WorldConfig.Role.MANAGER || old != null && old.role() == WorldConfig.Role.MANAGER))
          throw new Workspace.Refused("管理者只能邀請或移除建造者");
        config.members.put(uuid, new WorldConfig.Member(name, newRole));
        ws.saveConfig();
        msg().send(sender, "members-added", "name", name, "role", roleName(newRole));
        if (target != null && target != p) msg().send(bg.platform().sender(target), server ? "server-members-invited" : "members-invited", "world", ws.label(), "role", roleName(newRole));
        roleChanged(ws);
        if (menu) bg.menu().openMembers(p, ws);
      }
      case "remove" -> {
        if (w.size() != 3) {
          msg().send(sender, "usage-world");
          return;
        }
        if (!manager) throw new Workspace.Refused("只有擁有者與管理者能移除成員");
        var entry = member(config, w.get(2));
        if (entry == null) throw new Workspace.Refused(w.get(2) + " 不是這個世界的成員");
        if (!owner && entry.getValue().role() == WorldConfig.Role.MANAGER) throw new Workspace.Refused("管理者只能邀請或移除建造者");
        config.members.remove(entry.getKey());
        ws.saveConfig();
        msg().send(sender, "members-removed", "name", entry.getValue().name());
        roleChanged(ws);
        if (menu) bg.menu().openMembers(p, ws);
      }
      default -> msg().send(sender, "usage-world");
    }
  }

  private static Map.Entry<UUID, WorldConfig.Member> member(WorldConfig config, String name) {
    return config.members.entrySet().stream().filter(e -> e.getValue().name().equalsIgnoreCase(name)).findFirst().orElse(null);
  }

  /** 顯示名稱、簡介最多幾個字。 */
  private static final int DISPLAY_NAME_MAX = 32, DESCRIPTION_MAX = 120;

  /**
   * {@code world settings [<設定> [值]] [menu]}（03-worlds §3）：沒給設定時打開設定選單；選單裡點一下是帶著 {@code menu} 執行，
   * 改完再打開選單。改了當場套用到已載入的世界（含草稿、檢視世界）。
   */
  private void settings(Sender sender, ServerPlayer p, Workspace ws, List<String> w) throws IOException {
    boolean menu = w.getLast().equals("menu");
    var a = w.subList(1, menu ? w.size() - 1 : w.size());
    if (a.isEmpty()) {
      bg.menu().openSettings(p, ws);
      return;
    }
    var c = ws.config();
    var key = a.getFirst();
    var value = a.size() == 2 ? a.get(1).toLowerCase(Locale.ROOT) : "";
    if (Menu.TEXT_SETTINGS.contains(key) || key.equals("icon")) {
      text(sender, p, ws, key, String.join(" ", a.subList(1, a.size())).strip(), menu);
      return;
    }
    boolean kit = key.startsWith("kit-");
    if (a.size() > 2 || !Menu.SETTINGS.containsKey(key) || (kit ? !value.isEmpty() && !value.equals("clear") : value.isEmpty() != Menu.SETTINGS.get(key).isEmpty())) {
      msg().send(sender, "usage-world-settings");
      return;
    }
    boolean modes = false;
    switch (key) {
      case "time" -> {
        if (value.equals("cycle")) c.time = null;
        else if (Menu.TIMES.containsKey(value)) c.time = Menu.TIMES.get(value);
        else {
          int t = number(value);
          if (t < 0 || t > 23999) throw new Workspace.Refused("時間可以是 " + String.join("、", Menu.SETTINGS.get("time")) + " 或 0～23999 的刻數");
          c.time = t;
        }
      }
      case "weather" -> c.weather = option(WorldConfig.Weather.class, value, key);
      case "difficulty" -> {
        c.difficulty = net.minecraft.world.Difficulty.byName(value);
        if (c.difficulty == null && !value.equals("server")) throw badValue(key);
      }
      case "mobs" -> c.mobs = onOff(value, key);
      case "nature" -> c.nature = onOff(value, key);
      case "member-mode", "visitor-mode" -> {
        var allowed = key.equals("member-mode") ? WorldConfig.MEMBER_MODES : WorldConfig.VISITOR_MODES;
        var mode = net.minecraft.world.level.GameType.byName(value, null);
        if (!allowed.contains(mode)) throw badValue(key);
        if (key.equals("member-mode")) c.memberMode = mode;
        else c.visitorMode = mode;
        modes = true;
      }
      case "visitor-interact" -> c.visitorInteract = onOff(value, key);
      case "allow-fork" -> c.allowFork = onOff(value, key);
      case "accept-submissions" -> c.acceptSubmissions = onOff(value, key);
      case "inventory" -> {
        var pw = bg.settings().playerWorlds();
        if (pw.separateInventories()) throw new Workspace.Refused("伺服器設定所有建築世界都隔離背包，不能關掉");
        if (!pw.allowInventoryIsolation()) throw new Workspace.Refused("伺服器不允許擁有者打開背包隔離");
        c.inventory = onOff(value, key);
        modes = true;
      }
      case "kit-member", "kit-visitor" -> {
        if (!ws.isolated()) throw new Workspace.Refused("要先打開背包隔離（/bg world settings inventory on），才能設定起始物品");
        var file = ws.kitFile(key.equals("kit-visitor"));
        if (value.equals("clear")) java.nio.file.Files.deleteIfExists(file);
        else Inventories.write(file, Inventories.items(p));
      }
      case "border" -> {
        int max = bg.settings().playerWorlds().maxBorder();
        if (value.equals("default")) c.border = 0;
        else {
          int size = number(value);
          if (size < 16 || size > max) throw new Workspace.Refused("邊界的邊長可以是 16～" + max + "，或 default（伺服器的預設）");
          if (c.border == 0) c.borderX = c.borderZ = 0;
          c.border = size;
        }
      }
      case "border-center" -> {
        if (c.border == 0) c.border = ws.borderSize();
        c.borderX = Math.floor(p.getX()) + 0.5;
        c.borderZ = Math.floor(p.getZ()) + 0.5;
      }
      case "spawn" -> c.spawn = new WorldConfig.Spawn(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
      default -> throw new IllegalStateException(key);
    }
    ws.saveConfig();
    ws.applySettings();
    if (modes) bg.realms().roleChanged(ws);
    if (menu) bg.menu().openSettings(p, ws);
    else msg().send(sender, "settings-changed", "setting", msg().get("setting-" + key), "value", bg.menu().settingValue(ws, key));
  }

  /**
   * 清單上的樣子：顯示名稱、簡介是後面所有的字（沒給時清掉；從選單點的提示怎麼打），圖示沒給時是手上的物品。
   * 玩家寫的文字一律當純文字顯示。
   */
  private void text(Sender sender, ServerPlayer p, Workspace ws, String key, String value, boolean menu) throws IOException {
    var c = ws.config();
    if (key.equals("icon")) {
      String id;
      if (value.isEmpty()) {
        var hand = p.getMainHandItem();
        if (hand.isEmpty()) throw new Workspace.Refused("手上沒有東西；拿著要當圖示的物品再點一次，或打 /bg world settings icon <物品>");
        id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
      } else {
        id = WorldConfig.id(value.toLowerCase(Locale.ROOT));
        var key2 = net.minecraft.resources.Identifier.tryParse(id);
        if (key2 == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key2) || id.equals("minecraft:air"))
          throw new Workspace.Refused("沒有這個物品：" + value);
      }
      c.icon = id.equals(Menu.DEFAULT_ICON) ? null : id;
    } else if (value.isEmpty() && menu) {
      // 選單裡沒辦法打字：給一行點了會填好指令的提示
      sender.send(msg().prefixed(msg().get("settings-type-text", "setting", msg().get("setting-" + key)))
          .clickEvent(ClickEvent.suggestCommand("/bg world settings " + key + " ")));
      return;
    } else {
      int max = key.equals("display-name") ? DISPLAY_NAME_MAX : DESCRIPTION_MAX;
      if (value.codePointCount(0, value.length()) > max) throw new Workspace.Refused((key.equals("display-name") ? "顯示名稱" : "簡介") + "最多 " + max + " 個字");
      if (value.chars().anyMatch(ch -> ch == '§' || Character.isISOControl(ch))) throw new Workspace.Refused("不能有格式碼或控制字元");
      var v = value.isEmpty() ? null : value;
      if (key.equals("display-name")) c.displayName = v;
      else c.description = v;
    }
    ws.saveConfig();
    if (menu) bg.menu().openSettings(p, ws);
    else msg().send(sender, "settings-changed", "setting", msg().get("setting-" + key), "value", bg.menu().settingValue(ws, key));
  }

  /** 不是數字時為 -1。 */
  private static int number(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static <E extends Enum<E>> E option(Class<E> type, String value, String key) throws Workspace.Refused {
    var e = WorldConfig.parse(type, value);
    if (e == null) throw badValue(key);
    return e;
  }

  private static boolean onOff(String value, String key) throws Workspace.Refused {
    if (value.equals("on")) return true;
    if (value.equals("off")) return false;
    throw badValue(key);
  }

  private static Workspace.Refused badValue(String key) {
    return new Workspace.Refused(key + " 可以是 " + String.join("、", Menu.SETTINGS.get(key)));
  }

  private net.kyori.adventure.text.Component roleName(WorldConfig.Role role) {
    return msg().get("role-" + WorldConfig.key(role));
  }

  /** 成員或可見性變了：沒資格的人送回去，留下的人換遊戲模式、重送指令清單（權限跟著角色變）。 */
  private void roleChanged(Workspace ws) {
    bg.realms().roleChanged(ws);
    for (var p : bg.platform().server().getPlayerList().getPlayers())
      if (bg.workspace(p.level()) == ws) bg.platform().server().getCommands().sendCommands(p);
  }

  /** {@code world undelete [世界]}：沒給世界時列出自己在回收區裡的世界；管理員可以找回任何人的（寫 {@code 擁有者/名稱}）。 */
  private void undelete(Sender sender, ServerPlayer p, List<String> w) throws IOException {
    boolean admin = bg.realms().admin(p);
    if (w.size() == 1) {
      var mine = bg.worlds().trash().stream().filter(d -> admin || d.entry().owner().equals(p.getUUID())).toList();
      if (mine.isEmpty()) {
        msg().send(sender, "trash-empty");
        return;
      }
      long days = bg.settings().playerWorlds().trashDays();
      for (var d : mine) {
        long left = Math.max(0, Math.ceilDiv(d.time() + days * 86_400_000L - System.currentTimeMillis(), 86_400_000L));
        var spec = admin ? d.entry().label() : d.entry().name();
        var line = msg().prefixed(msg().get("trash-entry", "world", d.entry().label(), "days", left));
        sender.send(line.clickEvent(ClickEvent.runCommand("/bg world undelete " + spec)));
      }
      return;
    }
    if (w.size() != 2) {
      msg().send(sender, "usage-world");
      return;
    }
    var d = bg.worlds().findDeleted(w.get(1), p.getUUID());
    if (d == null || !admin && !d.entry().owner().equals(p.getUUID())) throw new Workspace.Refused("回收區裡沒有 " + w.get(1));
    var ws = bg.worlds().undelete(d);
    msg().send(sender, "world-undeleted", "world", ws.label());
  }

  /** {@code world unlist|relist [世界]}：管理員下架（強制私人並鎖住）或解除；沒給世界時是所在的世界。 */
  private void unlist(Sender sender, ServerPlayer p, List<String> w) throws IOException {
    if (!bg.realms().admin(p)) throw new Workspace.Refused("只有管理員能下架");
    var ws = w.size() == 2 ? bg.worlds().find(w.get(1), p.getUUID()) : bg.workspace(p.level());
    if (w.size() > 2 || ws == null || ws.buildWorld() == null || ws.config() == null) {
      msg().send(sender, "usage-world");
      return;
    }
    boolean unlist = w.getFirst().equals("unlist");
    if (unlist && ws == bg.realms().lobbyWorld()) throw new Workspace.Refused("這個世界是大廳，必須是公開的；先改 config.yml 的 worlds.lobby");
    ws.config().unlisted = unlist;
    if (unlist) ws.config().open = false;
    ws.saveConfig();
    msg().send(sender, unlist ? "world-unlisted" : "world-relisted", "world", ws.label());
    if (unlist) roleChanged(ws);
  }

  private List<String> deletedNames(Sender s) {
    var p = s.player();
    if (p == null) return List.of();
    boolean admin = bg.realms().admin(p);
    return bg.worlds().trash().stream().filter(d -> admin || d.entry().owner().equals(p.getUUID()))
        .map(d -> admin ? d.entry().label() : d.entry().name()).toList();
  }

  /** {@code worlds [mine|public|recent] [頁]}：玩家打開清單選單；主控台列出所有建築世界。 */
  private void worlds(Sender sender, String[] args) {
    if (sender.player() instanceof ServerPlayer p) {
      var w = words(args);
      var tab = w.isEmpty() ? "mine" : w.getFirst();
      if (w.size() > 2 || !Menu.WORLD_TABS.contains(tab)) {
        msg().send(sender, "usage-worlds");
        return;
      }
      bg.menu().openWorlds(p, tab, w.size() == 2 ? Math.max(1, number(w.get(1))) : 1);
      return;
    }
    var all = visibleWorlds(sender);
    if (all.isEmpty()) {
      msg().send(sender, "worlds-none");
      return;
    }
    msg().send(sender, "worlds-header", "count", all.size());
    for (var ws : all) {
      int players = 0;
      boolean loaded = false;
      for (var wt : ws.worktrees())
        for (var level : wt.worlds().values()) {
          loaded = true;
          players += level.players().size();
        }
      var line = msg().prefixed(msg().get("worlds-entry", "world", ws.label(), "folder", ws.buildWorld().folder(), "state",
          msg().get(!ws.isOpen() ? "worlds-broken" : loaded ? "worlds-loaded" : "worlds-unloaded"), "players", players));
      sender.send(sender.isPlayer() ? line.clickEvent(ClickEvent.runCommand("/bg open " + ws.label())) : line);
    }
  }
}
