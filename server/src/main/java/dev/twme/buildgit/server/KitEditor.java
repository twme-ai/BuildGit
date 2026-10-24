package dev.twme.buildgit.server;

import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 起始物品的編輯箱（大箱子，03-worlds §2.2）：上面三排是背包、第四排是快捷欄，第五排是頭盔、胸甲、護腿、靴子、副手，
 * 其餘是說明。關上時存起來（全空時刪掉）。物品是真的放進去、拿出來，不會多出東西。
 */
final class KitEditor {
  private KitEditor() {}

  /** 箱子的每一格對到背包的哪一格；-1 是說明或分隔，不能拿也不能放。 */
  private static final int[] SLOTS = new int[54];
  private static final String[] GEAR = {"head", "chest", "legs", "feet", "offhand"};

  static {
    Arrays.fill(SLOTS, -1);
    for (int i = 0; i < 27; i++) SLOTS[i] = 9 + i;
    for (int i = 0; i < 9; i++) SLOTS[27 + i] = i;
    int[] gear = {39, 38, 37, 36, 40}; // 背包的編號：頭盔、胸甲、護腿、靴子、副手
    for (int i = 0; i < gear.length; i++) SLOTS[36 + i] = gear[i];
  }

  /** 正在編輯的起始物品檔 → 誰開著哪個箱子（主執行緒）。同一份只能一個人編輯，不然兩人各拿走一份就多出物品。 */
  private static final java.util.Map<java.nio.file.Path, Editing> EDITING = new java.util.HashMap<>();

  private record Editing(ServerPlayer player, AbstractContainerMenu menu) {
    /** 那個人已經離線或關掉了（沒收到關閉時也不會一直鎖著）。 */
    boolean stale() {
      return player.hasDisconnected() || player.containerMenu != menu;
    }
  }

  /** 編輯箱用的箱子（認出哪些格子要鎖住）。 */
  private static final class Box extends SimpleContainer {
    Box() {
      super(54);
    }
  }

  /**
   * 角色變了、世界要改名、轉讓、刪除時：沒有資格的人開著的編輯箱立刻關上（照關上那時的內容存下），之後不能再放東西進去。
   * 不在關上時才拒絕寫回：箱子裡的物品就是起始物品本身，不寫回又把物品還給玩家會多出一份。
   */
  static void close(BuildGit bg, Workspace ws, boolean all) {
    var files = java.util.Set.of(ws.kitFile(false).toAbsolutePath().normalize(), ws.kitFile(true).toAbsolutePath().normalize());
    for (var e : java.util.List.copyOf(EDITING.entrySet())) {
      if (!files.contains(e.getKey()) || e.getValue().stale()) continue;
      var p = e.getValue().player();
      var role = ws.role(p.getUUID());
      if (all || !bg.realms().admin(p) && role != WorldConfig.Role.OWNER && role != WorldConfig.Role.MANAGER) p.closeContainer();
    }
  }

  /** 箱子裡的物品放回玩家的背包，放不下的掉在腳下。 */
  private static void giveBack(ServerPlayer p, SimpleContainer items) {
    for (int i = 0; i < items.getContainerSize(); i++) {
      var stack = items.getItem(i);
      if (!stack.isEmpty()) p.getInventory().add(stack); // 放得下的部分從 stack 扣掉
      if (!stack.isEmpty()) p.level().addFreshEntity(new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), stack));
    }
  }

  /** 打開成員或訪客的起始物品；{@code menu} 是從設定選單點進來的，關上後回到設定選單。 */
  static void open(BuildGit bg, ServerPlayer p, Workspace ws, boolean visitor, boolean menu) {
    var msg = bg.messages();
    var key = visitor ? "kit-visitor" : "kit-member";
    var file = ws.kitFile(visitor).toAbsolutePath().normalize();
    var other = EDITING.get(file);
    if (other != null && !other.stale()) {
      msg.send(bg.platform().sender(p), "kit-editing", "player", other.player().getScoreboardName());
      return;
    }
    var kit = Inventories.kit(p, file, bg.workspace().upgrader());
    var box = new Box();
    for (int i = 0; i < SLOTS.length; i++) {
      if (SLOTS[i] >= 0) box.setItem(i, kit.getItem(SLOTS[i]));
      else if (i >= 45 && i < 50) box.setItem(i, label(p, item("white_stained_glass_pane"), msg.get("kit-editor-" + GEAR[i - 45]), null));
      else if (i == 44) box.setItem(i, label(p, item("book"), msg.get("kit-editor-help"), msg.get("kit-editor-help-lore")));
      else box.setItem(i, label(p, item("gray_stained_glass_pane"), Component.empty(), null));
    }
    p.openMenu(new SimpleMenuProvider((id, inventory, player) -> new ChestMenu(MenuType.GENERIC_9x6, id, inventory, box, 6) {
      {
        EDITING.put(file, new Editing(p, this));
      }

      @Override
      protected Slot addSlot(Slot slot) {
        if (slot.container instanceof Box && SLOTS[slot.getContainerSlot()] < 0)
          slot = new Slot(slot.container, slot.getContainerSlot(), slot.x, slot.y) {
            @Override
            public boolean mayPickup(Player player) {
              return false;
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
              return false;
            }
          };
        return super.addSlot(slot);
      }

      @Override
      public void removed(Player player) {
        super.removed(player);
        var editing = EDITING.get(file);
        if (editing != null && editing.menu() == this) EDITING.remove(file);
        var out = new SimpleContainer(Inventories.SLOTS);
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] >= 0) out.setItem(SLOTS[i], box.getItem(i));
        try {
          Inventories.writeKit(p, file, out);
        } catch (IOException e) {
          // 存不了時箱子裡的物品放回玩家的背包，放不下的掉在腳下
          bg.platform().log().log(Level.WARNING, "無法存下 " + ws.label() + " 的起始物品", e);
          giveBack(p, out);
          msg.send(bg.platform().sender(p), "kit-save-failed");
          return;
        }
        if (menu) bg.platform().later(1, () -> {
          if (!p.hasDisconnected() && bg.workspace(p.level()) == ws) bg.menu().openSettings(p, ws);
        });
        else msg.send(bg.platform().sender(p), "settings-changed", "setting", msg.get("setting-" + key), "value", bg.menu().settingValue(ws, key));
      }
    }, vanilla(p, msg.get("kit-editor-title", "setting", msg.get("setting-" + key), "world", ws.label()))));
  }

  /** 說明或分隔用的物品；沒有說明時不顯示提示框以外的東西（分隔連名稱都不顯示）。 */
  private static ItemStack label(ServerPlayer p, Item item, Component name, Component lore) {
    var stack = new ItemStack(item);
    stack.set(DataComponents.CUSTOM_NAME, vanilla(p, name.decoration(TextDecoration.ITALIC, false)));
    if (lore != null)
      stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(vanilla(p, lore.decoration(TextDecoration.ITALIC, false)))));
    else if (name.equals(Component.empty())) stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new LinkedHashSet<>()));
    return stack;
  }

  /** 這個版本沒有那個物品時是紙（版本間改名）。 */
  private static Item item(String id) {
    var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
    return item == Items.AIR ? Items.PAPER : item;
  }

  /** Adventure 的文字轉成原版的（經過 JSON）。 */
  private static net.minecraft.network.chat.Component vanilla(ServerPlayer p, Component text) {
    var json = GsonComponentSerializer.gson().serializeToTree(text);
    return ComponentSerialization.CODEC.parse(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();
  }
}
