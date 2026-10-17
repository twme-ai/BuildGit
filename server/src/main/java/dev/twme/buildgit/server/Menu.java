package dev.twme.buildgit.server;

import dev.twme.buildgit.core.repo.Repository;
import java.io.IOException;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bg} 不帶參數時的箱子選單（設計 §6.6）：常用動作與草稿清單，點一下就執行對應的子指令，
 * 所以權限與行為和打指令完全相同。畫面由平台負責（Paper 的物品欄、Fabric 的箱子選單）。
 *
 * <p>排列靠近游標：開啟容器時游標在視窗正中央，6 排箱子的畫面正中央落在箱子最下排的中間（第 49 格）。
 * 所以最常用的動作放在最下排、從中間往兩側排，情境動作在上一排，草稿清單接在動作上方，每排都置中。
 * 固定 6 排，游標的位置才不會跟著內容變。
 */
final class Menu {
  private static final int ROWS = 6;
  /** 草稿清單可用的排數（動作佔最下兩排）。 */
  private static final int LIST_ROWS = ROWS - 2;
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

    // 最下排（游標所在）：最常用的五個，狀態在正中間
    var bottom = new ArrayList<Platform.MenuItem>();
    put(bottom, "spyglass", "menu-diff", "diff", p, "buildgit.diff");
    put(bottom, "writable_book", "menu-save", "save", p, "buildgit.save");
    put(bottom, "book", "menu-status", "status", p, "buildgit.status");
    put(bottom, "clock", "menu-log", "log", p, "buildgit.log");
    put(bottom, "compass", "menu-inspect", "inspect", p, "buildgit.inspect");
    // 上一排：草稿／整合／舊版本裡的動作在中間，其他放兩側
    var second = new ArrayList<Platform.MenuItem>();
    put(second, "player_head", "menu-who", "who", p, "buildgit.who");
    if (inDraft) {
      put(second, "oak_door", "menu-back", "back", p, "buildgit.back");
      put(second, "emerald", "menu-publish", "publish", p, "buildgit.publish"); // 狀態正上方
    } else if (inMerge) {
      put(second, "barrier", "menu-merge-conflicts", "merge conflicts", null, null);
      put(second, "emerald", "menu-merge-continue", "merge continue", null, null);
    } else if (inView) put(second, "oak_door", "menu-back", "back", p, "buildgit.back");
    put(second, "wooden_axe", "menu-sel", "sel show", p, "buildgit.sel");

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

    var items = new ArrayList<Platform.MenuItem>();
    row(items, ROWS - 1, bottom);
    row(items, ROWS - 2, second);
    // 草稿清單貼著動作往上長：一排九個，最後不滿的一排也置中
    var shown = list.subList(0, Math.min(list.size(), LIST_ROWS * 9));
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

  private void put(List<Platform.MenuItem> items, String item, String key, String command, ServerPlayer p, String permission) {
    if (permission != null && !bg.platform().hasPermission(p, permission)) return;
    var msg = bg.messages();
    items.add(new Platform.MenuItem(0, item, msg.get(key), msg.get(key + "-lore"), command));
  }
}
