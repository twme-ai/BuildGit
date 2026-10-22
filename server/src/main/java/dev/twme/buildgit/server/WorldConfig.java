package dev.twme.buildgit.server;

import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.Upgrader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

/**
 * 建築世界自己的設定（03-worlds §3、§4），存在版本庫的 {@code repo.yml}：建立時決定的類型與地形（之後不能改）、
 * 建立後可以改的設定（時間、天氣、邊界、遊戲模式…）、出生點、可見性與成員。用 BuildGit 自己的寫法存（超平坦的層是方塊 id
 * 與層數，時間是刻數，模式與難度寫名稱），不用遊戲規則的名稱（1.21.11 起改了寫法），連同寫下時的資料版本；讀取時把方塊與
 * 生態域的 id 升級到伺服器目前的版本（03-worlds §6.4）。
 */
public final class WorldConfig {
  public static final String FILE = "repo.yml";

  public enum Type { OVERWORLD, NETHER, END }

  /** {@code SERVER}：第一階段建立的世界，照伺服器主世界的產生器。 */
  public enum Terrain { NORMAL, LARGE_BIOMES, AMPLIFIED, FLAT, VOID, SERVER }

  /** 建立時的規則預設，決定下面那些設定的初始值。{@code BUILD}：固定正午、晴天、和平、不生成生物、沒有自然變化。 */
  public enum Rules { BUILD, VANILLA }

  public enum Weather { CLEAR, RAIN, THUNDER, CYCLE }

  /** 角色往下包含：擁有者也是管理者與建造者。訪客不記在成員名單裡。 */
  public enum Role { OWNER, MANAGER, BUILDER, VISITOR }

  public record Layer(String block, int count) {}

  /** 產生世界要的全部資訊，交給平台建立世界；{@code layers} 由下往上，只在超平坦與虛空時用。 */
  public record Gen(Type type, Terrain terrain, long seed, List<Layer> layers, String biome) {}

  public record Spawn(double x, double y, double z, float yaw, float pitch) {}

  public record Member(String name, Role role) {}

  public Type type = Type.OVERWORLD;
  public Terrain terrain = Terrain.SERVER;
  public long seed;
  public List<Layer> layers = List.of();
  public String biome;
  /** 虛空世界建立時放在出生點腳下的方塊。 */
  public String block;
  public Rules rules = Rules.VANILLA;

  // ---- 建立後可以改的設定（03-worlds §3） ----

  /** 固定的時刻（0～23999 刻，6000 是正午）；null 是正常流動。 */
  public Integer time;
  public Weather weather = Weather.CYCLE;
  /** null 是照伺服器的難度。 */
  public Difficulty difficulty;
  /** 生成生物（含怪物、巡邏隊、幻影、流浪商人、伏守者、突襲）。 */
  public boolean mobs = true;
  /** 自然變化：隨機刻（作物、冰、樹葉）、火焰蔓延、生物破壞方塊、藤蔓蔓延。 */
  public boolean nature = true;
  public GameType memberMode = GameType.CREATIVE;
  /** 旁觀者或冒險（03-worlds §4.1）。 */
  public GameType visitorMode = GameType.SPECTATOR;
  /** 冒險模式的訪客能不能按按鈕、拉桿、開門、踩壓力板。 */
  public boolean visitorInteract;
  /** 背包隔離（03-worlds §2.2）；伺服器設定 {@code separate-inventories} 時一律隔離。 */
  public boolean inventory;
  /** 訪客能不能 fork（公開時才有訪客）。 */
  public boolean allowFork = true;
  /** 接不接受 fork 的世界提出併回。 */
  public boolean acceptSubmissions = true;
  /** 世界邊界的邊長；0 是伺服器的預設（{@code player-worlds.border}）。 */
  public int border;
  public double borderX, borderZ;
  /** 傳送進來時出現的地方；還沒決定（剛建立、還沒有人進去）時為 null。 */
  public Spawn spawn;
  /** 死在這個世界的人在出生點重生（自己的床、重生錨在這個世界時照原版）；關掉時照原版回伺服器世界。 */
  public boolean respawn = true;
  public boolean open;
  /** 管理員下架（03-worlds §4）：強制私人，擁有者不能自己改回公開。 */
  public boolean unlisted;
  /** 管理員設成範本（{@code /bg world template on}）：和 {@code player-worlds.templates} 列的一樣出現在建立對話框。 */
  public boolean template;
  /** 清單上顯示的名稱、簡介（玩家寫的文字，顯示時不當 MiniMessage）；沒有時是 null。 */
  public String displayName, description;
  /** 清單上的圖示（物品 id）；沒有時是 null（草方塊）。 */
  public String icon;
  public final Map<UUID, Member> members = new LinkedHashMap<>();
  /** fork 的來源（世界 id）與當時來源 main 的存檔；從範本建立的不顯示「複製自」。 */
  public String forkId, forkCommit;
  public boolean forkTemplate;

  // ---- 名稱 ----

  static String key(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  static <E extends Enum<E>> E parse(Class<E> type, String s) {
    if (s == null) return null;
    for (var e : type.getEnumConstants()) if (key(e).equals(s.toLowerCase(Locale.ROOT))) return e;
    return null;
  }

  /** 照規則預設設定時間、天氣、難度、生物與自然變化。 */
  void preset(Rules r) {
    rules = r;
    boolean build = r == Rules.BUILD;
    time = build ? 6000 : null;
    weather = build ? Weather.CLEAR : Weather.CYCLE;
    difficulty = build ? Difficulty.PEACEFUL : null;
    mobs = !build;
    nature = !build;
  }

  Gen gen() {
    return new Gen(type, terrain, seed, terrain == Terrain.FLAT ? layers : List.of(), biome);
  }

  /** 每種類型的超平坦預設（和原版「經典平坦」相同的想法）。 */
  /** 預設的層：底下墊空氣，地面在 y=64（主世界從 -64、地獄與終界從 0 往上疊）。 */
  static List<Layer> defaultLayers(Type type) {
    return switch (type) {
      case OVERWORLD -> List.of(new Layer("minecraft:air", 125), new Layer("minecraft:bedrock", 1), new Layer("minecraft:dirt", 2),
          new Layer("minecraft:grass_block", 1));
      case NETHER -> List.of(new Layer("minecraft:air", 62), new Layer("minecraft:bedrock", 1), new Layer("minecraft:netherrack", 2));
      case END -> List.of(new Layer("minecraft:air", 62), new Layer("minecraft:end_stone", 3));
    };
  }

  static String defaultBiome(Type type, Terrain terrain) {
    if (terrain == Terrain.VOID) return "minecraft:the_void";
    return switch (type) {
      case OVERWORLD -> "minecraft:plains";
      case NETHER -> "minecraft:nether_wastes";
      case END -> "minecraft:the_end";
    };
  }

  /**
   * 原版超平坦「預設代碼」的寫法：{@code minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block;minecraft:plains}，
   * 由下往上，生態域可省略，命名空間可省略。格式不對時丟出 {@link IllegalArgumentException}。
   */
  static List<Layer> parseLayers(String code, StringBuilder biome) {
    var parts = code.split(";", -1);
    if (parts.length > 2 || parts[0].isBlank()) throw new IllegalArgumentException(code);
    var layers = new ArrayList<Layer>();
    for (var s : parts[0].split(",")) {
      s = s.strip();
      int star = s.indexOf('*');
      int count = star < 0 ? 1 : Integer.parseInt(s.substring(0, star).strip());
      var block = id(star < 0 ? s : s.substring(star + 1).strip());
      if (count < 1 || block.isEmpty()) throw new IllegalArgumentException(s);
      layers.add(new Layer(block, count));
    }
    if (parts.length == 2 && !parts[1].isBlank()) biome.append(id(parts[1].strip()));
    return layers;
  }

  static String id(String s) {
    s = s.toLowerCase(Locale.ROOT);
    return s.contains(":") ? s : "minecraft:" + s;
  }

  static String formatLayers(List<Layer> layers) {
    var sb = new StringBuilder();
    for (var l : layers) {
      if (!sb.isEmpty()) sb.append(',');
      if (l.count() > 1) sb.append(l.count()).append('*');
      sb.append(l.block());
    }
    return sb.toString();
  }

  // ---- 讀寫 ----

  /** 沒有 {@code repo.yml}（第一階段建立的世界）時是照伺服器主世界產生、原版規則、只有擁有者的私人世界。 */
  static WorldConfig load(Path dir, Upgrader upgrader) {
    var c = new WorldConfig();
    var y = Yaml.load(dir.resolve(FILE));
    int from = y.getInt("data-version", upgrader.current());
    c.type = Objects.requireNonNullElse(parse(Type.class, y.getString("generation.type")), Type.OVERWORLD);
    c.terrain = Objects.requireNonNullElse(parse(Terrain.class, y.getString("generation.terrain")), Terrain.SERVER);
    c.seed = y.getLong("generation.seed", 0);
    if (y.get("generation.layers") instanceof List<?> list) {
      var layers = new ArrayList<Layer>();
      for (var o : list)
        if (o instanceof Map<?, ?> m && m.get("block") instanceof String b)
          layers.add(new Layer(upgrader.cell(new Cell(b, null), from).state(), m.get("count") instanceof Number n ? n.intValue() : 1));
      c.layers = List.copyOf(layers);
    }
    var biome = y.getString("generation.biome");
    c.biome = biome == null ? defaultBiome(c.type, c.terrain) : upgrader.biome(biome, from);
    var block = y.getString("generation.block");
    c.block = block == null ? null : upgrader.cell(new Cell(block, null), from).state();
    c.preset(Objects.requireNonNullElse(parse(Rules.class, y.getString("rules")), Rules.VANILLA));
    // 第二階段建立的世界沒有 settings，照規則預設
    if (y.contains("settings")) {
      var time = y.getString("settings.time", "cycle");
      try {
        c.time = time.equals("cycle") ? null : Math.floorMod(Integer.parseInt(time), 24000);
      } catch (NumberFormatException e) {
        c.time = null;
      }
      c.weather = Objects.requireNonNullElse(parse(Weather.class, y.getString("settings.weather")), Weather.CYCLE);
      c.difficulty = Difficulty.byName(y.getString("settings.difficulty", "server"));
      c.mobs = y.getBoolean("settings.mobs", true);
      c.nature = y.getBoolean("settings.nature", true);
      c.memberMode = mode(y.getString("settings.member-mode"), MEMBER_MODES, GameType.CREATIVE);
      c.visitorMode = mode(y.getString("settings.visitor-mode"), VISITOR_MODES, GameType.SPECTATOR);
      c.visitorInteract = y.getBoolean("settings.visitor-interact", false);
      c.inventory = y.getBoolean("settings.inventory", false);
      c.allowFork = y.getBoolean("settings.allow-fork", true);
      c.acceptSubmissions = y.getBoolean("settings.accept-submissions", true);
      c.respawn = y.getBoolean("settings.respawn", true);
      c.border = Math.max(0, y.getInt("settings.border.size", 0));
      c.borderX = num(y.get("settings.border.x"));
      c.borderZ = num(y.get("settings.border.z"));
    }
    if (y.contains("spawn.x"))
      c.spawn = new Spawn(num(y.get("spawn.x")), num(y.get("spawn.y")), num(y.get("spawn.z")), (float) num(y.get("spawn.yaw")), (float) num(y.get("spawn.pitch")));
    c.open = "public".equals(y.getString("visibility"));
    c.unlisted = y.getBoolean("unlisted", false);
    c.template = y.getBoolean("template", false);
    c.displayName = y.getString("display-name");
    c.description = y.getString("description");
    var icon = y.getString("icon");
    // 物品 id 會隨版本改名（例如 grass 改成 short_grass），照存檔時的版本升級
    c.icon = icon == null ? null : upgrader.itemId(icon, from);
    c.forkId = y.getString("fork.source");
    c.forkCommit = y.getString("fork.commit");
    c.forkTemplate = y.getBoolean("fork.template", false);
    readMembers(y, c);
    return c;
  }

  /** 伺服器世界的成員與可見性（03-worlds §4.2），存在 {@code buildgit/server-world.yml}；沒有時是公開、沒有成員。 */
  static WorldConfig loadServer(Path file) {
    var c = new WorldConfig();
    var y = Yaml.load(file);
    c.open = !"private".equals(y.getString("visibility"));
    readMembers(y, c);
    return c;
  }

  void saveServer(Path file) throws IOException {
    var y = new Yaml();
    y.set("visibility", open ? "public" : "private");
    writeMembers(y);
    Files.createDirectories(file.getParent());
    y.save(file);
  }

  private static void readMembers(Yaml y, WorldConfig c) {
    if (y.get("members") instanceof Map<?, ?> members)
      for (var e : members.entrySet()) {
        try {
          if (!(e.getValue() instanceof Map<?, ?> m)) continue;
          var role = parse(Role.class, String.valueOf(m.get("role")));
          if (role == null || role == Role.VISITOR || role == Role.OWNER) continue;
          c.members.put(UUID.fromString(String.valueOf(e.getKey())), new Member(String.valueOf(m.get("name")), role));
        } catch (IllegalArgumentException ignored) {
          // 格式不對的成員略過
        }
      }
  }

  static final List<GameType> MEMBER_MODES = List.of(GameType.CREATIVE, GameType.SURVIVAL, GameType.ADVENTURE);
  static final List<GameType> VISITOR_MODES = List.of(GameType.SPECTATOR, GameType.ADVENTURE);

  private static GameType mode(String name, List<GameType> allowed, GameType fallback) {
    var m = GameType.byName(name == null ? "" : name, null);
    return allowed.contains(m) ? m : fallback;
  }

  private static double num(Object o) {
    return o instanceof Number n ? n.doubleValue() : 0;
  }

  /** 寫回 {@code repo.yml}（主執行緒；檔案很小）。 */
  void save(Path dir, int dataVersion) throws IOException {
    var y = new Yaml();
    y.set("data-version", dataVersion);
    y.set("generation.type", key(type));
    y.set("generation.terrain", key(terrain));
    y.set("generation.seed", seed);
    if (!layers.isEmpty()) {
      var list = new ArrayList<Map<String, Object>>();
      for (var l : layers) {
        var m = new LinkedHashMap<String, Object>();
        m.put("block", l.block());
        m.put("count", l.count());
        list.add(m);
      }
      y.set("generation.layers", list);
    }
    y.set("generation.biome", biome);
    y.set("generation.block", block);
    y.set("rules", key(rules));
    y.set("settings.time", time == null ? "cycle" : String.valueOf(time));
    y.set("settings.weather", key(weather));
    y.set("settings.difficulty", difficulty == null ? "server" : difficulty.getSerializedName());
    y.set("settings.mobs", mobs);
    y.set("settings.nature", nature);
    y.set("settings.member-mode", memberMode.getName());
    y.set("settings.visitor-mode", visitorMode.getName());
    y.set("settings.visitor-interact", visitorInteract);
    y.set("settings.inventory", inventory);
    y.set("settings.allow-fork", allowFork);
    y.set("settings.accept-submissions", acceptSubmissions);
    y.set("settings.respawn", respawn);
    if (border > 0) {
      y.set("settings.border.size", border);
      y.set("settings.border.x", borderX);
      y.set("settings.border.z", borderZ);
    }
    if (spawn != null) {
      y.set("spawn.x", spawn.x());
      y.set("spawn.y", spawn.y());
      y.set("spawn.z", spawn.z());
      y.set("spawn.yaw", (double) spawn.yaw());
      y.set("spawn.pitch", (double) spawn.pitch());
    }
    y.set("visibility", open ? "public" : "private");
    y.set("unlisted", unlisted ? true : null);
    y.set("template", template ? true : null);
    y.set("display-name", displayName);
    y.set("description", description);
    y.set("icon", icon);
    if (forkId != null) {
      y.set("fork.source", forkId);
      y.set("fork.commit", forkCommit);
      y.set("fork.template", forkTemplate ? true : null);
    }
    writeMembers(y);
    Files.createDirectories(dir);
    y.save(dir.resolve(FILE));
  }

  private void writeMembers(Yaml y) {
    for (var e : members.entrySet()) {
      y.set("members." + e.getKey() + ".name", e.getValue().name());
      y.set("members." + e.getKey() + ".role", key(e.getValue().role()));
    }
  }
}
