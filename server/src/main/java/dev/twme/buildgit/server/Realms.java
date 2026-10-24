package dev.twme.buildgit.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 進出建築世界（03-worlds §2）：誰能進去、傳送到出生點、回伺服器世界、失去資格的人送回去，以及遊戲模式：
 * 在建築世界裡照這個世界的設定（預設成員創造、訪客旁觀者），回到伺服器世界時恢復原本的模式；打開背包隔離的世界換上這個世界的背包。
 *
 * <p>每個玩家一個 {@code buildgit/players/<uuid>.yml}：進建築世界前在伺服器世界的模式與位置。重啟、當機、離線時被移出都照這份恢復，
 * 所以只要玩家人在伺服器世界、檔案裡還有模式，就換回去。模式與世界寫名稱（{@code creative}、{@code minecraft:overworld}），不隨版本變。
 *
 * <p>背包隔離（03-worlds §2.2）：{@code inventory} 記著身上穿的是哪個建築世界的背包（沒有就是原本的）。換到別的背包時先把身上的收進
 * {@code buildgit/players/<uuid>/<世界 id>.dat}（原本的是 {@code server.dat}），再換上目標的；訪客的不保留，每次都拿起始物品。
 */
final class Realms {
  /** 「最近去過」記幾個。 */
  static final int RECENT = 27;
  private final BuildGit bg;
  /** 這次在邊界外已經提醒過的訪客；回到邊界內就清掉。 */
  private final Set<UUID> warned = new HashSet<>();
  /** 上次提醒傳送門不能用的時間（站在傳送門裡每刻都會問）。 */
  private final Map<UUID, Long> portalWarned = new HashMap<>();

  Realms(BuildGit bg) {
    this.bg = bg;
  }

  /** 最近去過的建築世界 id，新的在前。 */
  List<String> recent(UUID player) {
    return load(player).getStringList("recent");
  }

  boolean admin(ServerPlayer p) {
    return bg.platform().hasPermission(p, "buildgit.world.admin");
  }

  /** 能不能進去：建築世界要是成員，或世界是公開的；伺服器世界見 {@link #canEnterServer}；管理員都可以。 */
  boolean canEnter(ServerPlayer p, Workspace ws) {
    if (ws.buildWorld() == null) return canEnterServer(p);
    return ws.role(p.getUUID()) != null || admin(p);
  }

  /** 伺服器世界（03-worlds §4.2）：公開時都可以；私人時要是成員、或有伺服器給的權限（{@code buildgit.role.viewer} 以上）。 */
  boolean canEnterServer(ServerPlayer p) {
    var access = bg.workspace().access();
    return access.open || access.members.containsKey(p.getUUID()) || bg.platform().hasPermission(p, PermissionSets.VIEWER) || admin(p);
  }

  /** 在伺服器世界裡：主世界、地獄、終界與它們的草稿、檢視世界。不受版本控制的其他世界（例如大廳）不算。 */
  boolean inServerWorld(ServerLevel level) {
    var ws = bg.workspace(level);
    if (ws.buildWorld() != null) return false;
    var key = level.dimension();
    return ws.owns(level) || key == net.minecraft.world.level.Level.OVERWORLD || key == net.minecraft.world.level.Level.NETHER
        || key == net.minecraft.world.level.Level.END;
  }

  /** 在私人的伺服器世界裡、卻不能在這裡（上線、被移出成員、死掉重生、權限被拿掉時）。 */
  private boolean blocked(ServerPlayer p) {
    return inServerWorld(p.level()) && !canEnterServer(p);
  }

  // ---- 大廳（03-worlds §4.2） ----

  /** 大廳無法使用時已經警告過（恢復前不再重複）。 */
  private boolean lobbyWarned;

  /** 是建築世界的大廳（{@code worlds.lobby} 寫「擁有者/名稱」）；不是、或找不到時為 null。 */
  Workspace lobbyWorld() {
    var spec = bg.settings().lobby();
    return spec.contains("/") ? bg.worlds().find(spec, null) : null;
  }

  /** 普通世界的大廳：先照維度 id（只寫名稱時當 {@code minecraft:名稱}），再照世界名稱找。 */
  private ServerLevel plainLobby() {
    var spec = bg.settings().lobby();
    if (spec.isEmpty() || spec.contains("/")) return null;
    var id = Identifier.tryParse((spec.contains(":") ? spec : "minecraft:" + spec).toLowerCase(Locale.ROOT));
    var level = id == null ? null : bg.platform().server().getLevel(ResourceKey.create(Registries.DIMENSION, id));
    return level != null ? level : bg.platform().findLevel(spec);
  }

  /** 大廳能不能用：有設定、找得到，是建築世界時是公開的，不是伺服器世界本身。能用時為 null，否則是原因。 */
  String lobbyProblem() {
    var spec = bg.settings().lobby();
    if (spec.isEmpty()) return "還沒有設定大廳（config.yml 的 worlds.lobby）";
    if (spec.contains("/")) {
      var ws = lobbyWorld();
      if (ws == null || !ws.isOpen()) return "找不到大廳 " + spec;
      if (!ws.config().open) return "大廳 " + spec + " 不是公開的";
      return null;
    }
    var level = plainLobby();
    if (level == null) return "找不到大廳世界 " + spec + "（要先用多世界插件載入）";
    if (inServerWorld(level)) return "大廳不能是伺服器世界本身";
    return null;
  }

  /** 立刻送到大廳（主執行緒）；大廳不能用時留在原地，主控台警告一次。 */
  void toLobby(ServerPlayer p) {
    var problem = lobbyProblem();
    var ws = lobbyWorld();
    ServerLevel level = null;
    if (problem == null)
      try {
        level = ws != null ? ws.openMain() : plainLobby();
      } catch (IOException | RuntimeException e) {
        problem = e.getMessage();
      }
    if (level == null) {
      if (!lobbyWarned) bg.platform().log().warning("伺服器世界是私人的，但無法把玩家送到大廳：" + problem);
      lobbyWarned = true;
      return;
    }
    lobbyWarned = false;
    var spawn = ws != null ? ws.config().spawn : null;
    if (spawn != null) Workspace.teleportNow(p, level, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    else if (ws != null) {
      level.getChunk(0, 0);
      Workspace.teleportNow(p, level, 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0), 0.5, p.getYRot(), p.getXRot());
    } else {
      var r = level.getLevelData().getRespawnData();
      Workspace.teleportNow(p, level, r.pos().getX() + 0.5, r.pos().getY(), r.pos().getZ() + 0.5, r.yaw(), r.pitch());
    }
    apply(p);
    bg.messages().send(bg.platform().sender(p), "lobby-sent");
  }

  /** 能不能 fork：成員都可以；訪客要擁有者允許複製；管理員都可以。 */
  boolean canFork(ServerPlayer p, Workspace ws) {
    var role = ws.role(p.getUUID());
    return admin(p) || role != null && (role != WorldConfig.Role.VISITOR || ws.config().allowFork);
  }

  /** 在建築世界裡只能看的人：訪客（管理員除外）。 */
  boolean visitor(ServerPlayer p, Workspace ws) {
    var role = ws.role(p.getUUID());
    return (role == null || role == WorldConfig.Role.VISITOR) && !admin(p);
  }

  /** 傳送到建築世界的出生點（第一次有人進來時才決定，擁有者可以改）。從伺服器世界進來時記下位置給 {@link #home}。 */
  void enter(ServerPlayer p, Workspace ws) throws IOException {
    if (bg.worlds().busy(ws)) throw new Workspace.Refused("這個世界正在改名、轉讓或刪除，請稍後再試");
    if (bg.workspace(p.level()).buildWorld() == null) {
      var state = load(p.getUUID());
      state.set("home.world", p.level().dimension().identifier().toString());
      state.set("home.pos", List.of(p.getX(), p.getY(), p.getZ(), (double) p.getYRot(), (double) p.getXRot()));
      save(p.getUUID(), state);
    }
    var level = ws.openMain();
    // 最近去過（記 id，改名、轉讓後照樣對得上）
    var state = load(p.getUUID());
    var recent = new ArrayList<>(state.getStringList("recent"));
    recent.remove(ws.buildWorld().id());
    recent.addFirst(ws.buildWorld().id());
    state.set("recent", List.copyOf(recent.subList(0, Math.min(recent.size(), RECENT))));
    save(p.getUUID(), state);
    var sender = bg.platform().sender(p);
    ws.spawn(bg.tracker().author(p), bg.messages().raw("world-first-commit")).whenComplete((spawn, e) -> ws.sync(() -> {
      if (e != null) {
        bg.platform().log().log(Level.WARNING, "無法決定建築世界 " + ws.label() + " 的出生點", e);
        bg.messages().send(sender, "branch-failed", "error", String.valueOf(Workspace.unwrap(e).getMessage()));
        return;
      }
      bg.platform().teleport(p, level, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
      bg.messages().send(sender, "world-entered", "world", ws.label());
    }));
  }

  /** 同 {@link #home}，但立刻傳送（之後要卸載世界時）。 */
  private void homeNow(ServerPlayer p) {
    var state = load(p.getUUID());
    var server = bg.platform().server();
    var id = Identifier.tryParse(state.getString("home.world", ""));
    var saved = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    if (saved != null && !allowedHome(p, saved)) saved = null;
    if (saved == null && !canEnterServer(p)) {
      toLobby(p);
      return;
    }
    if (saved != null && state.get("home.pos") instanceof List<?> pos && pos.size() == 5) {
      double[] v = new double[5];
      for (int i = 0; i < 5; i++) v[i] = pos.get(i) instanceof Number n ? n.doubleValue() : 0;
      Workspace.teleportNow(p, saved, v[0], v[1], v[2], (float) v[3], (float) v[4]);
    } else {
      var level = server.overworld();
      level.getChunk(0, 0);
      Workspace.teleportNow(p, level, 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0), 0.5, p.getYRot(), p.getXRot());
    }
    apply(p);
  }

  /**
   * 回到進入建築世界之前的位置；不知道、或那個世界沒載入時到伺服器主世界的 (0, 0)。模式由 {@link #apply} 在換世界後恢復。
   * 伺服器世界是私人的、自己又不能在那裡時送到大廳，回傳 false。
   */
  boolean home(ServerPlayer p) {
    var state = load(p.getUUID());
    var server = bg.platform().server();
    var id = Identifier.tryParse(state.getString("home.world", ""));
    var saved = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    if (saved != null && !allowedHome(p, saved)) saved = null;
    if (saved == null && !canEnterServer(p)) {
      toLobby(p);
      return false;
    }
    if (saved != null && state.get("home.pos") instanceof List<?> pos && pos.size() == 5) {
      double[] v = new double[5];
      for (int i = 0; i < 5; i++) v[i] = pos.get(i) instanceof Number n ? n.doubleValue() : 0;
      bg.platform().teleport(p, saved, v[0], v[1], v[2], (float) v[3], (float) v[4]);
      return true;
    }
    var level = server.overworld();
    bg.platform().loadChunk(level, 0, 0).thenAccept(chunk -> bg.workspace().sync(() ->
        bg.platform().teleport(p, level, 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0), 0.5, p.getYRot(), p.getXRot())));
    return true;
  }

  /** 記下的位置能不能回去：不是建築世界，是伺服器世界時要進得去。 */
  private boolean allowedHome(ServerPlayer p, ServerLevel level) {
    return bg.workspace(level).buildWorld() == null && (!inServerWorld(level) || canEnterServer(p));
  }

  /**
   * 照玩家現在所在的世界調整遊戲模式（換世界、上線、角色變了時）：在建築世界裡，第一次進來先記下原本的模式，
   * 再換成成員或訪客的模式；在伺服器世界裡，有記下的模式就換回去。
   */
  void apply(ServerPlayer p) {
    var ws = bg.workspace(p.level());
    var state = load(p.getUUID());
    inventory(p, ws, state);
    var saved = GameType.byName(state.getString("gamemode", ""), null);
    if (ws.buildWorld() == null) {
      if (saved == null) return;
      p.setGameMode(saved);
      state.set("gamemode", null);
      save(p.getUUID(), state);
      return;
    }
    if (saved == null) {
      state.set("gamemode", p.gameMode().getName());
      save(p.getUUID(), state);
    }
    var mode = visitor(p, ws) ? ws.config().visitorMode : ws.config().memberMode;
    if (p.gameMode() != mode) p.setGameMode(mode);
  }

  /**
   * 照所在的世界換背包。要換上的背包讀不懂、收起現在的寫不進檔案，或狀態記不下來時維持原狀
   * （不然原本的背包會被清空，或下次被當成另一份覆蓋掉）。
   */
  private void inventory(ServerPlayer p, Workspace ws, Yaml state) {
    boolean visitor = ws.buildWorld() != null && visitor(p, ws);
    var target = ws.buildWorld() != null && ws.isolated(visitor) ? ws.buildWorld().id() : null;
    var worn = state.getString("inventory");
    if (Objects.equals(target, worn)) return;
    var serverFile = inventoryFile(p.getUUID(), "server");
    var own = target == null ? null : inventoryFile(p.getUUID(), target);
    CompoundTag next;
    try {
      next = target == null ? Inventories.load(serverFile) : ws.kitReset(visitor) ? null : Inventories.load(own);
    } catch (IOException e) {
      bg.platform().log().log(Level.WARNING, "玩家 " + p.getScoreboardName() + " 的背包檔 " + (target == null ? serverFile : own) + " 讀不懂，沒有換背包", e);
      return;
    }
    try {
      if (worn == null) Inventories.write(serverFile, Inventories.capture(p));
      else Inventories.write(inventoryFile(p.getUUID(), worn), Inventories.capture(p)); // 進來時才照「每次重設」決定要不要用
    } catch (IOException e) {
      bg.platform().log().log(Level.WARNING, "無法收起玩家 " + p.getScoreboardName() + " 的背包", e);
      return;
    }
    state.set("inventory", target);
    state.set("inventory-keep", null); // 舊版記的，現在離開時一律收起
    if (!save(p.getUUID(), state)) {
      state.set("inventory", worn);
      return;
    }
    var upgrader = bg.workspace().upgrader();
    if (target == null) {
      var file = serverFile;
      Inventories.restore(p, next, upgrader);
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        // 下次收起時會覆蓋
      }
      return;
    }
    if (ws.kitReset(visitor)) {
      try {
        Files.deleteIfExists(own); // 之後改成只給第一次時，不會換上很久以前留下的背包
      } catch (IOException e) {
        // 下次收起時會覆蓋
      }
    }
    Inventories.restore(p, next != null ? next : Inventories.read(ws.kitFile(visitor)), upgrader);
  }

  /** 死後重生（重生前呼叫，這時還是死掉的玩家）：重生後還穿著這個世界的背包、打開了死後重給時，換上起始物品（終界箱不動）。 */
  void died(ServerPlayer dead, Workspace ws) {
    var id = dead.getUUID();
    bg.platform().later(1, () -> {
      var p = bg.platform().server().getPlayerList().getPlayer(id);
      if (p == null || bg.workspace(p.level()) != ws || ws.buildWorld() == null || !ws.buildWorld().id().equals(load(id).getString("inventory"))) return;
      boolean visitor = visitor(p, ws);
      if (ws.kitRespawn(visitor)) Inventories.giveKit(p, Inventories.read(ws.kitFile(visitor)), bg.workspace().upgrader());
    });
  }

  private Path inventoryFile(UUID player, String name) {
    return bg.platform().repoDir().resolve("players").resolve(player.toString()).resolve(name + ".dat");
  }

  /** 在這個建築世界（含草稿、檢視世界）裡、已經沒有資格的人送回伺服器世界；留下的人照新的角色換模式（成員、可見性或遊戲模式的設定變了時）。 */
  void roleChanged(Workspace ws) {
    if (ws.buildWorld() == null) {
      for (var p : List.copyOf(bg.platform().server().getPlayerList().getPlayers())) if (blocked(p)) toLobby(p);
      return;
    }
    KitEditor.close(bg, ws, false);
    for (var p : List.copyOf(bg.platform().server().getPlayerList().getPlayers()))
      if (bg.workspace(p.level()) == ws) {
        if (canEnter(p, ws)) apply(p);
        else evict(p, ws);
      }
  }

  /** 世界要改名、轉讓、刪除：裡面的人（含草稿、檢視世界）立刻送回伺服器世界。 */
  void evictAll(Workspace ws, String key, Object... args) {
    KitEditor.close(bg, ws, true);
    for (var p : List.copyOf(bg.platform().server().getPlayerList().getPlayers()))
      if (bg.workspace(p.level()) == ws) {
        bg.messages().send(bg.platform().sender(p), key, args);
        homeNow(p);
      }
  }

  private void evict(ServerPlayer p, Workspace ws) {
    bg.messages().send(bg.platform().sender(p), "world-evicted", "world", ws.label());
    home(p);
  }

  /** 上線時（下一刻）：在沒有資格的建築世界裡（離線期間被移除、改成私人）送回去；否則照所在的世界調整模式。 */
  void joined(ServerPlayer p) {
    var ws = bg.workspace(p.level());
    if (ws.buildWorld() != null && !canEnter(p, ws)) evict(p, ws);
    else if (blocked(p)) toLobby(p);
    else apply(p);
    // 離線時收到的通知
    var state = load(p.getUUID());
    if (state.get("notices") instanceof List<?> notices) {
      for (var n : notices)
        if (n instanceof Map<?, ?> m && m.get("key") instanceof String key && m.get("args") instanceof List<?> args)
          bg.messages().send(bg.platform().sender(p), key, args.stream().map(String::valueOf).toArray());
      state.set("notices", null);
      save(p.getUUID(), state);
    }
  }

  /** 通知玩家（主執行緒）：在線上就送，否則記在 {@code players/<uuid>.yml}，下次上線再送。佔位符的值都當文字。 */
  void notify(UUID player, String key, Object... placeholders) {
    var online = bg.platform().server().getPlayerList().getPlayer(player);
    if (online != null) {
      bg.messages().send(bg.platform().sender(online), key, placeholders);
      return;
    }
    var state = load(player);
    var notices = new ArrayList<Object>(state.get("notices") instanceof List<?> l ? l : List.of());
    var n = new LinkedHashMap<String, Object>();
    n.put("key", key);
    n.put("args", Arrays.stream(placeholders).map(String::valueOf).toList());
    notices.add(n);
    state.set("notices", notices);
    save(player, state);
  }

  /**
   * 每秒：在私人的伺服器世界裡、卻不能在那裡的人送到大廳（死掉重生、從傳送門過來、權限被拿掉）。
   * 旁觀者會穿過世界邊界，訪客超出邊界就拉回邊界內側（03-worlds §4.1）。
   * 世界邊界只擋得住非旁觀者，成員本來就出不去。
   */
  void tick() {
    for (var p : List.copyOf(bg.platform().server().getPlayerList().getPlayers())) {
      if (blocked(p)) {
        toLobby(p);
        continue;
      }
      var ws = bg.workspace(p.level());
      var border = p.level().getWorldBorder();
      if (ws.buildWorld() == null || !visitor(p, ws) || border.isWithinBounds(p.getX(), p.getZ())) {
        warned.remove(p.getUUID());
        continue;
      }
      // 拉回離邊界一格的地方，免得下一秒又在線上
      double r = Math.max(0, border.getSize() / 2 - 1);
      double x = Math.clamp(p.getX(), border.getCenterX() - r, border.getCenterX() + r);
      double z = Math.clamp(p.getZ(), border.getCenterZ() - r, border.getCenterZ() + r);
      bg.platform().teleport(p, p.level(), x, p.getY(), z, p.getYRot(), p.getXRot());
      if (warned.add(p.getUUID())) bg.messages().send(bg.platform().sender(p), "visitor-border");
    }
  }

  /** 玩家想用建築世界裡的傳送門：每 3 秒最多提醒一次。 */
  void portalRefused(ServerPlayer p) {
    long now = System.currentTimeMillis();
    if (now - portalWarned.getOrDefault(p.getUUID(), 0L) < 3000) return;
    portalWarned.put(p.getUUID(), now);
    bg.messages().send(bg.platform().sender(p), "visitor-no-portal");
  }

  void quit(ServerPlayer p) {
    warned.remove(p.getUUID());
    portalWarned.remove(p.getUUID());
  }

  // ---- 每個玩家的記錄 ----

  private Path path(UUID player) {
    return bg.platform().repoDir().resolve("players").resolve(player + ".yml");
  }

  private Yaml load(UUID player) {
    return Yaml.load(path(player));
  }

  /** 記下玩家的狀態；記不下來時警告並回傳 false。 */
  private boolean save(UUID player, Yaml state) {
    var file = path(player);
    try {
      if (state.keys().isEmpty()) Files.deleteIfExists(file);
      else {
        Files.createDirectories(file.getParent());
        state.save(file);
      }
      return true;
    } catch (IOException e) {
      bg.platform().log().log(Level.WARNING, "無法記錄玩家 " + player + " 進建築世界前的狀態", e);
      return false;
    }
  }
}
