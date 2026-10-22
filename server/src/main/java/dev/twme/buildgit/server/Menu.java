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
    // 分隔線右端：建築世界清單，在建築世界裡（擁有者與管理者）多一個設定
    put(items, second + 8, "filled_map", "menu-worlds", "worlds", p, null);
    var role = ws.role(p.getUUID());
    if (ws.buildWorld() != null && (role == WorldConfig.Role.OWNER || role == WorldConfig.Role.MANAGER || bg.realms().admin(p)))
      put(items, second + 7, "comparator", "menu-world-settings", "world settings", p, null);
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
    platform.openMenu(p, msg.get("menu-title", "world", ws.label()), items);
  }

  /**
   * 建立建築世界的對話框（03-worlds §3）：名稱、類型、地形、規則、種子、超平坦的層、虛空腳下的方塊一次填好，按「建立」執行
   * {@code new … confirm}。有範本時多一個範本的選項，選了範本就照它，類型、地形、規則不看。沒開放的類型、地形不列（管理員都列）。
   */
  void openCreate(ServerPlayer p, BgCommand.NewWorld o) {
    var msg = bg.messages();
    var pw = bg.settings().playerWorlds();
    boolean admin = bg.realms().admin(p);
    var inputs = new ArrayList<com.google.gson.JsonObject>();
    inputs.add(Dialogs.text("name", msg.get("world-create-name"), o.name(), 32));
    var types = new ArrayList<Dialogs.Option>();
    for (var t : WorldConfig.Type.values())
      if (admin || pw.allowed(t)) types.add(new Dialogs.Option(WorldConfig.key(t), msg.get("world-type-" + WorldConfig.key(t))));
    inputs.add(Dialogs.choice("type", msg.get("world-create-type"), types, WorldConfig.key(o.type())));
    var terrains = new ArrayList<Dialogs.Option>();
    for (var t : WorldConfig.Terrain.values())
      if (t != WorldConfig.Terrain.SERVER && (admin || pw.allowed(t))) terrains.add(new Dialogs.Option(WorldConfig.key(t), msg.get("world-terrain-" + WorldConfig.key(t))));
    inputs.add(Dialogs.choice("terrain", msg.get("world-create-terrain"), terrains, WorldConfig.key(o.terrain())));
    var rules = new ArrayList<Dialogs.Option>();
    for (var r : WorldConfig.Rules.values()) rules.add(new Dialogs.Option(WorldConfig.key(r), msg.get("world-rules-" + WorldConfig.key(r))));
    inputs.add(Dialogs.choice("rules", msg.get("world-create-rules"), rules, WorldConfig.key(o.rules())));
    var command = "bg new name:$(name) type:$(type) terrain:$(terrain) rules:$(rules) seed:$(seed) layers:$(layers) block:$(block)";
    var templates = templates();
    if (!templates.isEmpty()) {
      var list = new ArrayList<Dialogs.Option>();
      list.add(new Dialogs.Option("none", msg.get("world-create-no-template")));
      for (var t : templates) list.add(new Dialogs.Option(t.label(), Component.text(t.config().displayName != null ? t.config().displayName : t.label())));
      inputs.add(Dialogs.choice("template", msg.get("world-create-template"), list, o.template() == null ? "none" : o.template()));
      command += " template:$(template)";
    }
    inputs.add(Dialogs.text("seed", msg.get("world-create-seed"), o.seed(), 64));
    inputs.add(Dialogs.text("layers", msg.get("world-create-layers"), o.layers(), 256));
    inputs.add(Dialogs.text("block", msg.get("world-create-block"), o.block(), 64));
    Dialogs.open(bg, p, msg.get("world-create-title"), msg.get("world-create-body"), inputs, msg.get("world-create-confirm"), msg.get("dialog-cancel"),
        command + " confirm");
  }

  /** {@code player-worlds.templates} 裡找得到的，以及管理員設成範本的建築世界；版本庫要開著。 */
  List<Workspace> templates() {
    var result = new ArrayList<Workspace>();
    for (var spec : bg.settings().playerWorlds().templates()) {
      var ws = spec.contains("/") ? bg.worlds().find(spec, null) : null;
      if (ws != null && ws.buildWorld() != null && ws.isOpen() && !result.contains(ws)) result.add(ws);
    }
    for (var ws : bg.worlds().buildWorlds())
      if (ws.isOpen() && ws.config() != null && ws.config().template && !result.contains(ws)) result.add(ws);
    return result;
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
    SETTINGS.put("respawn", List.of("off", "on"));
    SETTINGS.put("member-mode", List.of("creative", "survival", "adventure"));
    SETTINGS.put("visitor-mode", List.of("spectator", "adventure"));
    SETTINGS.put("visitor-interact", List.of("off", "on"));
    SETTINGS.put("inventory", List.of("off", "on"));
    // 起始物品：不帶值是存下身上的背包，clear 是清空
    SETTINGS.put("kit-member", List.of());
    SETTINGS.put("kit-visitor", List.of());
    // 清單上的樣子：顯示名稱與簡介是文字，圖示不帶值是手上的物品
    SETTINGS.put("display-name", List.of());
    SETTINGS.put("description", List.of());
    SETTINGS.put("icon", List.of());
    SETTINGS.put("allow-fork", List.of("off", "on"));
    SETTINGS.put("accept-submissions", List.of("off", "on"));
  }

  /** 玩家寫的文字設定（值是後面所有的字，保留大小寫）。 */
  static final Set<String> TEXT_SETTINGS = Set.of("display-name", "description");
  static final String DEFAULT_ICON = "minecraft:grass_block";

  /** 清單上的圖示；沒設、或這個版本沒有那個物品（例如降版）時是草方塊。 */
  static String icon(Workspace ws) {
    var icon = ws.config() == null ? null : ws.config().icon;
    var id = icon == null ? null : net.minecraft.resources.Identifier.tryParse(icon);
    return id != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id) ? icon : DEFAULT_ICON;
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
      case "inventory" -> ws.isolated() ? "on" : "off";
      case "allow-fork" -> c.allowFork ? "on" : "off";
      case "respawn" -> c.respawn ? "on" : "off";
      case "accept-submissions" -> c.acceptSubmissions ? "on" : "off";
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
      case "inventory" -> bg.settings().playerWorlds().separateInventories() ? msg.get("value-inventory-forced") : msg.get("value-" + v);
      case "kit-member", "kit-visitor" -> {
        int n = Inventories.count(ws.kitFile(key.equals("kit-visitor")));
        yield n <= 0 ? msg.get("value-kit-empty") : msg.get("value-kit", "count", n);
      }
      case "display-name" -> c.displayName == null ? msg.get("value-display-name-unset", "name", ws.buildWorld().name()) : Component.text(c.displayName);
      case "description" -> c.description == null ? msg.get("value-unset") : Component.text(c.description);
      case "icon" -> Component.text(icon(ws));
      default -> msg.get("value-" + v);
    };
  }

  /** {@code /bg world settings}：每格一個設定，點一下換成下一個值（沒有值的設成自己站的位置），改完重開選單。 */
  void openSettings(ServerPlayer p, Workspace ws) {
    var msg = bg.messages();
    var items = new ArrayList<Platform.MenuItem>();
    var header = new ArrayList<>(List.of(msg.get("settings-header-lore")));
    if (!bg.worlds().kept(ws).isEmpty()) header.add(msg.get("settings-kept", "branches", String.join("、", bg.worlds().kept(ws))));
    items.add(new Platform.MenuItem(4, "name_tag", msg.get("settings-header", "world", ws.label()), header, null));
    var slots = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33);
    var icons = Map.ofEntries(Map.entry("time", "clock"), Map.entry("weather", "water_bucket"), Map.entry("difficulty", "iron_sword"),
        Map.entry("mobs", "zombie_head"), Map.entry("nature", "wheat"), Map.entry("border", "barrier"), Map.entry("border-center", "compass"),
        Map.entry("spawn", "red_bed"), Map.entry("respawn", "respawn_anchor"), Map.entry("member-mode", "diamond_pickaxe"), Map.entry("visitor-mode", "ender_eye"),
        Map.entry("visitor-interact", "lever"), Map.entry("inventory", "chest"), Map.entry("kit-member", "bundle"), Map.entry("kit-visitor", "map"),
        Map.entry("display-name", "name_tag"), Map.entry("description", "writable_book"), Map.entry("icon", icon(ws)),
        Map.entry("allow-fork", "cartography_table"), Map.entry("accept-submissions", "hopper"));
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
          key.startsWith("kit-") ? msg.get("settings-click-kit", "key", key)
              : TEXT_SETTINGS.contains(key) ? msg.get("settings-click-text", "key", key)
              : key.equals("icon") ? msg.get("settings-click-icon")
              : msg.get(values.isEmpty() ? "settings-click-here" : "settings-click-next"));
      items.add(new Platform.MenuItem(slots.get(i++), icons.get(key), msg.get("setting-" + key), lore, action));
    }
    for (int c = 36; c < 45; c++) if (c != 40 && c != 41) items.add(divider(c));
    items.add(new Platform.MenuItem(40, "player_head", msg.get("settings-members"), msg.get("settings-members-lore"), "world members"));
    items.add(new Platform.MenuItem(41, "paper", msg.get("settings-submissions"), msg.get("settings-submissions-lore"), "world submissions"));
    bg.platform().openMenu(p, msg.get("settings-title", "world", ws.label()), items);
  }

  /** {@code /bg world submissions}：還沒處理的提出，點一下進去看（和草稿一樣）。最多 36 個。 */
  void openSubmissions(ServerPlayer p, Workspace ws, List<Submissions.Submission> list) {
    var msg = bg.messages();
    var items = new ArrayList<Platform.MenuItem>();
    for (int i = 0; i < Math.min(36, list.size()); i++) {
      var s = list.get(i);
      var from = s.from() == null ? null : bg.worlds().byId(s.from());
      var lore = new ArrayList<Component>();
      lore.add(msg.get("submissions-item-player", "player", s.name(), "time", BgCommand.TIME.format(Instant.ofEpochMilli(s.time()))));
      if (from != null) lore.add(msg.get("submissions-item-from", "world", from.label()));
      for (int at = 0; at < s.message().length(); at += DESCRIPTION_LINE)
        lore.add(msg.get("worlds-item-description", "text", s.message().substring(at, Math.min(s.message().length(), at + DESCRIPTION_LINE))));
      lore.add(msg.get("submissions-item-click"));
      lore.add(msg.get("submissions-item-reject", "branch", s.branch()));
      items.add(new Platform.MenuItem(i, "paper", msg.get("submissions-item", "branch", s.branch()), lore, "go " + s.branch()));
    }
    if (list.isEmpty()) items.add(new Platform.MenuItem(13, "barrier", msg.get("submissions-none"), List.of(), null));
    for (int c = 36; c < 45; c++) if (c != 40) items.add(divider(c));
    items.add(new Platform.MenuItem(40, "arrow", msg.get("members-menu-back"), List.of(), "world settings"));
    bg.platform().openMenu(p, msg.get("submissions-title", "world", ws.label()), items);
  }

  /** {@code /bg worlds} 的分頁。 */
  static final List<String> WORLD_TABS = List.of("mine", "public", "recent");

  /**
   * {@code /bg worlds [mine|public|recent] [頁]}（03-worlds §2.1）：最上面三排是世界，點一下進去；下一排是翻頁，
   * 最下排左邊是分頁（我的：自己的與受邀的；公開：依最近存檔；最近去過），右邊是建立、回收區、這個世界的設定與回去。
   */
  void openWorlds(ServerPlayer p, String tab, int page) {
    var msg = bg.messages();
    var realms = bg.realms();
    List<Workspace> list = switch (tab) {
      case "public" -> bg.worlds().buildWorlds().stream().filter(ws -> ws.config() != null && ws.config().open)
          .sorted(Comparator.comparingLong(Menu::lastSave).reversed()).toList();
      case "recent" -> realms.recent(p.getUUID()).stream().map(bg.worlds()::byId)
          .filter(ws -> ws != null && realms.canEnter(p, ws)).toList();
      default -> bg.worlds().buildWorlds().stream().filter(ws -> {
        var r = ws.role(p.getUUID());
        return r != null && r != WorldConfig.Role.VISITOR;
      }).sorted(Comparator.comparing(Workspace::label)).toList();
    };
    int capacity = LIST_ROWS * 9, pages = Math.max(1, Math.ceilDiv(list.size(), capacity));
    page = Math.clamp(page, 1, pages);
    var items = new ArrayList<Platform.MenuItem>();
    int i = 0;
    for (var ws : list.subList((page - 1) * capacity, Math.min(list.size(), page * capacity))) items.add(worldItem(p, ws, i++));
    if (list.isEmpty()) items.add(new Platform.MenuItem(13, "light_gray_stained_glass_pane", msg.get("worlds-empty-" + tab), msg.get("worlds-empty-lore"), null));
    int row = (ROWS - 2) * 9, bottom = (ROWS - 1) * 9;
    for (int c = 0; c < 9; c++) items.add(divider(row + c));
    if (page > 1) items.set(items.size() - 9, new Platform.MenuItem(row, "arrow", msg.get("worlds-prev"), msg.get("worlds-page", "page", page, "pages", pages), "worlds " + tab + " " + (page - 1)));
    if (page < pages) items.set(items.size() - 1, new Platform.MenuItem(row + 8, "arrow", msg.get("worlds-next"), msg.get("worlds-page", "page", page, "pages", pages), "worlds " + tab + " " + (page + 1)));
    var tabIcons = List.of("chest", "filled_map", "clock");
    for (int t = 0; t < WORLD_TABS.size(); t++) {
      var name = WORLD_TABS.get(t);
      boolean here = name.equals(tab);
      items.add(new Platform.MenuItem(bottom + t, here ? "lime_stained_glass" : tabIcons.get(t),
          msg.get(here ? "world-option-selected" : "world-option", "text", msg.get("worlds-tab-" + name)), msg.get("worlds-tab-" + name + "-lore"), here ? null : "worlds " + name));
    }
    items.add(divider(bottom + 3));
    var pw = bg.settings().playerWorlds();
    if (realms.admin(p) || pw.enabled() && bg.platform().hasPermission(p, "buildgit.world.create")) put(items, bottom + 4, "emerald", "worlds-create", "new", p, null);
    items.add(divider(bottom + 5));
    put(items, bottom + 6, "cauldron", "worlds-trash", "world undelete", p, null);
    var here = bg.workspace(p.level());
    var role = here.role(p.getUUID());
    if (here.buildWorld() != null && (role == WorldConfig.Role.OWNER || role == WorldConfig.Role.MANAGER || realms.admin(p)))
      put(items, bottom + 7, "comparator", "menu-world-settings", "world settings", p, null);
    if (here.buildWorld() != null) put(items, bottom + 8, "oak_door", "worlds-home", "home", p, null);
    bg.platform().openMenu(p, msg.get("worlds-title", "tab", msg.get("worlds-tab-" + tab)), items);
  }

  /** 清單上的一個世界：圖示、顯示名稱、名稱、簡介、擁有者與自己的角色、人數、最後存檔。 */
  private Platform.MenuItem worldItem(ServerPlayer p, Workspace ws, int slot) {
    var msg = bg.messages();
    var c = ws.config();
    var lore = new ArrayList<Component>();
    lore.add(msg.get("worlds-item-label", "world", ws.label()));
    if (c != null && c.description != null)
      // 選單的說明不會自動換行
      for (int at = 0; at < c.description.length(); at += DESCRIPTION_LINE)
        lore.add(msg.get("worlds-item-description", "text", c.description.substring(at, Math.min(c.description.length(), at + DESCRIPTION_LINE))));
    var role = ws.role(p.getUUID());
    lore.add(msg.get("worlds-item-owner", "owner", ws.buildWorld().ownerName(), "role", msg.get("role-" + (role == null ? "visitor" : WorldConfig.key(role)))));
    int players = 0;
    boolean loaded = false;
    for (var wt : ws.worktrees())
      for (var level : wt.worlds().values()) {
        loaded = true;
        players += level.players().size();
      }
    lore.add(msg.get("worlds-item-state", "state", msg.get(!ws.isOpen() ? "worlds-broken" : loaded ? "worlds-loaded" : "worlds-unloaded"), "players", players));
    long saved = lastSave(ws);
    if (saved > 0) lore.add(msg.get("worlds-item-saved", "time", BgCommand.TIME.format(Instant.ofEpochMilli(saved))));
    var from = c == null || c.forkId == null || c.forkTemplate ? null : bg.worlds().byId(c.forkId);
    if (from != null) lore.add(msg.get("worlds-item-fork", "world", from.label()));
    if (c != null && c.unlisted) lore.add(msg.get("worlds-item-unlisted"));
    lore.add(msg.get("worlds-item-click"));
    var name = c != null && c.displayName != null ? c.displayName : ws.buildWorld().name();
    return new Platform.MenuItem(slot, icon(ws), msg.get("worlds-item", "name", name), lore, "open " + ws.label());
  }

  /** 選單裡簡介每行幾個字。 */
  private static final int DESCRIPTION_LINE = 24;

  /** 主世界最後一次存檔的時間；讀不到時是 0。 */
  private static long lastSave(Workspace ws) {
    if (!ws.isOpen()) return 0;
    try {
      return ws.repo().commit(ws.repo().head(Repository.MAIN)).time();
    } catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  /**
   * {@code /bg world members}：最上面是世界與可見性（擁有者點一下切換），中間兩排是成員（點一下調整角色或移除），
   * 下一排是線上還不是成員的玩家（點一下邀請成建造者），最下排回設定。
   */
  void openMembers(ServerPlayer p, Workspace ws) {
    var msg = bg.messages();
    var c = ws.access();
    boolean server = ws.buildWorld() == null;
    var role = server ? ws.serverRole(p.getUUID()) : ws.role(p.getUUID());
    boolean owner = role == WorldConfig.Role.OWNER || bg.realms().admin(p);
    boolean manager = owner || role == WorldConfig.Role.MANAGER;
    var items = new ArrayList<Platform.MenuItem>();
    items.add(new Platform.MenuItem(4, "name_tag", msg.get("settings-header", "world", ws.label()),
        msg.get("members-menu-owner", "owner", server ? msg.raw("members-server-owner") : ws.buildWorld().ownerName()), null));
    items.add(new Platform.MenuItem(8, c.open ? "ender_eye" : "ender_pearl", msg.get(c.open ? "visibility-public" : "visibility-private"),
        List.of(msg.get(c.open ? "members-menu-public-lore" : "members-menu-private-lore"), msg.get(owner ? "settings-click-next" : "members-menu-owner-only")),
        owner ? "world visibility " + (c.open ? "private" : "public") + " menu" : null));
    int slot = 9;
    for (var m : c.members.values()) {
      if (slot >= 27) break;
      boolean editable = owner || manager && m.role() == WorldConfig.Role.BUILDER;
      items.add(new Platform.MenuItem(slot++, "player_head", Component.text(m.name()),
          List.of(msg.get("members-menu-role", "role", msg.get("role-" + WorldConfig.key(m.role()))), msg.get(editable ? "members-menu-edit" : "members-menu-owner-only")),
          editable ? "world members edit " + m.name() : null));
    }
    if (c.members.isEmpty()) items.add(new Platform.MenuItem(13, "light_gray_stained_glass_pane", msg.get("members-menu-none"), msg.get("members-menu-none-lore"), null));
    slot = 27;
    if (manager)
      for (var online : bg.platform().server().getPlayerList().getPlayers()) {
        if (slot >= 36) break;
        if (online == p || !server && ws.buildWorld().owner().equals(online.getUUID()) || c.members.containsKey(online.getUUID())) continue;
        items.add(new Platform.MenuItem(slot++, "player_head", Component.text(online.getScoreboardName()), msg.get("members-menu-invite"),
            "world members add " + online.getScoreboardName() + " menu"));
      }
    for (int x = 36; x < 45; x++) if (x != 40 || server) items.add(divider(x));
    // 伺服器世界沒有設定選單
    if (!server) items.add(new Platform.MenuItem(40, "oak_door", msg.get("members-menu-back"), (List<Component>) null, "world settings"));
    bg.platform().openMenu(p, msg.get("members-menu-title", "world", ws.label()), items);
  }

  /** 一位成員：設成建造者、管理者（只有擁有者能設），或移出成員。 */
  void openMember(ServerPlayer p, Workspace ws, UUID member) {
    var msg = bg.messages();
    var m = ws.access().members.get(member);
    boolean owner = ws.role(p.getUUID()) == WorldConfig.Role.OWNER || bg.realms().admin(p);
    var items = new ArrayList<Platform.MenuItem>();
    items.add(new Platform.MenuItem(4, "player_head", Component.text(m.name()), msg.get("members-menu-role", "role", msg.get("role-" + WorldConfig.key(m.role()))), null));
    for (var r : List.of(WorldConfig.Role.BUILDER, WorldConfig.Role.MANAGER)) {
      boolean selected = m.role() == r, can = owner || r == WorldConfig.Role.BUILDER;
      items.add(new Platform.MenuItem(r == WorldConfig.Role.BUILDER ? 20 : 22, selected ? "lime_stained_glass" : r == WorldConfig.Role.BUILDER ? "iron_pickaxe" : "golden_helmet",
          msg.get(selected ? "world-option-selected" : "world-option", "text", msg.get("role-" + WorldConfig.key(r))), msg.get("role-" + WorldConfig.key(r) + "-lore"),
          selected || !can ? null : "world members add " + m.name() + " " + WorldConfig.key(r) + " menu"));
    }
    items.add(new Platform.MenuItem(24, "barrier", msg.get("members-menu-remove"), msg.get("members-menu-remove-lore"), "world members remove " + m.name() + " menu"));
    for (int x = 36; x < 45; x++) if (x != 40) items.add(divider(x));
    items.add(new Platform.MenuItem(40, "oak_door", msg.get("members-menu-back-list"), (List<Component>) null, "world members"));
    bg.platform().openMenu(p, msg.get("member-menu-title", "player", m.name(), "world", ws.label()), items);
  }

  private void label(List<Platform.MenuItem> items, int slot, String item, String key) {
    items.add(new Platform.MenuItem(slot, item, bg.messages().get(key), bg.messages().get(key + "-lore"), null));
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
