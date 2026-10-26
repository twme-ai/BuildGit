package dev.twme.buildgit.server;

import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 播放歷史（{@code /bg play}）：在玩家自己的檢視世界裡，照時間順序一步一步套用存檔，玩家自己走動觀看。
 * 每一步算的是兩個版本的差異，所以往回、跳到任何一步都一樣。
 */
final class Playback {
  /** 一步：那次存檔與它的版本。第一步是起點（播放開始時世界的樣子）。 */
  record Step(ObjectId commit, ObjectId tree, long time, String author, String message, boolean auto) {}

  static final class Session {
    final Workspace ws;
    final UUID player;
    final String dimension;
    final List<Step> steps;
    ServerLevel world;
    /** 合成之後才載入的 chunk 用的版本：目前播到的那一步。 */
    volatile ObjectId tree;
    int index;
    int seconds;
    boolean paused, busy, arrived;
    long next;
    final long started = System.currentTimeMillis();

    Session(Workspace ws, UUID player, String dimension, List<Step> steps, int seconds) {
      this.ws = ws;
      this.player = player;
      this.dimension = dimension;
      this.steps = steps;
      this.seconds = seconds;
      this.tree = steps.getFirst().tree();
    }

    Step step() {
      return steps.get(index);
    }
  }

  static final int DEFAULT_SECONDS = 2;

  private final BuildGit bg;
  private final Map<UUID, Session> sessions = new HashMap<>();

  Playback(BuildGit bg) {
    this.bg = bg;
  }

  /** 玩家在播放世界裡時的播放；不然是 null。 */
  Session of(ServerPlayer p) {
    var s = sessions.get(p.getUUID());
    return s != null && s.world == p.level() ? s : null;
  }

  /** 開一個播放世界、停在起點，傳送玩家過去（主執行緒）。 */
  Session start(ServerPlayer p, Workspace ws, String dimension, List<Step> steps) throws IOException {
    var s = new Session(ws, p.getUUID(), dimension, steps, DEFAULT_SECONDS);
    s.world = ws.openPlayback(steps.getFirst().commit(), dimension, "p" + Long.toString(System.currentTimeMillis(), 36), () -> s.tree);
    sessions.put(p.getUUID(), s);
    s.next = System.currentTimeMillis() + s.seconds * 1000L;
    ws.teleport(p, s.world);
    return s;
  }

  void stop(ServerPlayer p) {
    sessions.remove(p.getUUID());
  }

  /** 跳到第 {@code target} 步（0 是起點）。上一步還在寫入時回傳 false。 */
  boolean go(Session s, int target) {
    if (s.busy) return false;
    target = Math.clamp(target, 0, s.steps.size() - 1);
    if (target == s.index) return true;
    s.busy = true;
    var from = s.step().tree();
    s.index = target;
    var to = s.step().tree();
    s.tree = to;
    s.ws.viewAt(s.world, s.step().commit());
    s.ws.replay(s.world, s.dimension, from, to).whenComplete((v, e) -> s.ws.sync(() -> {
      s.busy = false;
      s.next = System.currentTimeMillis() + s.seconds * 1000L;
      if (e != null) {
        bg.platform().log().log(Level.WARNING, "播放歷史時無法寫入世界", Workspace.unwrap(e));
        s.paused = true;
      }
      var p = bg.platform().server().getPlayerList().getPlayer(s.player);
      if (p != null) bg.hud().update(p);
    }));
    return true;
  }

  /** 每刻（主執行緒）：到時間就播下一步；玩家離開播放世界就結束。 */
  void tick() {
    if (sessions.isEmpty()) return;
    long now = System.currentTimeMillis();
    for (var it = sessions.values().iterator(); it.hasNext(); ) {
      var s = it.next();
      var p = bg.platform().server().getPlayerList().getPlayer(s.player);
      boolean inside = p != null && p.level() == s.world;
      s.arrived |= inside;
      // 傳送是非同步的：還沒到之前不算離開，但 30 秒都沒到就放棄
      if (p == null || !inside && (s.arrived || now - s.started > 30_000)) {
        it.remove();
        continue;
      }
      if (!inside || s.paused || s.busy || now < s.next) continue;
      if (s.index + 1 >= s.steps.size()) {
        s.paused = true;
        bg.messages().send(bg.platform().sender(p), "play-ended");
        bg.hud().update(p);
        continue;
      }
      go(s, s.index + 1);
    }
  }

  // ---- 範圍 ----

  private static final Pattern AGO = Pattern.compile("(\\d+)([mhd])");
  private static final Pattern DATE = Pattern.compile("(?:(\\d{4})-)?(\\d{1,2})-(\\d{1,2})(?:/(\\d{1,2}):(\\d{2}))?");

  /**
   * 時間寫法：多久以前（{@code 30m}、{@code 12h}、{@code 3d}），或日期 {@code MM-dd}、{@code yyyy-MM-dd}，可以接 {@code /HH:mm}。
   * 只有日期的終點算到那天結束。不是時間寫法時回傳 null（當成存檔編號）。
   */
  static Long time(String text, boolean end) {
    var m = AGO.matcher(text);
    if (m.matches()) {
      long unit = switch (m.group(2)) {
        case "m" -> 60_000L;
        case "h" -> 3_600_000L;
        default -> 86_400_000L;
      };
      return System.currentTimeMillis() - Long.parseLong(m.group(1)) * unit;
    }
    m = DATE.matcher(text);
    if (!m.matches()) return null;
    try {
      var zone = ZoneId.systemDefault();
      var today = LocalDate.now(zone);
      var date = m.group(1) != null ? LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))
          : MonthDay.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))).atYear(today.getYear());
      // 沒寫年份、日期還沒到：是去年
      if (m.group(1) == null && date.isAfter(today)) date = date.minusYears(1);
      var time = m.group(4) != null ? LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)))
          : end ? LocalTime.MAX : LocalTime.MIDNIGHT;
      return date.atTime(time).atZone(zone).toInstant().toEpochMilli();
    } catch (java.time.DateTimeException | NumberFormatException e) {
      return null;
    }
  }

  /**
   * 從 {@code head} 沿著第一個父提交往回，挑出要播的存檔（背景執行緒）。起點：{@code fromCommit} 那次存檔的樣子，
   * 或 {@code fromTime} 之前最後的樣子，都沒有時是分支的開頭；終點：{@code toCommit}（取代 head），或 {@code toTime} 之前。
   * 預設略過自動存檔（它們的改動算進下一個里程碑），{@code all} 時全部播；最後一次存檔一定播到。
   */
  static List<Step> steps(Repository repo, ObjectId head, ObjectId fromCommit, Long fromTime, ObjectId toCommit, Long toTime, boolean all)
      throws IOException {
    var picked = new ArrayList<ObjectId>();
    ObjectId base = null;
    var id = toCommit != null ? toCommit : head;
    while (id != null) {
      var c = repo.commit(id);
      if (id.equals(fromCommit) || fromTime != null && c.time() < fromTime || c.parents().isEmpty()) {
        base = id;
        break;
      }
      if (toTime == null || c.time() <= toTime) picked.add(id);
      id = c.parents().getFirst();
    }
    if (fromCommit != null && !fromCommit.equals(base)) throw new Workspace.Refused("起點不在這個分支的歷史裡（只看每次存檔的第一個上一版）");
    Collections.reverse(picked);
    var steps = new ArrayList<Step>();
    steps.add(step(repo, base));
    for (int i = 0; i < picked.size(); i++) {
      var s = step(repo, picked.get(i));
      if (s.auto() && !all && i < picked.size() - 1) continue;
      if (!s.tree().equals(steps.getLast().tree())) steps.add(s);
    }
    return steps;
  }

  private static Step step(Repository repo, ObjectId id) throws IOException {
    var c = repo.commit(id);
    return new Step(id, c.tree(), c.time(), c.author().name(), c.message(), c.auto());
  }
}
