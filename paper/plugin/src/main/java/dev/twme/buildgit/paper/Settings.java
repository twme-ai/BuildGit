package dev.twme.buildgit.paper;

import java.util.*;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/** config.yml 的內容。 */
public record Settings(
    String language,
    long cascadeWindow,
    int maxDistance,
    Set<EntityType> ignoredEntities,
    Set<EntityType> propagatingEntities,
    long autosaveIdleTicks,
    long autosaveMaxTicks,
    int journalFlushTicks,
    boolean worldEdit,
    boolean axiom) {

  public static Settings load(ConfigurationSection c, Logger log) {
    return new Settings(
        c.getString("language", "zh_tw"),
        Math.max(1, c.getLong("tracking.cascade-window", 200)),
        Math.max(1, c.getInt("tracking.max-distance", 256)),
        types(c.getStringList("tracking.ignored-entities"), log),
        types(c.getStringList("tracking.propagating-entities"), log),
        Math.max(0, c.getLong("autosave.idle-seconds", 300)) * 20,
        Math.max(0, c.getLong("autosave.max-interval-seconds", 1800)) * 20,
        Math.max(1, c.getInt("tracking.journal-flush-ticks", 20)),
        c.getBoolean("integrations.worldedit", true),
        c.getBoolean("integrations.axiom", true));
  }

  private static Set<EntityType> types(List<String> names, Logger log) {
    var result = EnumSet.noneOf(EntityType.class);
    for (var name : names) {
      var key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
      var type = key == null ? null : Registry.ENTITY_TYPE.get(key);
      if (type == null) log.warning("config.yml：未知的實體類型 " + name);
      else result.add(type);
    }
    return result;
  }
}
