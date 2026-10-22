package dev.twme.buildgit.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    boolean luckPermsContexts,
    long syncMaxBlocks,
    int syncRadius,
    long lazyCopyAboveMb,
    String genesisDir,
    PlayerWorlds playerWorlds) {

  /**
   * 建築世界（03-worlds §5.1、§6.1）。路徑是範本，佔位符在建立世界時代入。
   *
   * @param serverWorldName 伺服器世界在指令裡的名稱；空白時用 {@code level-name}
   * @param worldName 世界資料夾名稱的範本
   */
  /**
   * @param enabled 一般玩家能不能建立建築世界；@param maxPerPlayer 每人最多擁有幾個；@param border 世界邊界預設的邊長；
   * @param maxBorder 擁有者能設定的上限
   * @param separateInventories 所有建築世界都隔離背包
   * @param allowInventoryIsolation 擁有者能不能為自己的世界打開背包隔離
   * @param trashDays 刪除後在回收區保留幾天
   * @param maxLoaded 同時載入的建築世界（含草稿、檢視）上限
   * @param allowedTypes 一般玩家能選的類型；空的是全部
   * @param allowedTerrain 一般玩家能選的地形；空的是全部
   * @param templates 可以當範本的建築世界（{@code 擁有者/名稱}）
   */
  public record PlayerWorlds(boolean enabled, int maxPerPlayer, int border, int maxBorder, boolean separateInventories,
      boolean allowInventoryIsolation, int trashDays, int maxLoaded, Set<WorldConfig.Type> allowedTypes, Set<WorldConfig.Terrain> allowedTerrain,
      List<String> templates, String serverWorldName, String worldName, String repo, String objects, String genesis) {
    boolean allowed(WorldConfig.Type type) {
      return allowedTypes.isEmpty() || allowedTypes.contains(type);
    }

    boolean allowed(WorldConfig.Terrain terrain) {
      return allowedTerrain.isEmpty() || allowedTerrain.contains(terrain);
    }
  }

  /** 列出的選項；寫錯的略過並警告。 */
  private static <E extends Enum<E>> Set<E> options(Yaml c, String path, Class<E> type, Logger log) {
    var set = EnumSet.noneOf(type);
    for (var s : c.getStringList(path)) {
      var e = WorldConfig.parse(type, s);
      if (e == null) log.warning(path + " 裡的 " + s + " 不認得，略過");
      else set.add(e);
    }
    return Collections.unmodifiableSet(set);
  }

  /** 讀取 {@code dir/config.yml}；不存在時先寫入內建的預設檔，缺少新版本的設定時補上。 */
  public static Settings load(Path dir, Logger log) {
    var file = dir.resolve("config.yml");
    try (var in = Settings.class.getResourceAsStream("/config.yml")) {
      if (in != null) {
        var template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Files.createDirectories(dir);
        if (!Files.exists(file)) Files.writeString(file, template);
        else update(file, template, log);
      }
    } catch (IOException e) {
      log.warning("無法寫入設定檔 " + file + "：" + e.getMessage());
    }
    return of(Yaml.load(file), log);
  }

  /**
   * 外掛更新後，內建的預設檔有使用者的檔案沒有的設定時：以預設檔為範本（新的設定與說明），填回使用者設的值，
   * 原本的檔案留在 {@code config.yml.old}。讀不懂的檔案不動。
   */
  private static void update(Path file, String template, Logger log) throws IOException {
    Yaml user;
    try {
      user = Yaml.read(file);
    } catch (IOException e) {
      log.warning("config.yml 讀不懂，沒有補上新的設定：" + e.getMessage());
      return;
    }
    var added = new ArrayList<String>();
    for (var path : Yaml.load(new java.io.ByteArrayInputStream(template.getBytes(StandardCharsets.UTF_8))).paths())
      if (user.get(path) == null) added.add(path);
    if (added.isEmpty()) return;
    Yaml.replace(file, Yaml.fill(template, user::get));
    log.info("config.yml 補上了新的設定 " + String.join("、", added) + "；原本的檔案留在 config.yml.old");
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
        c.getBoolean("integrations.luckperms-contexts", true),
        Math.max(1, c.getLong("sync.max-blocks", 12_000_000)),
        Math.max(1, c.getInt("sync.radius", 80)),
        c.getLong("init.lazy-copy-above-mb", -1),
        c.getString("init.genesis-dir", ""),
        new PlayerWorlds(c.getBoolean("player-worlds.enabled", false), Math.max(0, c.getInt("player-worlds.max-per-player", 3)),
            Math.clamp(c.getInt("player-worlds.border", 1024), 16, 59_999_968),
            Math.clamp(c.getInt("player-worlds.max-border", 4096), 16, 59_999_968),
            c.getBoolean("player-worlds.separate-inventories", false), c.getBoolean("player-worlds.allow-inventory-isolation", true),
            Math.max(0, c.getInt("player-worlds.trash-days", 7)),
            Math.max(1, c.getInt("player-worlds.max-loaded", 20)),
            options(c, "player-worlds.allowed-types", WorldConfig.Type.class, log),
            options(c, "player-worlds.allowed-terrain", WorldConfig.Terrain.class, log),
            List.copyOf(c.getStringList("player-worlds.templates")),
            c.getString("player-worlds.server-world-name", "").strip(),
            c.getString("player-worlds.world-name", "bw_{owner-name}_{name}").strip(),
            c.getString("player-worlds.paths.repo", "{buildgit}/worlds/{id}").strip(),
            c.getString("player-worlds.paths.objects", "{buildgit}/objects").strip(),
            c.getString("player-worlds.paths.genesis", "{repo}/genesis").strip()));
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
