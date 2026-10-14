package dev.twme.buildgit.core.nbt;

import java.util.Set;

/**
 * 把方塊實體與實體的 NBT 變成可比較的形式：拿掉位置相關或每次存檔都會變的欄位。
 * 規則取自 WorldGit 已驗證的正規化（WorldGit docs/02 §3），只保留建築會用到的部分。
 */
public final class Normalizer {
  private Normalizer() {}

  /** 方塊實體：座標由所在格子決定；這些欄位與建築內容無關。 */
  private static final Set<String> BLOCK_ENTITY_DROP =
      Set.of("x", "y", "z", "keepPacked", "LootTableSeed", "Bukkit.PublicBukkitValues");

  /** 實體的暫態欄位。 */
  private static final Set<String> ENTITY_DROP =
      Set.of(
          "Motion",
          "FallDistance",
          "fall_distance",
          "Fire",
          "Air",
          "OnGround",
          "PortalCooldown",
          "HurtTime",
          "HurtByTimestamp",
          "DeathTime",
          "Age",
          "Brain",
          "Paper.Origin",
          "Paper.OriginWorld",
          "Paper.SpawnReason",
          "Spigot.ticksLived",
          "Bukkit.updateLevel",
          "Bukkit.Aware",
          "WorldUUIDLeast",
          "WorldUUIDMost");

  /** 熔爐、釀造台、生怪磚等的倒數欄位：會自己跑，不是建築內容。 */
  private static final Set<String> BLOCK_ENTITY_TRANSIENT =
      Set.of(
          "BurnTime",
          "lit_time_remaining",
          "CookTime",
          "cooking_time_spent",
          "BrewTime",
          "Fuel",
          "Delay",
          "TransferCooldown",
          "ticks_since_song");

  public static Nbt.Compound blockEntity(Nbt.Compound raw) {
    var c = Nbt.copy(raw);
    BLOCK_ENTITY_DROP.forEach(c::remove);
    BLOCK_ENTITY_TRANSIENT.forEach(c::remove);
    return c;
  }

  public static Nbt.Compound entity(Nbt.Compound raw) {
    var c = Nbt.copy(raw);
    ENTITY_DROP.forEach(c::remove);
    c.keySet().removeIf(k -> k.startsWith("Paper.") || k.startsWith("Spigot."));
    return c;
  }
}
