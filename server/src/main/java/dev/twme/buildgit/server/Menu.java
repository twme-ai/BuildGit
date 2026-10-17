package dev.twme.buildgit.server;

import dev.twme.buildgit.core.repo.Repository;
import java.io.IOException;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bg} 不帶參數時的箱子選單（設計 §6.6）：常用動作與草稿清單，點一下就執行對應的子指令，
 * 所以權限與行為和打指令完全相同。畫面由平台負責（Paper 的物品欄、Fabric 的箱子選單）。
 */
final class Menu {
  private static final int SIZE = 54;
  private final BuildGit bg;

  Menu(BuildGit bg) {
    this.bg = bg;
  }

  void open(ServerPlayer p) {
    var msg = bg.messages();
    var ws = bg.workspace();
    var platform = bg.platform();
    var items = new ArrayList<Platform.MenuItem>();
    var wt = ws.worktree(p.level());
    boolean inDraft = wt != null && !wt.branch().equals(Repository.MAIN) && !Workspace.isScratch(wt.branch());
    boolean inMerge = wt != null && Workspace.isScratch(wt.branch());
    boolean inView = ws.view(p.level()) != null;

    put(items, 0, "book", "menu-status", "status", p, "buildgit.status");
    put(items, 1, "writable_book", "menu-save", "save", p, "buildgit.save");
    put(items, 2, "clock", "menu-log", "log", p, "buildgit.log");
    put(items, 3, "spyglass", "menu-diff", "diff", p, "buildgit.diff");
    put(items, 4, "compass", "menu-inspect", "inspect", p, "buildgit.inspect");
    put(items, 5, "player_head", "menu-who", "who", p, "buildgit.who");
    put(items, 6, "wooden_axe", "menu-sel", "sel show", p, "buildgit.sel");
    if (inDraft) {
      put(items, 7, "emerald", "menu-publish", "publish", p, "buildgit.publish");
      put(items, 8, "oak_door", "menu-back", "back", p, "buildgit.back");
    } else if (inMerge) {
      put(items, 7, "emerald", "menu-merge-continue", "merge continue", p, null);
      put(items, 8, "barrier", "menu-merge-conflicts", "merge conflicts", p, null);
    } else if (inView) put(items, 8, "oak_door", "menu-back", "back", p, "buildgit.back");

    int slot = 18;
    try {
      for (var name : ws.drafts().keySet()) {
        if (slot >= SIZE) break;
        var here = wt != null && wt.branch().equals(name);
        var label = name.equals(Repository.MAIN) ? msg.get("branch-main") : msg.get("menu-draft", "name", name);
        var item = here ? "lime_stained_glass" : name.equals(Repository.MAIN) ? "grass_block" : "paper";
        var action = !here && platform.hasPermission(p, "buildgit.go") ? "go " + name : null;
        items.add(new Platform.MenuItem(slot++, item, label, msg.get(here ? "menu-draft-here" : "menu-draft-go"), action));
      }
    } catch (IOException e) {
      // 清單讀不到時只顯示上排動作
    }
    for (var s : ws.merges().all()) {
      if (slot >= SIZE) break;
      items.add(new Platform.MenuItem(slot++, "red_stained_glass", msg.get("menu-merge", "id", s.id(), "target", s.target()),
          msg.get("menu-draft-go"), "merge view result " + s.id()));
    }
    platform.openMenu(p, msg.get("menu-title"), items);
  }

  private void put(List<Platform.MenuItem> items, int slot, String item, String key, String command, ServerPlayer p, String permission) {
    if (permission != null && !bg.platform().hasPermission(p, permission)) return;
    var msg = bg.messages();
    items.add(new Platform.MenuItem(slot, item, msg.get(key), msg.get(key + "-lore"), command));
  }
}
