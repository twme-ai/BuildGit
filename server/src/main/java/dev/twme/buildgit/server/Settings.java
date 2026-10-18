package dev.twme.buildgit.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** config.yml 的內容。實體類型以 id（{@code minecraft:item}）表示。 */
public record Settings(
    String language,
    long cascadeWindow,
    int maxDistance,
    Set<String> ignoredEntities,
    Set<String> propagatingEntities,
    long autosaveIdleTicks,
    long autosaveMaxTicks,
    int journalFlushTicks,
    long unloadIdleTicks,
    boolean worldEdit,
    boolean axiom,
    boolean debugStickPro,
    long syncMaxBlocks,
    long lazyCopyAboveMb) {

  /** 讀取 {@code dir/config.yml}；不存在時先寫入內建的預設檔。 */
  public static Settings load(Path dir, Logger log) {
    var file = dir.resolve("config.yml");
    if (!Files.exists(file))
      try (var in = Settings.class.getResourceAsStream("/config.yml")) {
        Files.createDirectories(dir);
        if (in != null) Files.copy(in, file);
      } catch (IOException e) {
        log.warning("無法寫入預設設定檔 " + file + "：" + e.getMessage());
      }
    return of(Yaml.load(file), log);
  }

  public static Settings of(Yaml c, Logger log) {
    return new Settings(
        c.getString("language", "zh_tw"),
        Math.max(1, c.getLong("tracking.cascade-window", 200)),
        Math.max(1, c.getInt("tracking.max-distance", 256)),
        types(c.getStringList("tracking.ignored-entities"), log),
        types(c.getStringList("tracking.propagating-entities"), log),
        Math.max(0, c.getLong("autosave.idle-seconds", 300)) * 20,
        Math.max(0, c.getLong("autosave.max-interval-seconds", 1800)) * 20,
        Math.max(1, c.getInt("tracking.journal-flush-ticks", 20)),
        unloadIdle(c.getLong("worlds.unload-idle-seconds", 60)),
        c.getBoolean("integrations.worldedit", true),
        c.getBoolean("integrations.axiom", true),
        c.getBoolean("integrations.debugstickpro", true),
        Math.max(1, c.getLong("sync.max-blocks", 2_000_000)),
        c.getLong("init.lazy-copy-above-mb", -1));
  }

  /** 負數表示不卸載；否則至少 30 秒：開世界加傳送可能要好幾秒，太短會在玩家抵達前就卸載。 */
  private static long unloadIdle(long seconds) {
    return seconds < 0 ? -1 : Math.max(30, seconds) * 20;
  }

  private static Set<String> types(List<String> names, Logger log) {
    var result = new HashSet<String>();
    for (var name : names) {
      var id = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
      if (id == null || BuiltInRegistries.ENTITY_TYPE.getOptional(id).isEmpty()) log.warning("config.yml：未知的實體類型 " + name);
      else result.add(id.toString());
    }
    return Set.copyOf(result);
  }
}
