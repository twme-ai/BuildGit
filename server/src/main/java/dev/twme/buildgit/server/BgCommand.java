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
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private static final int MAX_MARK = 1 << 22;
  private static final int SHOW_CELLS = 200;
  private static final int OUTLINE_SECONDS = 60;
  private static final int HERE_RADIUS = 8;

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
    sub("init", "buildgit.admin", (s, a) -> init(s), null);
    sub("reload", "buildgit.admin", (s, a) -> {
      bg.reload();
      msg().send(s, "reloaded");
    }, null);
    sub("status", "buildgit.view", (s, a) -> status(s), null);
    sub("save", "buildgit.save", (s, a) -> save(s, String.join(" ", a)), null);
    sub("commit", "buildgit.commit", (s, a) -> save(s, String.join(" ", a)), null);
    sub("log", "buildgit.view", this::log, (s, b) -> concat(List.of("--all", "--author"), draftNames()));
    sub("graph", "buildgit.view", this::graph, (s, b) -> List.of("--all"));
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
    sub("pull", "buildgit.sync", this::pull, null);
    sub("push", "buildgit.sync", this::push, (s, b) -> b.isEmpty() && s.player() instanceof ServerPlayer p ? bg.sync().names(p) : List.of());
    sub("load", null, this::load, (s, b) -> b.isEmpty() && bg.platform().syncFolder() != null ? LocalSync.names(bg.platform().syncFolder()) : List.of());
    sub("view", "buildgit.go", this::view, (s, b) -> b.isEmpty() ? recentCommits(s) : List.of());
    sub("publish", "buildgit.publish", this::publish, (s, b) -> concat(List.of("--selection", "confirm"), s.isPlayer() ? List.of() : draftNames()));
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
    return bg.messages();
  }

  private Workspace ws() {
    return bg.workspace();
  }

  // ---- Brigadier ----

  /** @param sender 把平台的指令來源轉成 {@link Sender} */
  public <S> LiteralCommandNode<S> build(Function<S, Sender> sender) {
    var root = LiteralArgumentBuilder.<S>literal("bg").executes(ctx -> {
      var s = sender.apply(ctx.getSource());
      if (s.player() != null && s.hasPermission("buildgit.view") && ws().isOpen()) bg.menu().open(s.player());
      else msg().send(s, "help");
      return 1;
    });
    for (var sub : subs)
      for (var name : sub.names()) {
        var literal = LiteralArgumentBuilder.<S>literal(name)
            .requires(src -> sub.permission() == null || sender.apply(src).hasPermission(sub.permission()))
            .executes(ctx -> dispatch(sub, sender.apply(ctx.getSource()), new String[0]));
        literal.then(RequiredArgumentBuilder.<S, String>argument("args", StringArgumentType.greedyString())
            .suggests((ctx, builder) -> suggest(sub, sender.apply(ctx.getSource()), builder))
            .executes(ctx -> dispatch(sub, sender.apply(ctx.getSource()), StringArgumentType.getString(ctx, "args").trim().split(" +"))));
        root.then(literal);
      }
    return root.build();
  }

  private CompletableFuture<Suggestions> suggest(Sub sub, Sender sender, SuggestionsBuilder builder) {
    if (sub.suggester() == null || !ws().isOpen() && !sub.names().contains("load")) return builder.buildFuture();
    var remaining = builder.getRemaining();
    int space = remaining.lastIndexOf(' ');
    var before = space < 0 ? List.<String>of() : Arrays.asList(remaining.substring(0, space).trim().split(" +"));
    var current = remaining.substring(space + 1).toLowerCase(Locale.ROOT);
    var b = builder.createOffset(builder.getStart() + space + 1);
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
    if (!name.equals("init") && !name.equals("reload") && !name.equals("help") && !name.equals("load") && !ws().isOpen()) {
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
    msg().send(sender, "branch-failed", "error", String.valueOf(e.getMessage()));
  }

  private List<String> draftNames() {
    try {
      return List.copyOf(ws().drafts().keySet());
    } catch (IOException e) {
      return List.of();
    }
  }

  private List<String> recentCommits(Sender sender) {
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
  private Worktree current(Sender sender) {
    var wt = sender.player() instanceof ServerPlayer p ? ws().worktree(p.level()) : null;
    return wt != null ? wt : ws().main();
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
  private void autosaveThen(Sender sender, Worktree wt, Runnable next) {
    if (wt.table().isEmpty()) {
      next.run();
      return;
    }
    boolean started = wt.save(author(sender), "autosave", Commit.AUTO, r -> next.run(), e -> {
      bg.platform().log().log(Level.WARNING, "自動存檔失敗", e);
      msg().send(sender, "save-failed", "error", String.valueOf(e.getMessage()));
    });
    if (!started) msg().send(sender, "save-busy");
  }

  /** 玩家的選取範圍換成版本中的維度與範圍。 */
  private Workspace.Scope selection(ServerPlayer p) {
    var sel = bg.selections().get(p);
    if (sel.isEmpty()) {
      msg().send(s(p), "sel-none");
      return null;
    }
    return new Workspace.Scope(ws().logicalDimension(sel.get().level()), sel.get().box());
  }

  private void outline(Sender sender, Collection<Workspace.Changed> changed) {
    if (sender.player() instanceof ServerPlayer p)
      bg.outlines().show(p, p.level(), Outlines.shapes(changed, ws().logicalDimension(p.level())), OUTLINE_SECONDS);
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
    var p = sender.player();
    var world = p != null ? p.level() : bg.platform().server().overworld();
    if (ws().tracked(world)) {
      msg().send(sender, "already-initialized", "world", bg.platform().levelName(world));
      return;
    }
    String name = bg.platform().levelName(world);
    try {
      var copy = ws().init(p != null ? bg.tracker().author(p) : bg.tracker().author(new UUID(0, 0), null), world);
      msg().send(sender, "init-started", "world", name);
      copy.whenDone(size -> ws().sync(() -> msg().send(sender, "init-done", "world", name, "size", size / (1024 * 1024))),
          error -> ws().sync(() -> msg().send(sender, "init-failed", "error", String.valueOf(error.getMessage()))));
    } catch (Workspace.Refused e) {
      msg().send(sender, "init-failed", "error", e.getMessage());
    } catch (IOException e) {
      bg.platform().log().log(Level.SEVERE, "init 失敗", e);
      msg().send(sender, "init-failed", "error", e.getMessage());
    }
  }

  private void status(Sender sender) throws IOException {
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

  private void save(Sender sender, String message) {
    msg().send(sender, "save-start");
    boolean started = current(sender).save(author(sender), message.isBlank() ? "save" : message, 0,
        r -> {
          if (r.commit().isEmpty()) msg().send(sender, "save-nothing");
          else msg().send(sender, "save-done", "id", r.commit().get().abbrev(), "cells", r.cells(), "entities", r.entities());
        },
        e -> {
          bg.platform().log().log(Level.WARNING, "存檔失敗", e);
          msg().send(sender, "save-failed", "error", String.valueOf(e.getMessage()));
        });
    if (!started) msg().send(sender, "save-busy");
  }

  /** {@code log [分支] [頁數] [--all] [--author <玩家>]}：預設只列里程碑，自動存檔收合成一行；分頁顯示。 */
  private void log(Sender sender, String[] args) throws IOException {
    var repo = ws().repo();
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
          if (who != null && !wrote(c, who)) continue;
          if (!showAll && c.auto()) {
            folded++;
            continue;
          }
          if (folded > 0) lines.add(msg().get("log-folded", "count", folded));
          folded = 0;
          lines.add(msg().get("log-entry", "id", commitLink(id), "time", TIME.format(Instant.ofEpochMilli(c.time())), "author", c.author().name(),
              "message", c.auto() ? msg().get("log-auto") : Component.text(c.message()), "coauthors", coauthors(c)));
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
    boolean all = flags(args).contains("--all");
    var w = words(args);
    int page = Math.min(200, Math.max(1, w.isEmpty() ? 1 : parseInt(w.getFirst(), 1)));
    // 分支線要從最上面一路排下來才正確，所以每頁都從頭算，只顯示這一頁；多算一列判斷有沒有下一頁
    int limit = page * PAGE + 1;
    var heads = new LinkedHashMap<ObjectId, List<String>>();
    for (var e : ws().drafts().entrySet()) heads.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
    var repo = ws().repo();
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
  private Component coauthors(Commit c) throws IOException {
    var names = new LinkedHashSet<String>();
    for (var a : ws().repo().changeset(c.changeset()).authors())
      if (!a.uuid().equals(c.author().uuid()) && !a.equals(Author.MERGE) && !a.equals(Author.SYSTEM)) names.add(a.name());
    if (names.isEmpty()) return Component.empty();
    var shown = Component.join(net.kyori.adventure.text.JoinConfiguration.separator(msg().get("log-coauthors-separator")),
        names.stream().limit(3).map(Component::text).toList());
    return names.size() > 3
        ? msg().get("log-coauthors-more", "names", shown, "count", names.size())
        : msg().get("log-coauthors", "names", shown);
  }

  private boolean wrote(Commit c, String name) throws IOException {
    if (c.author().name().equalsIgnoreCase(name)) return true;
    for (var a : ws().repo().changeset(c.changeset()).authors()) if (a.name().equalsIgnoreCase(name)) return true;
    return false;
  }

  private void show(Sender sender, String[] args) throws IOException {
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
  private void diff(Sender sender, String[] args) throws IOException {
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

  private void mark(Sender sender, String[] args) {
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
    if (!bg.tracker().isTracked(p.level())) {
      msg().send(sender, "inspect-untracked");
      return;
    }
    bg.tracker().markBox(p.level(), box, bg.tracker().author(p));
    msg().send(sender, "mark-done", "cells", volume);
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
      var wt = ws().worktree(p.level());
      var view = ws().view(p.level());
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
    var view = ws().view(world);
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
    var repo = ws().repo();
    var dim = ws().logicalDimension(world);
    var wt = ws().worktree(world);
    bg.platform().async(() -> {
      try {
        var start = view != null ? view.commit() : repo.head(wt.branch());
        var blame = repo.blame(start, dim, at);
        String time = blame.isEmpty() ? "" : TIME.format(Instant.ofEpochMilli(repo.commit(blame.get().commit()).time()));
        ws().sync(() -> {
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
  private void tryDraft(Sender sender, String[] args) {
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
        if (sender.player() instanceof ServerPlayer p) ws().teleport(p, draft);
      } catch (IOException | RuntimeException e) {
        bg.platform().log().log(Level.WARNING, "try 失敗", e);
        failed(sender, e);
      }
    });
  }

  // ---- 與單人世界同步（設計 §6.7） ----

  /** {@code pull <名稱>}：把選取範圍送到自己的用戶端。 */
  private void pull(Sender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    String name = w.size() == 1 ? w.getFirst().toLowerCase(Locale.ROOT) : "";
    if (!Frame.NAME.matcher(name).matches()) {
      msg().send(s(p), "usage-pull");
      return;
    }
    if (!bg.platform().canSync(p)) {
      msg().send(s(p), "sync-no-client");
      return;
    }
    var sel = bg.selections().get(p);
    if (sel.isEmpty()) {
      msg().send(s(p), "sel-none");
      return;
    }
    var wt = ws().worktree(sel.get().level());
    if (wt == null) {
      msg().send(s(p), "sync-not-branch");
      return;
    }
    var box = sel.get().box();
    if (Bundle.volume(box) > bg.settings().syncMaxBlocks()) {
      msg().send(s(p), "sync-too-big", "max", bg.settings().syncMaxBlocks());
      return;
    }
    String dimension = ws().logicalDimension(sel.get().level());
    if (ws().copying(dimension)) throw new Workspace.Refused("這個世界的起點世界還在背景複製，請稍後再試");
    autosaveThen(sender, wt, () -> bg.sync().pull(p, name, wt.branch(), dimension, box));
  }

  /** {@code push <名稱> [草稿名稱]}：收回單人世界改過的範圍，存成草稿。 */
  private void push(Sender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    String name = w.isEmpty() ? "" : w.getFirst().toLowerCase(Locale.ROOT);
    if (w.size() > 2 || !Frame.NAME.matcher(name).matches()) {
      msg().send(s(p), "usage-push");
      return;
    }
    String draft = w.size() == 2 ? w.get(1).toLowerCase(Locale.ROOT) : name;
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
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.size() != 1) {
      msg().send(sender, "usage-go");
      return;
    }
    String name = w.getFirst().toLowerCase(Locale.ROOT);
    if (!ws().drafts().containsKey(name)) {
      msg().send(s(p), "branch-unknown", "name", name);
      return;
    }
    ws().teleport(p, ws().openBranch(name));
    msg().send(s(p), "go-done", "branch", branchLabel(name));
  }

  private void back(Sender sender) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var world = p.level();
    if (ws().view(world) != null) {
      var session = ws().merges().of(world);
      var saved = returnTo.remove(p.getUUID());
      if (session != null) ws().teleport(p, ws().openBranch(session.branch()));
      else if (saved != null && ws().loaded(saved.level()))
        bg.platform().teleport(p, saved.level(), saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
      else ws().teleport(p, ws().main());
      return;
    }
    var wt = ws().worktree(world);
    if (wt == null || wt.branch().equals(Repository.MAIN)) {
      msg().send(s(p), "not-in-draft");
      return;
    }
    String source = ws().source(wt.branch());
    ws().teleport(p, ws().openBranch(source));
    msg().send(s(p), "go-done", "branch", branchLabel(source));
  }

  /** {@code view <版本>}：唯讀地看某個版本（設計 §6.5）。 */
  private void view(Sender sender, String[] args) throws IOException {
    var p = player(sender);
    if (p == null) return;
    var w = words(args);
    if (w.size() != 1) {
      msg().send(s(p), "usage-view");
      return;
    }
    var id = resolve(w.getFirst());
    if (id == null) {
      msg().send(s(p), "unknown-commit", "id", w.getFirst());
      return;
    }
    if (ws().view(p.level()) == null) returnTo.put(p.getUUID(), new Location(p.level(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
    var world = ws().openView(id, ws().logicalDimension(p.level()));
    ws().teleport(p, world);
    msg().send(s(p), "view-opened", "id", id.abbrev());
  }

  // ---- 併回（設計 §6.2） ----

  /**
   * 玩家：{@code publish [--selection] [confirm]}；主控台：{@code publish <草稿> [confirm]}。
   * 不帶 confirm 時只預覽：數量、作者，並在世界裡顯示會變動的位置。
   */
  private void publish(Sender sender, String[] args) throws IOException {
    var flags = flags(args);
    var w = words(args);
    Worktree draft;
    if (sender.player() instanceof ServerPlayer p) {
      draft = ws().worktree(p.level());
      if (draft != null && Workspace.isScratch(draft.branch())) {
        msg().send(s(p), "merge-use-continue");
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

  private void preview(Sender sender, Worktree target, String source, Workspace.Scope scope, boolean selection) {
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
      String command = "/bg publish " + (sender.isPlayer() ? "" : source + " ") + (selection ? "--selection " : "") + "confirm";
      sendButton(sender, "publish-confirm-button", command);
    }));
  }

  /** 合併並寫入目標世界；有衝突時開始衝突整合。{@code draft} 不為 null 時，完成後把在草稿裡的玩家送回目標世界。 */
  private void merge(Sender sender, Worktree target, String source, Workspace.Scope scope, Worktree draft) {
    ws().merge(target, source, scope, author(sender), applied -> {
      if (applied.isEmpty()) {
        msg().send(sender, "publish-nothing");
        return;
      }
      var a = applied.get();
      msg().send(sender, "publish-done", "id", a.commit().abbrev(), "cells", a.cells(), "entities", a.entities(), "target",
          branchLabel(target.branch()));
      if (a.shapes() > 0) msg().send(sender, "shapes-fixed", "cells", a.shapes());
      if (draft != null && sender.player() instanceof ServerPlayer p && !p.hasDisconnected() && ws().worktree(p.level()) == draft) {
        ws().teleport(p, target);
        if (scope == null && draft.branch().equals(source)) sendButton(s(p), "draft-delete-button", "/bg branch delete " + source);
      }
    }, error -> {
      if (error instanceof Workspace.ConflictException c)
        startSession(sender, scope == null ? MergeSessions.Kind.MERGE : MergeSessions.Kind.PARTIAL, target.branch(), source, scope, c);
      else {
        bg.platform().log().log(Level.WARNING, "併回失敗", error);
        failed(sender, error);
      }
    });
  }

  private void startSession(Sender sender, MergeSessions.Kind kind, String target, String source, Workspace.Scope scope,
      Workspace.ConflictException c) {
    ws().merges().start(kind, target, source, scope, c, author(sender), session -> {
      msg().send(sender, "merge-started", "cells", c.result.cellConflicts().size(), "entities", c.result.entityConflicts().size());
      msg().send(sender, "merge-help");
      if (!(sender.player() instanceof ServerPlayer p)) {
        listConflicts(sender, session);
        return;
      }
      try {
        // 外框要等玩家到了整合世界才顯示（換世界時會清掉外框）。
        ws().teleport(p, ws().openBranch(session.branch())).thenRun(() -> ws().sync(() -> listConflicts(s(p), session)));
      } catch (IOException e) {
        failed(s(p), e);
      }
    }, e -> {
      bg.platform().log().log(Level.WARNING, "無法開始衝突整合", e);
      failed(sender, e);
    });
  }

  // ---- 分支 ----

  private void branch(Sender sender, String[] args) throws IOException {
    var w = words(args);
    var what = w.isEmpty() ? "list" : w.getFirst().toLowerCase(Locale.ROOT);
    switch (what) {
      case "list" -> {
        for (var e : ws().drafts().entrySet()) {
          var wt = ws().worktree(e.getKey());
          int players = 0;
          if (wt != null) for (var world : wt.worlds().values()) players += world.players().size();
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

  private MergeSessions.Session session(Sender sender, List<String> w) {
    var merges = ws().merges();
    MergeSessions.Session s = null;
    if (sender.player() instanceof ServerPlayer p) s = merges.of(p.level());
    if (s == null && w.size() > 1) s = merges.get(w.getLast());
    if (s == null && merges.all().size() == 1) s = merges.all().iterator().next();
    if (s == null) msg().send(sender, "merge-none");
    return s;
  }

  private boolean mayResolve(Sender sender) {
    if (sender.hasPermission("buildgit.merge") || sender.hasPermission("buildgit.publish") || sender.hasPermission("buildgit.revert")) return true;
    msg().send(sender, "no-permission");
    return false;
  }

  private void merge(Sender sender, String[] args) throws IOException {
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
        if (p == null || !mayResolve(s(p))) return;
        var s = session(s(p), w);
        if (s == null) return;
        var side = w.size() > 1 ? w.get(1) : "result";
        var dim = ws().logicalDimension(p.level());
        switch (side) {
          case "ours" -> ws().teleport(p, ws().openView(s.ours(), dim));
          case "theirs" -> ws().teleport(p, ws().openView(s.theirs(), dim));
          default -> ws().teleport(p, ws().openBranch(s.branch()));
        }
        msg().send(s(p), "merge-view-" + (side.equals("ours") || side.equals("theirs") ? side : "result"), "target", s.target(), "source",
            String.valueOf(s.source()));
      }
      case "conflicts" -> {
        if (!mayResolve(sender)) return;
        var s = session(sender, w);
        if (s != null) listConflicts(sender, s);
      }
      case "goto" -> {
        var p = player(sender);
        if (p == null || !mayResolve(s(p))) return;
        var s = session(s(p), List.of());
        if (s == null) return;
        int n = w.size() > 1 ? parseInt(w.get(1), 1) : 1;
        ws().merges().result(s).whenComplete((result, error) -> ws().sync(() -> {
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
            var world = ws().openBranch(s.branch()).world(c.dimension());
            if (world == null) return;
            var b = c.box();
            bg.platform().teleport(p, world, (b.minX() + b.maxX()) / 2.0 + .5, b.maxY() + 1, (b.minZ() + b.maxZ()) / 2.0 + .5, p.getYRot(), p.getXRot())
                .thenRun(() -> ws().sync(() -> showConflicts(p, result, clusters)));
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
            yield new Workspace.Scope(ws().logicalDimension(p.level()), new Box(at.x() - HERE_RADIUS, at.y() - HERE_RADIUS, at.z() - HERE_RADIUS,
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

  private void listConflicts(Sender sender, MergeSessions.Session s) {
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
        if (sender.isPlayer()) sender.send(line.clickEvent(ClickEvent.runCommand("/bg merge goto " + (i + 1))));
        else sender.send(line);
      }
      if (sender.player() instanceof ServerPlayer p) showConflicts(p, result, clusters);
    }));
  }

  /** 在玩家目前的世界標出衝突格；太多時改標每一群的包圍盒。 */
  private void showConflicts(ServerPlayer p, dev.twme.buildgit.core.overlay.ThreeWayMerge.Result result, List<MergeSessions.Cluster> clusters) {
    String dim = ws().logicalDimension(p.level());
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

  private void restore(Sender sender, String[] args) throws IOException {
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
