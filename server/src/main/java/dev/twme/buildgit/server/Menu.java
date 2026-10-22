package dev.twme.buildgit.server;

import dev.twme.buildgit.core.store.ObjectId;
import dev.twme.buildgit.core.repo.Repository;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bg} 不帶參數時的箱子選單（設計 §6.6）：常用動作與草稿清單，點一下就執行對應的子指令，
 * 所以權限與行為和打指令完全相同。畫面由平台負責（Paper 的物品欄、Fabric 的箱子選單）。
 *
 * <p>排列（5 排）：開啟容器時游標在視窗正中央，5 排箱子的正中央落在最下排的中間（第 40 格）。
 * 最下排依功能分成三組、以玻璃片隔開：左邊是歷史（檢查、歷史），中間是這個世界的工作（標出變更、狀態、存檔，
 * 狀態在游標上），右邊是玩家與範圍（誰在哪裡、選取範圍）。上一排是玻璃分隔線，情境動作（回去與併回、
 * 衝突清單與完成整合）嵌在中間、狀態正上方。每個動作的位置固定，沒有權限時那格空著。
 * 最上面三排是草稿清單，從左上角開始排。
 */
final class Menu {
  private static final int ROWS = Platform.MENU_ROWS;
  /** 草稿清單的排數：最下排是動作，上一排是分隔線。 */
  private static final int LIST_ROWS = ROWS - 2;
  private static final String DIVIDER = "gray_stained_glass_pane";
  /** 每個草稿顯示幾個里程碑，以及最多往回找幾個存檔。 */
  private static final int MILESTONES = 3, MILESTONE_SEARCH = 200;
  private final BuildGit bg;

  Menu(BuildGit bg) {
    this.bg = bg;
  }

  void open(ServerPlayer p) {
    var msg = bg.messages();
    var ws = bg.workspace(p.level());
    var platform = bg.platform();
    var wt = ws.worktree(p.level());
    boolean inDraft = wt != null && !wt.branch().equals(Repository.MAIN) && !Workspace.isScratch(wt.branch());
    boolean inMerge = wt != null && Workspace.isScratch(wt.branch());
    boolean inView = ws.view(p.level()) != null;

    var items = new ArrayList<Platform.MenuItem>();
    int bottom = (ROWS - 1) * 9, second = (ROWS - 2) * 9;
    // 左：歷史
    put(items, bottom + 0, "compass", "menu-inspect", "inspect", p, "buildgit.inspect");
    put(items, bottom + 1, "clock", "menu-log", "log", p, "buildgit.log");
    // 中：這個世界的工作，狀態在游標上
    put(items, bottom + 3, "spyglass", "menu-diff", "diff", p, "buildgit.diff");
    put(items, bottom + 4, "book", "menu-status", "status", p, "buildgit.status");
    put(items, bottom + 5, "writable_book", "menu-save", "save", p, "buildgit.save");
    if (inDraft) {
      put(items, second + 3, "oak_door", "menu-back", "back", p, "buildgit.back");
      put(items, second + 4, "emerald", "menu-publish", "publish", p, "buildgit.publish");
    } else if (inMerge) {
      put(items, second + 3, "barrier", "menu-merge-conflicts", "merge conflicts", p, null);
      put(items, second + 4, "emerald", "menu-merge-continue", "merge continue", p, null);
    } else if (inView) put(items, second + 3, "oak_door", "menu-back", "back", p, "buildgit.back");
    // 右：玩家與範圍
    put(items, bottom + 7, "player_head", "menu-who", "who", p, "buildgit.who");
    put(items, bottom + 8, "wooden_axe", "menu-sel", "sel show", p, "buildgit.sel");
    // 分隔線：最下排三組之間的直線，上一排除了情境動作以外都是橫線
    items.add(divider(bottom + 2));
    items.add(divider(bottom + 6));
    var used = items.stream().map(Platform.MenuItem::slot).collect(java.util.stream.Collectors.toSet());
    for (int c = 0; c < 9; c++)
      if (!used.contains(second + c)) items.add(divider(second + c));

    var list = new ArrayList<Platform.MenuItem>();
    try {
      String dim = ws.logicalDimension(p.level());
      for (var e : ws.drafts().entrySet()) {
        var name = e.getKey();
        if (!ws.covers(name, dim)) continue; // 只列這個世界的草稿
        var here = wt != null && wt.branch().equals(name);
        var label = name.equals(Repository.MAIN) ? msg.get("branch-main") : msg.get("menu-draft", "name", name);
        var item = here ? "lime_stained_glass" : name.equals(Repository.MAIN) ? "grass_block" : "paper";
        var action = !here && platform.hasPermission(p, "buildgit.go") ? "go " + name : null;
        var lore = draftLore(ws, e.getValue());
        lore.add(msg.get(here ? "menu-draft-here" : "menu-draft-go"));
        list.add(new Platform.MenuItem(0, item, label, lore, action));
      }
    } catch (IOException e) {
      // 清單讀不到時只顯示動作
    }
    for (var s : ws.merges().all())
      list.add(new Platform.MenuItem(0, "red_stained_glass", msg.get("menu-merge", "id", s.id(), "target", s.target()),
          msg.get("menu-draft-go"), "merge view result " + s.id()));

    // 草稿清單從左上角開始排；放不下時最後一格提示還有幾個
    int capacity = LIST_ROWS * 9;
    var shown = new ArrayList<>(list.subList(0, Math.min(list.size(), capacity)));
    if (list.size() > capacity)
      shown.set(capacity - 1, new Platform.MenuItem(0, "book", msg.get("menu-more", "count", String.valueOf(list.size() - capacity + 1)),
          msg.get("menu-more-lore"), platform.hasPermission(p, "buildgit.branch.list") ? "branch list" : null));
    for (int i = 0; i < shown.size(); i++) {
      var it = shown.get(i);
      items.add(new Platform.MenuItem(i, it.item(), it.name(), it.lore(), it.action()));
    }
    platform.openMenu(p, msg.get("menu-title"), items);
  }

  /**
   * 建立建築世界的選單（03-worlds §3）：三排分別是類型、地形、規則，選中的打勾；點其他選項就是帶著改過的選項再執行一次
   * {@code new}，重新打開這個選單。最下排是名稱與種子等說明（只能打指令設定），以及「建立」。
   */
  void openCreate(ServerPlayer p, BgCommand.NewWorld o) {
    var msg = bg.messages();
    var items = new ArrayList<Platform.MenuItem>();
    label(items, 0, "compass", "world-create-type");
    for (var t : WorldConfig.Type.values()) {
      var icon = switch (t) {
        case OVERWORLD -> "grass_block";
        case NETHER -> "netherrack";
        case END -> "end_stone";
      };
      option(items, 2 + t.ordinal(), icon, "world-type-" + WorldConfig.key(t), o.type() == t, o.withType(t));
    }
    label(items, 9, "filled_map", "world-create-terrain");
    int slot = 11;
    for (var t : WorldConfig.Terrain.values()) {
      if (t == WorldConfig.Terrain.SERVER) continue;
      if (o.type() != WorldConfig.Type.OVERWORLD && (t == WorldConfig.Terrain.LARGE_BIOMES || t == WorldConfig.Terrain.AMPLIFIED)) continue;
      var icon = switch (t) {
        case NORMAL -> "oak_sapling";
        case LARGE_BIOMES -> "jungle_sapling";
        case AMPLIFIED -> "dripstone_block";
        case FLAT -> "smooth_stone";
        default -> "glass";
      };
      option(items, slot++, icon, "world-terrain-" + WorldConfig.key(t), o.terrain() == t, o.withTerrain(t));
    }
    label(items, 18, "writable_book", "world-create-rules");
    for (var r : WorldConfig.Rules.values())
      option(items, 20 + r.ordinal(), r == WorldConfig.Rules.BUILD ? "bricks" : "wheat", "world-rules-" + WorldConfig.key(r), o.rules() == r, o.withRules(r));
    for (int c = 0; c < 9; c++) items.add(divider(27 + c));
    var info = new ArrayList<Component>();
    info.add(msg.get("world-create-info-name", "name", o.name() != null ? o.name() : msg.raw("world-create-auto-name")));
    info.add(msg.get("world-create-info-seed", "seed", o.seed() != null ? o.seed() : msg.raw("world-create-random-seed")));
    if (o.terrain() == WorldConfig.Terrain.FLAT)
      info.add(msg.get("world-create-info-layers", "layers", o.layers() != null ? o.layers() : WorldConfig.formatLayers(WorldConfig.defaultLayers(o.type()))));
    if (o.terrain() == WorldConfig.Terrain.VOID) info.add(msg.get("world-create-info-block", "block", o.block() != null ? o.block() : "stone"));
    info.add(msg.get("world-create-info-hint"));
    items.add(new Platform.MenuItem(36, "name_tag", msg.get("world-create-info"), info, null));
    items.add(new Platform.MenuItem(40, "emerald", msg.get("world-create-confirm"), msg.get("world-create-confirm-lore"), "new " + o.args() + " confirm"));
    bg.platform().openMenu(p, msg.get("world-create-title"), items);
  }

  /** 可以改的設定與它們的值（依點選時輪替的順序）；沒有值的是「設成我現在的位置」。 */
  static final Map<String, List<String>> SETTINGS = new LinkedHashMap<>();
  /** 有名稱的時刻。 */
  static final Map<String, Integer> TIMES = new LinkedHashMap<>();

  static {
    TIMES.put("noon", 6000);
    TIMES.put("sunset", 12000);
    TIMES.put("midnight", 18000);
    TIMES.put("day", 1000);
    var times = new ArrayList<>(TIMES.keySet());
    times.add("cycle");
    SETTINGS.put("time", times);
    SETTINGS.put("weather", List.of("clear", "rain", "thunder", "cycle"));
    SETTINGS.put("difficulty", List.of("peaceful", "easy", "normal", "hard", "server"));
    SETTINGS.put("mobs", List.of("off", "on"));
    SETTINGS.put("nature", List.of("off", "on"));
    SETTINGS.put("border", List.of("256", "512", "1024", "2048", "4096", "default"));
    SETTINGS.put("border-center", List.of());
    SETTINGS.put("spawn", List.of());
    SETTINGS.put("member-mode", List.of("creative", "survival", "adventure"));
    SETTINGS.put("visitor-mode", List.of("spectator", "adventure"));
    SETTINGS.put("visitor-interact", List.of("off", "on"));
  }

  /** 設定目前的值，寫成 {@link #SETTINGS} 裡的寫法（固定在沒有名稱的時刻時是刻數）。 */
  private static String current(Workspace ws, String key) {
    var c = ws.config();
    return switch (key) {
      case "time" -> c.time == null ? "cycle"
          : TIMES.entrySet().stream().filter(e -> e.getValue().equals(c.time)).map(Map.Entry::getKey).findFirst().orElse(String.valueOf(c.time));
      case "weather" -> WorldConfig.key(c.weather);
      case "difficulty" -> c.difficulty == null ? "server" : c.difficulty.getSerializedName();
      case "mobs" -> c.mobs ? "on" : "off";
      case "nature" -> c.nature ? "on" : "off";
      case "border" -> c.border == 0 ? "default" : String.valueOf(c.border);
      case "member-mode" -> c.memberMode.getName();
      case "visitor-mode" -> c.visitorMode.getName();
      case "visitor-interact" -> c.visitorInteract ? "on" : "off";
      default -> "";
    };
  }

  /** 給玩家看的目前的值。 */
  Component settingValue(Workspace ws, String key) {
    var msg = bg.messages();
    var c = ws.config();
    var v = current(ws, key);
    return switch (key) {
      case "time" -> TIMES.containsKey(v) || v.equals("cycle") ? msg.get("value-time-" + v) : msg.get("value-ticks", "ticks", v);
      case "border" -> msg.get(c.border == 0 ? "value-border-default" : "value-border", "size", ws.borderSize());
      case "border-center" -> msg.get("value-position", "x", (long) Math.floor(c.border == 0 ? 0 : c.borderX), "z",
          (long) Math.floor(c.border == 0 ? 0 : c.borderZ));
      case "spawn" -> c.spawn == null ? msg.get("value-spawn-unset")
          : msg.get("value-position", "x", (long) Math.floor(c.spawn.x()), "z", (long) Math.floor(c.spawn.z()));
      default -> msg.get("value-" + v);
    };
  }

  /** {@code /bg world settings}：每格一個設定，點一下換成下一個值（沒有值的設成自己站的位置），改完重開選單。 */
  void openSettings(ServerPlayer p, Workspace ws) {
    var msg = bg.messages();
    var items = new ArrayList<Platform.MenuItem>();
    items.add(new Platform.MenuItem(4, "name_tag", msg.get("settings-header", "world", ws.label()), msg.get("settings-header-lore"), null));
    var slots = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22);
    var icons = Map.ofEntries(Map.entry("time", "clock"), Map.entry("weather", "water_bucket"), Map.entry("difficulty", "iron_sword"),
        Map.entry("mobs", "zombie_head"), Map.entry("nature", "wheat"), Map.entry("border", "barrier"), Map.entry("border-center", "compass"),
        Map.entry("spawn", "red_bed"), Map.entry("member-mode", "diamond_pickaxe"), Map.entry("visitor-mode", "ender_eye"),
        Map.entry("visitor-interact", "lever"));
    int i = 0;
    for (var e : SETTINGS.entrySet()) {
      var key = e.getKey();
      var values = e.getValue();
      String action;
      if (values.isEmpty()) action = "world settings " + key + " menu";
      else {
        var next = values.get((values.indexOf(current(ws, key)) + 1) % values.size());
        // 超過伺服器上限的邊界跳過
        if (key.equals("border"))
          while (!next.equals("default") && Integer.parseInt(next) > bg.settings().playerWorlds().maxBorder())
            next = values.get((values.indexOf(next) + 1) % values.size());
        action = "world settings " + key + " " + next + " menu";
      }
      var lore = List.of(msg.get("settings-current", "value", settingValue(ws, key)), msg.get("setting-" + key + "-lore"),
          msg.get(values.isEmpty() ? "settings-click-here" : "settings-click-next"));
      items.add(new Platform.MenuItem(slots.get(i++), icons.get(key), msg.get("setting-" + key), lore, action));
    }
    bg.platform().openMenu(p, msg.get("settings-title"), items);
  }

  private void label(List<Platform.MenuItem> items, int slot, String item, String key) {
    items.add(new Platform.MenuItem(slot, item, bg.messages().get(key), bg.messages().get(key + "-lore"), null));
  }

  private void option(List<Platform.MenuItem> items, int slot, String item, String key, boolean selected, BgCommand.NewWorld next) {
    var msg = bg.messages();
    items.add(new Platform.MenuItem(slot, selected ? "lime_stained_glass" : item, msg.get(selected ? "world-option-selected" : "world-option", "text", msg.get(key)),
        msg.get(key + "-lore"), selected ? null : "new " + next.args()));
  }

  /** 最後編輯時間與最近的里程碑，由新到舊、顏色漸暗。 */
  private List<Component> draftLore(Workspace ws, ObjectId head) throws IOException {
    var msg = bg.messages();
    var repo = ws.repo();
    var lore = new ArrayList<Component>();
    lore.add(msg.get("menu-draft-time", "time", BgCommand.TIME.format(Instant.ofEpochMilli(repo.commit(head).time()))));
    int n = 0;
    for (var id : repo.history(head, MILESTONE_SEARCH)) {
      var c = repo.commit(id);
      if (c.auto() || c.parents().isEmpty()) continue;
      lore.add(msg.get("menu-milestone-" + n, "message", Component.text(c.message()), "time", BgCommand.TIME.format(Instant.ofEpochMilli(c.time()))));
      if (++n == MILESTONES) break;
    }
    return lore;
  }

  private void put(List<Platform.MenuItem> items, int slot, String item, String key, String command, ServerPlayer p, String permission) {
    if (permission != null && !bg.platform().hasPermission(p, permission)) return; // 格子空著，其他動作不移位
    var msg = bg.messages();
    items.add(new Platform.MenuItem(slot, item, msg.get(key), msg.get(key + "-lore"), command));
  }

  private static Platform.MenuItem divider(int slot) {
    return new Platform.MenuItem(slot, DIVIDER, Component.empty(), (List<Component>) null, null);
  }
}
