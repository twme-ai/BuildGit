package dev.twme.buildgit.server;

import dev.twme.buildgit.core.repo.Repository;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家「在 BuildGit 的哪裡」，給 LuckPerms 當情境（設计 §8.1）。LuckPerms 可能在任何執行緒要情境，所以主執行緒每刻比對
 * 玩家目前的位置、存成一份對照表，變了才通知 LuckPerms 丟掉快取；計算時只讀這份表。
 */
final class Contexts {
  static final String LOCATION = "buildgit:location", BRANCH = "buildgit:branch";
  static final List<String> LOCATIONS = List.of("main", "draft", "view", "resolve");

  private final Map<UUID, Map<String, String>> current = new ConcurrentHashMap<>();
  private volatile Set<String> branches = Set.of();
  /** 通知 LuckPerms 某個玩家的情境變了；沒裝 LuckPerms 時為 null，什麼都不做。 */
  private Consumer<ServerPlayer> signal;
  private int ticks;

  void signal(Consumer<ServerPlayer> signal) {
    this.signal = signal;
  }

  /** 任何執行緒：玩家目前的情境。 */
  Map<String, String> of(UUID player) {
    return current.getOrDefault(player, Map.of());
  }

  /** 任何執行緒：目前的分支名稱（給 LuckPerms 的補全與網頁編輯器）。 */
  Set<String> branches() {
    return branches;
  }

  /** 主執行緒，每刻。 */
  void tick(Workspace ws, List<ServerPlayer> players) {
    if (signal == null) return;
    if (current.size() > players.size()) {
      var online = new HashSet<UUID>();
      for (var p : players) online.add(p.getUUID());
      current.keySet().retainAll(online);
    }
    for (var p : players) {
      var now = compute(ws, p.level());
      if (!now.equals(current.put(p.getUUID(), now))) signal.accept(p);
    }
    // 分支清單只給補全用，讀檔不必每刻
    if (ticks++ % 200 == 0 && ws.isOpen()) {
      try {
        var names = ws.branches().keySet();
        if (!names.equals(branches)) branches = Set.copyOf(names);
      } catch (Exception e) {
        // 讀不到分支清單時沿用上次的；只影響補全
      }
    }
  }

  private static Map<String, String> compute(Workspace ws, ServerLevel level) {
    if (!ws.isOpen()) return Map.of();
    var session = ws.merges().of(level);
    if (session != null) return Map.of(LOCATION, "resolve", BRANCH, session.target());
    var wt = ws.worktree(level);
    if (wt != null) return Map.of(LOCATION, wt.branch().equals(Repository.MAIN) ? "main" : "draft", BRANCH, wt.branch());
    if (ws.view(level) != null) return Map.of(LOCATION, "view");
    return Map.of();
  }
}
