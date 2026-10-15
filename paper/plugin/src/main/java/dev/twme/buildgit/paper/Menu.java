package dev.twme.buildgit.paper;

import dev.twme.buildgit.core.repo.Repository;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /bg} 不帶參數時的箱子選單（設計 §6.6）：常用動作與草稿清單，點一下就執行對應的子指令，
 * 所以權限與行為和打指令完全相同。
 */
final class Menu implements Listener {
  private static final int SIZE = 54;

  private record Holder(Map<Integer, String> actions, Inventory[] inventory) implements InventoryHolder {
    @Override
    public Inventory getInventory() {
      return inventory[0];
    }
  }

  private final BuildGitPlugin plugin;

  Menu(BuildGitPlugin plugin) {
    this.plugin = plugin;
  }

  void open(Player p) {
    var msg = plugin.messages();
    var ws = plugin.workspace();
    var holder = new Holder(new HashMap<>(), new Inventory[1]);
    var inv = Bukkit.createInventory(holder, SIZE, msg.get("menu-title"));
    holder.inventory()[0] = inv;
    var wt = ws.worktree(p.getWorld());
    boolean inDraft = wt != null && !wt.branch().equals(Repository.MAIN) && !Workspace.isScratch(wt.branch());
    boolean inMerge = wt != null && Workspace.isScratch(wt.branch());
    boolean inView = ws.view(p.getWorld()) != null;

    put(inv, holder, 0, Material.BOOK, "menu-status", "status", p, "buildgit.view");
    put(inv, holder, 1, Material.WRITABLE_BOOK, "menu-save", "save", p, "buildgit.save");
    put(inv, holder, 2, Material.CLOCK, "menu-log", "log", p, "buildgit.view");
    put(inv, holder, 3, Material.SPYGLASS, "menu-diff", "diff", p, "buildgit.view");
    put(inv, holder, 4, Material.COMPASS, "menu-inspect", "inspect", p, "buildgit.inspect");
    put(inv, holder, 5, Material.PLAYER_HEAD, "menu-who", "who", p, "buildgit.view");
    put(inv, holder, 6, Material.WOODEN_AXE, "menu-sel", "sel show", p, "buildgit.view");
    if (inDraft) {
      put(inv, holder, 7, Material.EMERALD, "menu-publish", "publish", p, "buildgit.publish");
      put(inv, holder, 8, Material.OAK_DOOR, "menu-back", "back", p, "buildgit.go");
    } else if (inMerge) {
      put(inv, holder, 7, Material.EMERALD, "menu-merge-continue", "merge continue", p, null);
      put(inv, holder, 8, Material.BARRIER, "menu-merge-conflicts", "merge conflicts", p, null);
    } else if (inView) put(inv, holder, 8, Material.OAK_DOOR, "menu-back", "back", p, "buildgit.go");

    int slot = 18;
    try {
      for (var name : ws.drafts().keySet()) {
        if (slot >= SIZE) break;
        var here = wt != null && wt.branch().equals(name);
        var label = name.equals(Repository.MAIN) ? msg.get("branch-main") : msg.get("menu-draft", "name", name);
        var item = item(here ? Material.LIME_STAINED_GLASS : name.equals(Repository.MAIN) ? Material.GRASS_BLOCK : Material.PAPER, label,
            msg.get(here ? "menu-draft-here" : "menu-draft-go"));
        inv.setItem(slot, item);
        if (!here && p.hasPermission("buildgit.go")) holder.actions().put(slot, "go " + name);
        slot++;
      }
    } catch (IOException e) {
      // 清單讀不到時只顯示上排動作
    }
    for (var s : ws.merges().all()) {
      if (slot >= SIZE) break;
      inv.setItem(slot, item(Material.RED_STAINED_GLASS, msg.get("menu-merge", "id", s.id(), "target", s.target()), msg.get("menu-draft-go")));
      holder.actions().put(slot++, "merge view result " + s.id());
    }
    p.openInventory(inv);
  }

  private void put(Inventory inv, Holder holder, int slot, Material material, String key, String command, Player p, String permission) {
    if (permission != null && !p.hasPermission(permission)) return;
    var msg = plugin.messages();
    inv.setItem(slot, item(material, msg.get(key), msg.get(key + "-lore")));
    holder.actions().put(slot, command);
  }

  private static ItemStack item(Material material, Component name, Component lore) {
    var item = new ItemStack(material);
    item.editMeta(m -> {
      m.displayName(name.decoration(TextDecoration.ITALIC, false));
      m.lore(List.of(lore.decoration(TextDecoration.ITALIC, false)));
    });
    return item;
  }

  @EventHandler
  public void onClick(InventoryClickEvent e) {
    if (!(e.getInventory().getHolder(false) instanceof Holder holder)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
    var action = holder.actions().get(e.getSlot());
    if (action == null) return;
    p.closeInventory();
    Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("bg " + action));
  }

  @EventHandler
  public void onDrag(InventoryDragEvent e) {
    if (e.getInventory().getHolder(false) instanceof Holder) e.setCancelled(true);
  }
}
