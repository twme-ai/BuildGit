package dev.twme.buildgit.server;

import dev.twme.buildgit.server.nms.DataUpgrader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

/**
 * 背包隔離與起始物品（03-worlds §2.2）存的檔案：背包、終界箱、經驗、狀態效果。物品存成原版物品的 NBT（{@code ItemStack} 的寫法），
 * 連同寫下時的資料版本，讀取時用 DataFixer 升級；經驗與狀態效果用 BuildGit 自己的寫法（效果寫 id），不隨版本變。
 * 起始物品只有背包。
 */
final class Inventories {
  private Inventories() {}

  /** 身上的全部：背包、終界箱、經驗、狀態效果。 */
  static CompoundTag capture(ServerPlayer p) {
    var tag = items(p);
    tag.put("ender", slots(p, p.getEnderChestInventory()));
    var xp = new CompoundTag();
    xp.putInt("level", p.experienceLevel);
    xp.putFloat("progress", p.experienceProgress);
    xp.putInt("total", p.totalExperience);
    tag.put("xp", xp);
    var effects = new ListTag();
    for (var e : p.getActiveEffects()) {
      var id = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value());
      if (id == null) continue;
      var t = new CompoundTag();
      t.putString("id", id.toString());
      t.putInt("duration", e.getDuration());
      t.putInt("amplifier", e.getAmplifier());
      t.putBoolean("ambient", e.isAmbient());
      t.putBoolean("particles", e.isVisible());
      t.putBoolean("icon", e.showIcon());
      effects.add(t);
    }
    tag.put("effects", effects);
    return tag;
  }

  /** 只有背包（起始物品）。 */
  static CompoundTag items(ServerPlayer p) {
    var tag = new CompoundTag();
    tag.putInt("DataVersion", dev.twme.buildgit.server.nms.LiveReader.dataVersion());
    tag.put("items", slots(p, p.getInventory()));
    return tag;
  }

  /** 換上存下的那一份；沒有的部分清空（{@code tag} 為 null 時全部清空）。 */
  static void restore(ServerPlayer p, CompoundTag tag, DataUpgrader upgrader) {
    if (tag == null) tag = new CompoundTag();
    int from = tag.getIntOr("DataVersion", upgrader.current());
    fill(p, p.getInventory(), tag.getListOrEmpty("items"), from, upgrader);
    fill(p, p.getEnderChestInventory(), tag.getListOrEmpty("ender"), from, upgrader);
    var xp = tag.getCompoundOrEmpty("xp");
    p.experienceLevel = xp.getIntOr("level", 0);
    p.experienceProgress = xp.getFloatOr("progress", 0);
    p.totalExperience = xp.getIntOr("total", 0);
    p.giveExperienceLevels(0); // 讓伺服器重送經驗條
    p.removeAllEffects();
    for (var t : tag.getListOrEmpty("effects")) {
      if (!(t instanceof CompoundTag e)) continue;
      var id = Identifier.tryParse(e.getStringOr("id", ""));
      var effect = id == null ? null : BuiltInRegistries.MOB_EFFECT.get(id).orElse(null);
      if (effect == null) continue; // 新版本拿掉的效果
      p.addEffect(new MobEffectInstance(effect, e.getIntOr("duration", 0), e.getIntOr("amplifier", 0), e.getBooleanOr("ambient", false),
          e.getBooleanOr("particles", true), e.getBooleanOr("icon", true)));
    }
    p.inventoryMenu.broadcastChanges();
  }

  /** 檔案裡有幾件物品（給選單顯示起始物品）；沒有檔案時是 -1。 */
  static int count(Path file) {
    var tag = read(file);
    return tag == null ? -1 : tag.getListOrEmpty("items").size();
  }

  static CompoundTag read(Path file) {
    if (!Files.isRegularFile(file)) return null;
    try {
      return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    } catch (IOException e) {
      return null;
    }
  }

  static void write(Path file, CompoundTag tag) throws IOException {
    Files.createDirectories(file.getParent());
    var tmp = file.resolveSibling(file.getFileName() + ".tmp");
    NbtIo.writeCompressed(tag, tmp);
    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  private static ListTag slots(ServerPlayer p, Container c) {
    var ops = p.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    var list = new ListTag();
    for (int i = 0; i < c.getContainerSize(); i++) {
      var stack = c.getItem(i);
      if (stack.isEmpty()) continue;
      int slot = i;
      ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(item -> {
        var t = new CompoundTag();
        t.putInt("slot", slot);
        t.put("item", item);
        list.add(t);
      });
    }
    return list;
  }

  private static void fill(ServerPlayer p, Container c, ListTag list, int from, DataUpgrader upgrader) {
    var ops = p.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    c.clearContent();
    for (Tag t : list) {
      if (!(t instanceof CompoundTag e)) continue;
      int slot = e.getIntOr("slot", -1);
      var item = e.getCompoundOrEmpty("item");
      if (slot < 0 || slot >= c.getContainerSize()) continue;
      ItemStack.CODEC.parse(ops, upgrader.item(item, from)).result().ifPresent(stack -> c.setItem(slot, stack));
    }
  }
}
