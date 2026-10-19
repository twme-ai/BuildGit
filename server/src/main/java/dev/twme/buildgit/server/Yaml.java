package dev.twme.buildgit.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * 小型 YAML 讀寫（SnakeYAML）：以點分隔的路徑存取巢狀的鍵，和 Bukkit 的 {@code YamlConfiguration} 相同的檔案格式。
 * Paper 內建 SnakeYAML；Fabric 版把它打包進 jar。
 */
public final class Yaml {
  private final Map<String, Object> root;

  private Yaml(Map<String, Object> root) {
    this.root = root;
  }

  public Yaml() {
    this(new LinkedHashMap<>());
  }

  private static org.yaml.snakeyaml.Yaml snake() {
    var dump = new DumperOptions();
    dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    dump.setIndent(2);
    var load = new LoaderOptions();
    return new org.yaml.snakeyaml.Yaml(new SafeConstructor(load), new Representer(dump), dump, load);
  }

  @SuppressWarnings("unchecked")
  private static Yaml parse(Reader reader) {
    Object o = snake().load(reader);
    return new Yaml(o instanceof Map<?, ?> m ? (Map<String, Object>) normalize(m) : new LinkedHashMap<>());
  }

  /** 鍵一律轉成字串（YAML 允許數字鍵）。 */
  private static Map<String, Object> normalize(Map<?, ?> map) {
    var result = new LinkedHashMap<String, Object>();
    map.forEach((k, v) -> result.put(String.valueOf(k), v instanceof Map<?, ?> m ? normalize(m) : v));
    return result;
  }

  /** 讀取檔案；不存在或讀不懂時回傳空的設定。 */
  public static Yaml load(Path file) {
    if (!Files.isRegularFile(file)) return new Yaml();
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return parse(reader);
    } catch (IOException | RuntimeException e) {
      return new Yaml();
    }
  }

  /** 讀取檔案；讀不懂時丟出例外。 */
  public static Yaml read(Path file) throws IOException {
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return parse(reader);
    } catch (RuntimeException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  public static Yaml load(InputStream in) {
    return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
  }

  public void save(Path file) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    var tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, snake().dump(root), StandardCharsets.UTF_8);
    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  // ---- 讀 ----

  public Object get(String path) {
    Object node = root;
    for (var part : path.split("\\.")) {
      if (!(node instanceof Map<?, ?> m)) return null;
      node = m.get(part);
    }
    return node;
  }

  public boolean contains(String path) {
    return get(path) != null;
  }

  public String getString(String path, String fallback) {
    var v = get(path);
    return v == null || v instanceof Map || v instanceof List ? fallback : String.valueOf(v);
  }

  public String getString(String path) {
    return getString(path, null);
  }

  public long getLong(String path, long fallback) {
    return get(path) instanceof Number n ? n.longValue() : fallback;
  }

  public int getInt(String path, int fallback) {
    return get(path) instanceof Number n ? n.intValue() : fallback;
  }

  public boolean getBoolean(String path, boolean fallback) {
    return get(path) instanceof Boolean b ? b : fallback;
  }

  public boolean isList(String path) {
    return get(path) instanceof List;
  }

  public List<String> getStringList(String path) {
    if (!(get(path) instanceof List<?> list)) return List.of();
    var result = new ArrayList<String>();
    for (var o : list) if (o != null) result.add(String.valueOf(o));
    return result;
  }

  public List<Integer> getIntegerList(String path) {
    if (!(get(path) instanceof List<?> list)) return List.of();
    var result = new ArrayList<Integer>();
    for (var o : list) if (o instanceof Number n) result.add(n.intValue());
    return result;
  }

  /** 最上層的鍵。 */
  public Set<String> keys() {
    return Collections.unmodifiableSet(root.keySet());
  }

  /** 所有不是區段的值的完整路徑。 */
  public Set<String> paths() {
    var result = new LinkedHashSet<String>();
    paths("", root, result);
    return result;
  }

  private static void paths(String prefix, Map<?, ?> map, Set<String> out) {
    map.forEach((k, v) -> {
      String key = prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k;
      if (v instanceof Map<?, ?> m) paths(key, m, out);
      else out.add(key);
    });
  }

  private static final Pattern KEY = Pattern.compile("( *)([A-Za-z0-9_-]+):(.*)");

  /**
   * 以 {@code template}（含說明的 YAML 文字）為底，把 {@code values} 給的值（以完整路徑查；null 表示用範本的）填進對應的鍵，
   * 保留範本的順序與註解。範本裡區段之外的值可以是單行、清單或多行文字（{@code |-}）。
   */
  public static String fill(String template, Function<String, Object> values) {
    var out = new StringBuilder();
    var keys = new ArrayDeque<Map.Entry<Integer, String>>(); // 縮排與鍵
    var blank = new StringBuilder(); // 還不知道屬於哪裡的空行
    int deeper = -1; // 比這個縮排深的行屬於上一個鍵的值
    boolean drop = false; // 那些行換成了新的值
    for (var line : template.split("\n", -1)) {
      if (line.isBlank()) {
        blank.append(line).append('\n');
        continue;
      }
      int indent = line.length() - line.stripLeading().length();
      if (deeper >= 0 && indent > deeper) {
        if (!drop) out.append(blank).append(line).append('\n');
        blank.setLength(0);
        continue;
      }
      out.append(blank);
      blank.setLength(0);
      deeper = -1;
      var m = KEY.matcher(line);
      if (!m.matches()) {
        out.append(line).append('\n');
        continue;
      }
      while (!keys.isEmpty() && keys.peekLast().getKey() >= indent) keys.removeLast();
      keys.addLast(Map.entry(indent, m.group(2)));
      var value = values.apply(String.join(".", keys.stream().map(Map.Entry::getValue).toList()));
      var rest = m.group(3).strip();
      if (value == null || value instanceof Map) {
        out.append(line).append('\n');
        // 多行文字的內容不是鍵
        if (rest.startsWith("|") || rest.startsWith(">")) {
          deeper = indent;
          drop = false;
        }
        continue;
      }
      var pad = " ".repeat(indent + 2);
      out.append(m.group(1)).append(m.group(2)).append(':');
      if (value instanceof List<?> list) {
        out.append(list.isEmpty() ? " []" : "").append('\n');
        for (var o : list) out.append(pad).append("- ").append(scalar(o)).append('\n');
      } else if (value instanceof String s && s.contains("\n") && !s.startsWith(" ")) {
        var body = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        out.append(s.endsWith("\n") ? " |" : " |-").append('\n');
        for (var l : body.split("\n", -1)) out.append(l.isEmpty() ? "" : pad + l).append('\n');
      } else {
        out.append(' ').append(scalar(value)).append('\n');
      }
      deeper = indent;
      drop = true;
    }
    return out.toString().stripTrailing() + "\n";
  }

  /** 把 {@code file} 換成 {@code text}，原本的檔案留在旁邊的 {@code .old}。 */
  public static void replace(Path file, String text) throws IOException {
    Files.copy(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
    var tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, text, StandardCharsets.UTF_8);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** 一個純量值寫成單行 YAML。 */
  public static String scalar(Object value) {
    var dump = new DumperOptions();
    dump.setWidth(Integer.MAX_VALUE);
    dump.setSplitLines(false);
    return new org.yaml.snakeyaml.Yaml(dump).dump(value).strip();
  }

  /** 所有字串值，鍵為完整的點分隔路徑。 */
  public Map<String, String> strings() {
    var result = new LinkedHashMap<String, String>();
    collect("", root, result);
    return result;
  }

  private static void collect(String prefix, Map<?, ?> map, Map<String, String> out) {
    map.forEach((k, v) -> {
      String key = prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k;
      if (v instanceof Map<?, ?> m) collect(key, m, out);
      else if (v instanceof String s) out.put(key, s);
    });
  }

  // ---- 寫 ----

  /** 設定值；{@code value} 為 null 時移除。 */
  @SuppressWarnings("unchecked")
  public void set(String path, Object value) {
    var parts = path.split("\\.");
    Map<String, Object> node = root;
    for (int i = 0; i < parts.length - 1; i++) {
      var next = node.get(parts[i]);
      if (!(next instanceof Map)) {
        if (value == null) return;
        next = new LinkedHashMap<String, Object>();
        node.put(parts[i], next);
      }
      node = (Map<String, Object>) next;
    }
    if (value == null) node.remove(parts[parts.length - 1]);
    else node.put(parts[parts.length - 1], value);
  }
}
