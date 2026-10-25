package dev.twme.buildgit.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;

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
    String lobby,
    List<String> keepLoaded,
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
   * @param worldName 世界資料夾名稱的範本；{@code draftWorldName}、{@code mergeWorldName}、{@code viewWorldName} 是草稿、
   *     衝突整合、檢視世界的
   * @param enabled 一般玩家能不能建立建築世界；@param maxPerPlayer 每人最多擁有幾個；@param border 世界邊界預設的邊長；
   * @param maxBorder 擁有者能設定的上限
   * @param separateInventories 所有建築世界都隔離背包（{@code inventory-isolation: always}）
   * @param allowInventoryIsolation 擁有者能不能為自己的世界打開背包隔離（不是 {@code never}）
   * @param trashDays 刪除後在回收區保留幾天
   * @param maxLoaded 同時載入的建築世界（含草稿、檢視）上限
   * @param allowedTypes 一般玩家能選的類型；空的是全部
   * @param allowedTerrain 一般玩家能選的地形；空的是全部
   * @param templates 可以當範本的建築世界（{@code 擁有者/名稱}）
   * @param unmanaged 交給其他插件管的世界規則（{@link #UNMANAGEABLE} 裡的）：BuildGit 不設定，擁有者也不能改
   * @param defaults 新世界的設定
   */
  public record PlayerWorlds(boolean enabled, int maxPerPlayer, int border, int maxBorder, boolean separateInventories,
      boolean allowInventoryIsolation, int trashDays, int maxLoaded, Set<WorldConfig.Type> allowedTypes, Set<WorldConfig.Terrain> allowedTerrain,
      List<String> templates, String serverWorldName, String worldName, String repo, String objects, String genesis,
      String uploadConnection, String uploadEndpoint, long uploadMaxBytes, Set<String> unmanaged, Defaults defaults,
      String draftWorldName, String mergeWorldName, String viewWorldName) {
    /** 可以交給其他插件管的設定：都是遊戲規則、時間、天氣、難度這類別的插件也會設的。 */
    static final List<String> UNMANAGEABLE = List.of("time", "weather", "difficulty", "mobs", "nature");

    /** BuildGit 管不管這個設定。 */
    boolean manages(String key) {
      return !unmanaged.contains(key);
    }

    boolean allowed(WorldConfig.Type type) {
      return allowedTypes.isEmpty() || allowedTypes.contains(type);
    }

    boolean allowed(WorldConfig.Terrain terrain) {
      return allowedTerrain.isEmpty() || allowedTerrain.contains(terrain);
    }
  }

  /** 新建立的建築世界一開始的設定（{@code player-worlds.defaults}），建立後擁有者還能改。 */
  public record Defaults(WorldConfig.Rules rules, GameType memberMode, GameType visitorMode, boolean visitorInteract, boolean inventoryMember,
      boolean inventoryVisitor, boolean open, boolean allowFork, boolean acceptSubmissions, boolean respawn) {
    /** 套用到剛建立的世界（規則預設由建立時選的決定）。 */
    void apply(WorldConfig c) {
      c.memberMode = memberMode;
      c.visitorMode = visitorMode;
      c.visitorInteract = visitorInteract;
      c.inventoryMember = inventoryMember;
      c.inventoryVisitor = inventoryVisitor;
      c.open = open;
      c.allowFork = allowFork;
      c.acceptSubmissions = acceptSubmissions;
      c.respawn = respawn;
    }
  }

  /** 舊版的設定，讀取時換成新的寫法（{@link #migrated}），補設定時從檔案拿掉。 */
  private static final Set<String> RENAMED = Set.of("player-worlds.separate-inventories", "player-worlds.allow-inventory-isolation");

  /** 舊版的檔案沒有 {@code path}、但有它取代的舊設定時，照舊設定換算的值；否則 null。 */
  private static Object migrated(Yaml c, String path) {
    if (path.equals("player-worlds.inventory-isolation") && RENAMED.stream().anyMatch(c::contains))
      return c.getBoolean("player-worlds.separate-inventories", false) ? "always"
          : c.getBoolean("player-worlds.allow-inventory-isolation", true) ? "owner" : "never";
    return null;
  }

  private static Object value(Yaml c, String path) {
    var v = c.get(path);
    return v != null ? v : migrated(c, path);
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
   * 外掛更新後，內建的預設檔有使用者的檔案沒有的設定時：以預設檔為範本（新的設定與說明、順序），填回使用者設的值
   * （改了名的換成新的寫法），原本的檔案留在 {@code config.yml.old}。讀不懂的檔案不動。認不得的設定（多半是打錯字）警告。
   */
  private static void update(Path file, String template, Logger log) throws IOException {
    Yaml user;
    try {
      user = Yaml.read(file);
    } catch (IOException e) {
      log.warning("config.yml 讀不懂，沒有補上新的設定：" + e.getMessage());
      return;
    }
    var known = Yaml.load(new java.io.ByteArrayInputStream(template.getBytes(StandardCharsets.UTF_8))).paths();
    var added = new ArrayList<String>();
    for (var path : known) if (user.get(path) == null && migrated(user, path) == null) added.add(path);
    var unknown = new ArrayList<String>();
    for (var path : user.paths()) if (!known.contains(path) && !RENAMED.contains(path)) unknown.add(path);
    boolean renamed = RENAMED.stream().anyMatch(user::contains);
    if (!added.isEmpty() || renamed) {
      Yaml.replace(file, Yaml.fill(template, path -> value(user, path)));
      if (!added.isEmpty()) log.info("config.yml 補上了新的設定 " + String.join("、", added) + "；原本的檔案留在 config.yml.old");
      if (renamed) log.info("config.yml 的 separate-inventories、allow-inventory-isolation 換成了 inventory-isolation；原本的檔案留在 config.yml.old");
    }
    for (var path : unknown)
      log.warning("config.yml 裡的 " + path + " 不認得（打錯字或放錯層？），沒有作用" + (!added.isEmpty() || renamed ? "，已從檔案拿掉（原本的在 config.yml.old）" : ""));
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
        c.getString("worlds.lobby", "").strip(),
        List.copyOf(c.getStringList("worlds.keep-loaded")),
        c.getBoolean("integrations.worldedit", true),
        c.getBoolean("integrations.axiom", true),
        c.getBoolean("integrations.debugstickpro", true),
        c.getBoolean("integrations.luckperms-contexts", true),
        Math.max(1, c.getLong("sync.max-blocks", 12_000_000)),
        Math.max(1, c.getInt("sync.radius", 80)),
        c.getLong("init.lazy-copy-above-mb", 512),
        c.getString("init.genesis-dir", ""),
        new PlayerWorlds(c.getBoolean("player-worlds.enabled", false), Math.max(0, c.getInt("player-worlds.max-per-player", 3)),
            Math.clamp(c.getInt("player-worlds.border", 1024), 16, 59_999_968),
            Math.clamp(c.getInt("player-worlds.max-border", 4096), 16, 59_999_968),
            isolation(c, log).equals("always"), !isolation(c, log).equals("never"),
            Math.max(0, c.getInt("player-worlds.trash-days", 7)),
            Math.max(1, c.getInt("player-worlds.max-loaded", 20)),
            options(c, "player-worlds.allowed-types", WorldConfig.Type.class, log),
            options(c, "player-worlds.allowed-terrain", WorldConfig.Terrain.class, log),
            List.copyOf(c.getStringList("player-worlds.templates")),
            c.getString("player-worlds.server-world-name", "").strip(),
            c.getString("player-worlds.world-name", "bw_{owner-name}_{name}").strip(),
            c.getString("player-worlds.paths.repo", "{buildgit}/worlds/{id}").strip(),
            c.getString("player-worlds.paths.objects", "{buildgit}/objects").strip(),
            c.getString("player-worlds.paths.genesis", "{repo}/genesis").strip(),
            c.getString("player-worlds.upload.connection", "").strip(),
            c.getString("player-worlds.upload.public-endpoint", "").strip(),
            Math.max(1, c.getLong("player-worlds.upload.max-mb", 512)) * 1024 * 1024,
            unmanaged(c, log), defaults(c, log),
            folderTemplate(c, "draft-world-name", "{world}-{draft}", "{draft}", log),
            folderTemplate(c, "merge-world-name", "{world}-{draft}", "{draft}", log),
            folderTemplate(c, "view-world-name", "bgview_{world}_{commit}", "{commit}", log)));
  }

  /** 草稿、整合、檢視世界的資料夾範本少了必要的佔位符時警告（用到時才拒絕）。 */
  private static String folderTemplate(Yaml c, String key, String fallback, String required, Logger log) {
    var t = c.getString("player-worlds." + key, fallback).strip();
    if (!t.contains(required) || !t.contains("{world}") && !t.contains("{id}"))
      log.warning("player-worlds." + key + " 必須包含 " + required + "，以及 {world} 或 {id}（現在是 " + t + "）；建立這種世界時會失敗");
    return t;
  }

  /** {@code player-worlds.inventory-isolation}：owner、always 或 never；舊版的兩個開關照舊讀。 */
  private static String isolation(Yaml c, Logger log) {
    var v = String.valueOf(Objects.requireNonNullElse(value(c, "player-worlds.inventory-isolation"), "owner")).strip().toLowerCase(Locale.ROOT);
    if (List.of("owner", "always", "never").contains(v)) return v;
    log.warning("player-worlds.inventory-isolation 只能是 owner、always 或 never（現在是 " + v + "），用 owner");
    return "owner";
  }

  private static Defaults defaults(Yaml c, Logger log) {
    var p = "player-worlds.defaults.";
    var rules = WorldConfig.parse(WorldConfig.Rules.class, c.getString(p + "rules", "build"));
    if (rules == null) {
      log.warning(p + "rules 只能是 build 或 vanilla，用 build");
      rules = WorldConfig.Rules.BUILD;
    }
    var visibility = c.getString(p + "visibility", "private").strip().toLowerCase(Locale.ROOT);
    if (!visibility.equals("public") && !visibility.equals("private")) log.warning(p + "visibility 只能是 public 或 private，用 private");
    return new Defaults(rules, mode(c, p + "member-mode", WorldConfig.MEMBER_MODES, log), mode(c, p + "visitor-mode", WorldConfig.VISITOR_MODES, log),
        c.getBoolean(p + "visitor-interact", false), c.getBoolean(p + "inventory-member", false), c.getBoolean(p + "inventory-visitor", false),
        visibility.equals("public"), c.getBoolean(p + "allow-fork", true), c.getBoolean(p + "accept-submissions", true), c.getBoolean(p + "respawn", true));
  }

  /** 遊戲模式；沒寫時是 {@code allowed} 的第一個，寫錯時警告。 */
  private static GameType mode(Yaml c, String path, List<GameType> allowed, Logger log) {
    var name = c.getString(path);
    var m = name == null ? null : GameType.byName(name.strip().toLowerCase(Locale.ROOT), null);
    if (m != null && allowed.contains(m)) return m;
    if (name != null)
      log.warning(path + " 只能是 " + String.join("、", allowed.stream().map(GameType::getName).toList()) + "，用 " + allowed.getFirst().getName());
    return allowed.getFirst();
  }

  private static Set<String> unmanaged(Yaml c, Logger log) {
    var set = new HashSet<String>();
    for (var s : c.getStringList("player-worlds.unmanaged-settings")) {
      var key = s.strip().toLowerCase(Locale.ROOT);
      if (PlayerWorlds.UNMANAGEABLE.contains(key)) set.add(key);
      else log.warning("player-worlds.unmanaged-settings 裡的 " + s + " 不認得，略過（可以寫 " + String.join("、", PlayerWorlds.UNMANAGEABLE) + "）");
    }
    return Set.copyOf(set);
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
