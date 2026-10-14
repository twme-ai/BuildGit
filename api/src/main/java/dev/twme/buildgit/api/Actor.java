package dev.twme.buildgit.api;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

/**
 * 變更的作者。通常是玩家；其他插件也可以用自己的 UUID 與名稱代表非玩家的操作者（例如 NPC 建築工）。
 *
 * @param uuid 作者 UUID，歷史與 blame 以它識別作者
 * @param name 顯示名稱
 */
public record Actor(UUID uuid, String name) {
  public Actor {
    Objects.requireNonNull(uuid, "uuid");
    Objects.requireNonNull(name, "name");
  }

  public static Actor of(OfflinePlayer player) {
    String name = player.getName();
    return new Actor(player.getUniqueId(), name == null ? player.getUniqueId().toString() : name);
  }
}
