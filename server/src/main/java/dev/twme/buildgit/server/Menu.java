package dev.twme.buildgit.server;

import dev.twme.buildgit.core.repo.Repository;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bg} 不帶參數時的箱子選單（設計 §6.6）：常用動作與草稿清單，點一下就執行對應的子指令，
 * 所以權限與行為和打指令完全相同。畫面由平台負責（Paper 的物品欄、Fabric 的箱子選單）。
 *
 * <p>排列：開啟容器時游標在視窗正中央，6 排箱子的正中央落在最下排的中間（第 49 格）。
 * 動作在最下兩排，依功能分成三組，以玻璃片隔開：左邊是歷史（歷史、檢查），中間是這個世界的工作
 * （標出變更、狀態、存檔，上一排是併回／回去或整合的動作，狀態在游標上），右邊是玩家與範圍（誰在哪裡、選取範圍）。
 * 每個動作的位置固定，沒有權限時那格空著，不讓其他動作移位。上面隔一排是草稿清單，貼著分隔線往上排、每排置中。
 */
final class Menu {
  private static final int ROWS = 6;
  /** 草稿清單的排數：動作佔最下兩排，再隔一排分隔線。 */
  private static final int LIST_ROWS = ROWS - 3;
  private static final String DIVIDER = "gray_stained_glass_pane";
  private final BuildGit bg;

  Menu(BuildGit bg) {
    this.bg = bg;
  }

  void open(ServerPlayer p) {
    var msg = bg.messages();
    var ws = bg.workspace();
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
    // 分隔線：三組之間的直線，與草稿清單之間的橫線
    for (int slot : new int[] {second + 2, second + 6, bottom + 2, bottom + 6}) items.add(divider(slot));
    for (int c = 0; c < 9; c++) items.add(divider(LIST_ROWS * 9 + c));

    var list = new ArrayList<Platform.MenuItem>();
    try {
      String dim = ws.logicalDimension(p.level());
      for (var name : ws.drafts().keySet()) {
        if (!ws.covers(name, dim)) continue; // 只列這個世界的草稿
        var here = wt != null && wt.branch().equals(name);
        var label = name.equals(Repository.MAIN) ? msg.get("branch-main") : msg.get("menu-draft", "name", name);
        var item = here ? "lime_stained_glass" : name.equals(Repository.MAIN) ? "grass_block" : "paper";
        var action = !here && platform.hasPermission(p, "buildgit.go") ? "go " + name : null;
        list.add(new Platform.MenuItem(0, item, label, msg.get(here ? "menu-draft-here" : "menu-draft-go"), action));
      }
    } catch (IOException e) {
      // 清單讀不到時只顯示動作
    }
    for (var s : ws.merges().all())
      list.add(new Platform.MenuItem(0, "red_stained_glass", msg.get("menu-merge", "id", s.id(), "target", s.target()),
          msg.get("menu-draft-go"), "merge view result " + s.id()));

    // 草稿清單貼著分隔線往上長：一排九個，最後不滿的一排也置中；放不下時最後一格提示還有幾個
    int capacity = LIST_ROWS * 9;
    var shown = new ArrayList<>(list.subList(0, Math.min(list.size(), capacity)));
    if (list.size() > capacity)
      shown.set(capacity - 1, new Platform.MenuItem(0, "book", msg.get("menu-more", "count", String.valueOf(list.size() - capacity + 1)),
          msg.get("menu-more-lore"), platform.hasPermission(p, "buildgit.branch.list") ? "branch list" : null));
    int rows = (shown.size() + 8) / 9;
    for (int r = 0; r < rows; r++)
      row(items, LIST_ROWS - rows + r, shown.subList(r * 9, Math.min(shown.size(), r * 9 + 9)));
    platform.openMenu(p, msg.get("menu-title"), items);
  }

  /** 一排置中擺放。 */
  private static void row(List<Platform.MenuItem> into, int row, List<Platform.MenuItem> items) {
    int start = row * 9 + (9 - items.size()) / 2;
    for (int i = 0; i < items.size(); i++) {
      var it = items.get(i);
      into.add(new Platform.MenuItem(start + i, it.item(), it.name(), it.lore(), it.action()));
    }
  }

  private void put(List<Platform.MenuItem> items, int slot, String item, String key, String command, ServerPlayer p, String permission) {
    if (permission != null && !bg.platform().hasPermission(p, permission)) return; // 格子空著，其他動作不移位
    var msg = bg.messages();
    items.add(new Platform.MenuItem(slot, item, msg.get(key), msg.get(key + "-lore"), command));
  }

  private static Platform.MenuItem divider(int slot) {
    return new Platform.MenuItem(slot, DIVIDER, Component.empty(), null, null);
  }
}
