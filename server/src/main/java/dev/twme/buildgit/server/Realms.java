package dev.twme.buildgit.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
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
  private final BuildGit bg;
  /** 這次在邊界外已經提醒過的訪客；回到邊界內就清掉。 */
  private final Set<UUID> warned = new HashSet<>();
  /** 上次提醒傳送門不能用的時間（站在傳送門裡每刻都會問）。 */
  private final Map<UUID, Long> portalWarned = new HashMap<>();

  Realms(BuildGit bg) {
    this.bg = bg;
  }

  boolean admin(ServerPlayer p) {
    return bg.platform().hasPermission(p, "buildgit.world.admin");
  }

  /** 能不能進去：伺服器世界都可以；建築世界要是成員，或世界是公開的；管理員都可以。 */
  boolean canEnter(ServerPlayer p, Workspace ws) {
    return ws.buildWorld() == null || ws.role(p.getUUID()) != null || admin(p);
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
    if (saved != null && bg.workspace(saved).buildWorld() == null && state.get("home.pos") instanceof List<?> pos && pos.size() == 5) {
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

  /** 回到進入建築世界之前的位置；不知道、或那個世界沒載入時到伺服器主世界的 (0, 0)。模式由 {@link #apply} 在換世界後恢復。 */
  void home(ServerPlayer p) {
    var state = load(p.getUUID());
    var server = bg.platform().server();
    var id = Identifier.tryParse(state.getString("home.world", ""));
    var saved = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    if (saved != null && bg.workspace(saved).buildWorld() == null && state.get("home.pos") instanceof List<?> pos && pos.size() == 5) {
      double[] v = new double[5];
      for (int i = 0; i < 5; i++) v[i] = pos.get(i) instanceof Number n ? n.doubleValue() : 0;
      bg.platform().teleport(p, saved, v[0], v[1], v[2], (float) v[3], (float) v[4]);
      return;
    }
    var level = server.overworld();
    bg.platform().loadChunk(level, 0, 0).thenAccept(chunk -> bg.workspace().sync(() ->
        bg.platform().teleport(p, level, 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0), 0.5, p.getYRot(), p.getXRot())));
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

  /** 照所在的世界換背包；換不了（寫不進檔案）時維持原狀。 */
  private void inventory(ServerPlayer p, Workspace ws, Yaml state) {
    var target = ws.buildWorld() != null && ws.isolated() ? ws.buildWorld().id() : null;
    var worn = state.getString("inventory");
    if (Objects.equals(target, worn)) return;
    try {
      if (worn == null) Inventories.write(inventoryFile(p.getUUID(), "server"), Inventories.capture(p));
      else if (state.getBoolean("inventory-keep", false)) Inventories.write(inventoryFile(p.getUUID(), worn), Inventories.capture(p));
    } catch (IOException e) {
      bg.platform().log().log(Level.WARNING, "無法收起玩家 " + p.getScoreboardName() + " 的背包", e);
      return;
    }
    boolean visitor = target != null && visitor(p, ws);
    state.set("inventory", target);
    state.set("inventory-keep", target != null && !visitor ? true : null);
    save(p.getUUID(), state);
    var upgrader = bg.workspace().upgrader();
    if (target == null) {
      var file = inventoryFile(p.getUUID(), "server");
      Inventories.restore(p, Inventories.read(file), upgrader);
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        // 下次收起時會覆蓋
      }
      return;
    }
    var own = visitor ? null : Inventories.read(inventoryFile(p.getUUID(), target));
    Inventories.restore(p, own != null ? own : Inventories.read(ws.kitFile(visitor)), upgrader);
  }

  private Path inventoryFile(UUID player, String name) {
    return bg.platform().repoDir().resolve("players").resolve(player.toString()).resolve(name + ".dat");
  }

  /** 在這個建築世界（含草稿、檢視世界）裡、已經沒有資格的人送回伺服器世界；留下的人照新的角色換模式（成員、可見性或遊戲模式的設定變了時）。 */
  void roleChanged(Workspace ws) {
    for (var p : List.copyOf(bg.platform().server().getPlayerList().getPlayers()))
      if (bg.workspace(p.level()) == ws) {
        if (canEnter(p, ws)) apply(p);
        else evict(p, ws);
      }
  }

  /** 世界要改名、轉讓、刪除：裡面的人（含草稿、檢視世界）立刻送回伺服器世界。 */
  void evictAll(Workspace ws, String key, Object... args) {
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
    else apply(p);
  }

  /**
   * 每秒：旁觀者會穿過世界邊界，訪客超出邊界就拉回邊界內側（03-worlds §4.1）。
   * 世界邊界只擋得住非旁觀者，成員本來就出不去。
   */
  void tick() {
    for (var p : bg.platform().server().getPlayerList().getPlayers()) {
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

  private void save(UUID player, Yaml state) {
    var file = path(player);
    try {
      if (state.keys().isEmpty()) Files.deleteIfExists(file);
      else {
        Files.createDirectories(file.getParent());
        state.save(file);
      }
    } catch (IOException e) {
      bg.platform().log().log(Level.WARNING, "無法記錄玩家 " + player + " 進建築世界前的狀態", e);
    }
  }
}
