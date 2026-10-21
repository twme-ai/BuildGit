package dev.twme.buildgit.server;

import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.Upgrader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 建築世界自己的設定（03-worlds §3、§4），存在版本庫的 {@code repo.yml}：建立時決定的類型與地形（之後不能改）、規則、
 * 出生點、可見性與成員。用 BuildGit 自己的寫法存（超平坦的層是方塊 id 與層數），連同寫下時的資料版本；讀取時把方塊與
 * 生態域的 id 升級到伺服器目前的版本（03-worlds §6.4）。
 */
public final class WorldConfig {
  public static final String FILE = "repo.yml";

  public enum Type { OVERWORLD, NETHER, END }

  /** {@code SERVER}：第一階段建立的世界，照伺服器主世界的產生器。 */
  public enum Terrain { NORMAL, LARGE_BIOMES, AMPLIFIED, FLAT, VOID, SERVER }

  /** {@code BUILD}：固定正午、晴天、和平、不生成生物、隨機刻 0、火不蔓延、生物不破壞方塊。 */
  public enum Rules { BUILD, VANILLA }

  /** 角色往下包含：擁有者也是管理者與建造者。訪客不記在成員名單裡。 */
  public enum Role { OWNER, MANAGER, BUILDER, VISITOR }

  public record Layer(String block, int count) {}

  /** 產生世界要的全部資訊，交給平台建立世界；{@code layers} 由下往上，只在超平坦與虛空時用。 */
  public record Gen(Type type, Terrain terrain, long seed, List<Layer> layers, String biome, Rules rules) {}

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
  /** 第一次進來的人出現的地方；還沒決定（剛建立、還沒有人進去）時為 null。 */
  public Spawn spawn;
  public boolean open;
  public final Map<UUID, Member> members = new LinkedHashMap<>();

  // ---- 名稱 ----

  static String key(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  static <E extends Enum<E>> E parse(Class<E> type, String s) {
    if (s == null) return null;
    for (var e : type.getEnumConstants()) if (key(e).equals(s.toLowerCase(Locale.ROOT))) return e;
    return null;
  }

  Gen gen() {
    return new Gen(type, terrain, seed, terrain == Terrain.FLAT ? layers : List.of(), biome, rules);
  }

  /** 每種類型的超平坦預設（和原版「經典平坦」相同的想法）。 */
  static List<Layer> defaultLayers(Type type) {
    return switch (type) {
      case OVERWORLD -> List.of(new Layer("minecraft:bedrock", 1), new Layer("minecraft:dirt", 2), new Layer("minecraft:grass_block", 1));
      case NETHER -> List.of(new Layer("minecraft:bedrock", 1), new Layer("minecraft:netherrack", 3));
      case END -> List.of(new Layer("minecraft:end_stone", 3));
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
    c.rules = Objects.requireNonNullElse(parse(Rules.class, y.getString("rules")), Rules.VANILLA);
    if (y.contains("spawn.x"))
      c.spawn = new Spawn(num(y.get("spawn.x")), num(y.get("spawn.y")), num(y.get("spawn.z")), (float) num(y.get("spawn.yaw")), (float) num(y.get("spawn.pitch")));
    c.open = "public".equals(y.getString("visibility"));
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
    return c;
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
    if (spawn != null) {
      y.set("spawn.x", spawn.x());
      y.set("spawn.y", spawn.y());
      y.set("spawn.z", spawn.z());
      y.set("spawn.yaw", (double) spawn.yaw());
      y.set("spawn.pitch", (double) spawn.pitch());
    }
    y.set("visibility", open ? "public" : "private");
    for (var e : members.entrySet()) {
      y.set("members." + e.getKey() + ".name", e.getValue().name());
      y.set("members." + e.getKey() + ".role", key(e.getValue().role()));
    }
    Files.createDirectories(dir);
    y.save(dir.resolve(FILE));
  }
}
