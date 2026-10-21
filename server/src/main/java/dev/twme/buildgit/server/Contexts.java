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
  static final String LOCATION = "buildgit:location", BRANCH = "buildgit:branch", WORLD = "buildgit:world", ROLE = "buildgit:role";
  static final List<String> LOCATIONS = List.of("main", "draft", "view", "resolve");
  static final List<String> ROLES = List.of("owner", "manager", "builder", "visitor");

  /** 玩家 → 情境（同一個鍵可以有多個值：角色往下包含）。 */
  private final Map<UUID, Map<String, List<String>>> current = new ConcurrentHashMap<>();
  private volatile Set<String> branches = Set.of();
  private volatile Set<String> worlds = Set.of();
  /** 通知 LuckPerms 某個玩家的情境變了；沒裝 LuckPerms 時為 null，什麼都不做。 */
  private Consumer<ServerPlayer> signal;
  private int ticks;

  void signal(Consumer<ServerPlayer> signal) {
    this.signal = signal;
  }

  /** 任何執行緒：玩家目前的情境。 */
  Map<String, List<String>> of(UUID player) {
    return current.getOrDefault(player, Map.of());
  }

  /** 任何執行緒：目前的分支名稱（給 LuckPerms 的補全與網頁編輯器）。 */
  Set<String> branches() {
    return branches;
  }

  /** 任何執行緒：伺服器世界與所有建築世界的名稱（同上）。 */
  Set<String> worlds() {
    return worlds;
  }

  /** 主執行緒，每刻。 */
  void tick(BuildWorlds worlds, List<ServerPlayer> players) {
    if (signal == null) return;
    if (current.size() > players.size()) {
      var online = new HashSet<UUID>();
      for (var p : players) online.add(p.getUUID());
      current.keySet().retainAll(online);
    }
    for (var p : players) {
      var now = compute(worlds.of(p.level()), p);
      if (!now.equals(current.put(p.getUUID(), now))) signal.accept(p);
    }
    // 分支與世界清單只給補全用，讀檔不必每刻
    var ws = worlds.server();
    if (ticks % 200 == 0) {
      var labels = new HashSet<String>();
      for (var w : worlds.all()) labels.add(w.label());
      if (!labels.equals(this.worlds)) this.worlds = Set.copyOf(labels);
    }
    if (ticks++ % 200 == 0 && ws.isOpen()) {
      try {
        var names = ws.branches().keySet();
        if (!names.equals(branches)) branches = Set.copyOf(names);
      } catch (Exception e) {
        // 讀不到分支清單時沿用上次的；只影響補全
      }
    }
  }

  /** 世界一律有；角色只在建築世界、只依成員名單（03-worlds §4）；位置與分支在受版本控制的世界。 */
  private static Map<String, List<String>> compute(Workspace ws, ServerPlayer p) {
    var level = p.level();
    var result = new HashMap<String, List<String>>();
    result.put(WORLD, List.of(ws.label()));
    var role = ws.role(p.getUUID());
    if (role != null)
      result.put(ROLE, switch (role) {
        case OWNER -> List.of("owner", "manager", "builder");
        case MANAGER -> List.of("manager", "builder");
        case BUILDER -> List.of("builder");
        case VISITOR -> List.of("visitor");
      });
    if (!ws.isOpen()) return result;
    var session = ws.merges().of(level);
    var wt = ws.worktree(level);
    if (session != null) {
      result.put(LOCATION, List.of("resolve"));
      result.put(BRANCH, List.of(session.target()));
    } else if (wt != null) {
      result.put(LOCATION, List.of(wt.branch().equals(Repository.MAIN) ? "main" : "draft"));
      result.put(BRANCH, List.of(wt.branch()));
    } else if (ws.view(level) != null) result.put(LOCATION, List.of("view"));
    return result;
  }
}
