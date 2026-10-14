package dev.twme.buildgit.paper.nms;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.paper.track.Tracker;
import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.execution.CustomCommandExecutor;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * 指令期間的原因（設計 §3.2）：把伺服器指令樹的每個可執行節點包一層，玩家執行時設定原因。
 * 原版、Bukkit 與 Paper 插件的指令都在同一棵樹上，所以其他插件的建築指令也會記錄。
 *
 * <p>{@code /fill}、{@code /clone} 的 strict 模式不發鄰居更新，所以另外依座標參數標記整個範圍。
 */
public final class CommandHook {
  private static final Field COMMAND;
  /** BuildGit 自己的指令：系統寫入另外處理，不當成玩家的建築指令。 */
  private static final Set<String> OWN = Set.of("bg", "buildgit", "buildgit:bg", "buildgit:buildgit");

  static {
    try {
      COMMAND = CommandNode.class.getDeclaredField("command");
      COMMAND.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Tracker tracker;
  private final Logger log;
  /** 已包裝過的節點；新節點（重新註冊、資料包重載）不在其中。 */
  private final Set<CommandNode<?>> wrapped = Collections.newSetFromMap(new IdentityHashMap<>());
  private boolean failed;

  public CommandHook(Tracker tracker, Logger log) {
    this.tracker = tracker;
    this.log = log;
  }

  /**
   * 包裝新出現的指令。每次指令執行前呼叫：Paper 與其他插件會在啟動後新增或替換節點（數量可能不變），
   * 所以逐一比對根節點的子節點；平時只是一兩百次雜湊查詢。
   */
  public void ensure() {
    if (failed) return;
    var root = MinecraftServer.getServer().getCommands().getDispatcher().getRoot();
    try {
      for (var child : root.getChildren()) if (!wrapped.contains(child) && !OWN.contains(child.getName())) wrap(child);
    } catch (ReflectiveOperationException | RuntimeException e) {
      failed = true;
      log.log(Level.WARNING, "無法包裝指令；指令造成的變更只能靠鄰居更新記錄", e);
    }
  }

  @SuppressWarnings("unchecked")
  private void wrap(CommandNode<CommandSourceStack> node) throws ReflectiveOperationException {
    if (!wrapped.add(node)) return;
    var command = (Command<CommandSourceStack>) COMMAND.get(node);
    // /function、/return 等由執行引擎以型別辨識，不能包裝；它們本身不改方塊，裡面的指令會各自被記錄。
    if (command != null && !(command instanceof Wrapped) && !(command instanceof CustomCommandExecutor.CommandAdapter<?>))
      COMMAND.set(node, new Wrapped(command));
    for (var child : node.getChildren()) wrap(child);
  }

  /** 恢復原本的指令（停用插件時）。 */
  @SuppressWarnings("unchecked")
  public void unwrap() {
    var seen = Collections.<CommandNode<?>>newSetFromMap(new IdentityHashMap<>());
    var stack = new ArrayDeque<CommandNode<CommandSourceStack>>();
    stack.push(MinecraftServer.getServer().getCommands().getDispatcher().getRoot());
    while (!stack.isEmpty()) {
      var node = stack.pop();
      if (!seen.add(node)) continue;
      try {
        if (COMMAND.get(node) instanceof CommandHook.Wrapped w) COMMAND.set(node, w.inner);
      } catch (ReflectiveOperationException ignored) {
        // 與包裝時相同的欄位，不會失敗
      }
      node.getChildren().forEach(stack::push);
    }
    wrapped.clear();
  }

  private final class Wrapped implements Command<CommandSourceStack> {
    final Command<CommandSourceStack> inner;

    Wrapped(Command<CommandSourceStack> inner) {
      this.inner = inner;
    }

    @Override
    public int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      if (!tracker.isTracked(ctx.getSource().getLevel().getWorld())) return inner.run(ctx);
      var player = ctx.getSource().getPlayer();
      // 主控台、指令方塊沒有玩家：只有其他插件以 API 指定了作者時才記。
      var author = player != null ? tracker.author(player.getBukkitEntity()) : tracker.cause();
      if (author == null) return inner.run(ctx);
      markArguments(ctx, author);
      try (var scope = tracker.push(author)) {
        return inner.run(ctx);
      }
    }
  }

  private static BlockPos position(CommandContext<CommandSourceStack> ctx, String name) {
    try {
      return ctx.getArgument(name, Coordinates.class).getBlockPos(ctx.getSource());
    } catch (IllegalArgumentException e) {
      return null; // 這個指令沒有這個參數
    }
  }

  private void markArguments(CommandContext<CommandSourceStack> ctx, Author author) {
    var world = ctx.getSource().getLevel().getWorld();
    var from = position(ctx, "from");
    var to = position(ctx, "to");
    if (from != null && to != null) mark(world, from, to, author);
    var begin = position(ctx, "begin");
    var end = position(ctx, "end");
    var destination = position(ctx, "destination");
    if (begin != null && end != null) {
      mark(world, begin, end, author);
      if (destination != null) {
        int dx = Math.abs(end.getX() - begin.getX()), dy = Math.abs(end.getY() - begin.getY()), dz = Math.abs(end.getZ() - begin.getZ());
        mark(world, destination, destination.offset(dx, dy, dz), author);
      }
    }
    var pos = position(ctx, "pos");
    if (pos != null) mark(world, pos, pos, author);
  }

  private void mark(org.bukkit.World world, BlockPos a, BlockPos b, Author author) {
    // 外擴一格：範圍邊界外的鄰居形狀也會變。
    var box =
        new Box(
            Math.min(a.getX(), b.getX()) - 1,
            Math.min(a.getY(), b.getY()) - 1,
            Math.min(a.getZ(), b.getZ()) - 1,
            Math.max(a.getX(), b.getX()) + 1,
            Math.max(a.getY(), b.getY()) + 1,
            Math.max(a.getZ(), b.getZ()) + 1);
    // 原版 /fill 上限 32768 格；插件指令可能更大，仍然只記位置，成本與格數成正比但不讀世界。
    tracker.markBox(world, box, author);
  }
}
