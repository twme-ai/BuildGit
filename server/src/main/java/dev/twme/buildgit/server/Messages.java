package dev.twme.buildgit.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;

/**
 * MiniMessage 訊息。內建 {@code lang/zh_tw.yml} 與 {@code lang/en_us.yml}，寫到設定目錄的 {@code lang/} 給伺服器修改；
 * 那裡缺的訊息用內建的，再缺的用英文。其他語言可以在那裡放自己的檔案。
 * 玩家看到的是他遊戲裡設定的語言（有那個語言檔時；同一種語言的其他地區也算，例如 zh_cn 用 zh_tw），
 * 其餘（主控台、沒有的語言、{@code language-per-player: false}）用 {@code language}。
 */
public final class Messages {
  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private static final List<String> BUNDLED = List.of("zh_tw", "en_us");
  /** 語言 → 訊息（已經補上英文）。 */
  private final Map<String, Map<String, String>> languages = new HashMap<>();
  private final String language;
  private final boolean perPlayer;
  /** 最新載入的訊息：{@link Workspace.Refused#getMessage} 用。 */
  private static volatile Messages current;

  public Messages(Path configDir, String language, boolean perPlayer, Logger log) {
    for (var name : BUNDLED) export(configDir, name, log);
    var names = new TreeSet<>(BUNDLED);
    names.add(language);
    try (var s = Files.list(configDir.resolve("lang"))) {
      s.map(f -> f.getFileName().toString()).filter(f -> f.matches("[a-z]{2,3}_[a-z0-9]{2,4}\\.yml")).forEach(f -> names.add(f.substring(0, f.length() - 4)));
    } catch (IOException e) {
      // 沒有 lang/ 時只有內建的
    }
    var english = load(configDir, "en_us", log);
    for (var name : names) {
      var m = new HashMap<>(english);
      if (!name.equals("en_us")) m.putAll(load(configDir, name, log));
      languages.put(name, m);
    }
    if (!BUNDLED.contains(language) && !Files.isRegularFile(configDir.resolve("lang/" + language + ".yml")))
      log.warning("config.yml language: " + language + " has no lang/" + language + ".yml; missing messages are shown in English");
    this.language = language;
    this.perPlayer = perPlayer;
    current = this;
  }

  /** 用 {@code language} 的純文字（主控台記錄、例外訊息）；還沒載入訊息時是 key。 */
  static String plain(String key, Object... placeholders) {
    var m = current;
    return m == null ? key : plain(m.get(key, placeholders));
  }

  static String plain(Component c) {
    var b = new StringBuilder();
    if (c instanceof net.kyori.adventure.text.TextComponent t) b.append(t.content());
    for (var child : c.children()) b.append(plain(child));
    return b.toString();
  }

  /** 給這位玩家看的語言；{@code null}（主控台）時是 {@code language}。 */
  public String language(ServerPlayer p) {
    if (p == null || !perPlayer) return language;
    var code = p.clientInformation().language().toLowerCase(Locale.ROOT);
    if (languages.containsKey(code)) return code;
    var base = code.split("_")[0] + "_";
    if (language.startsWith(base)) return language;
    for (var name : new TreeSet<>(languages.keySet())) if (name.startsWith(base)) return name;
    return language;
  }

  private Map<String, String> of(ServerPlayer p) {
    return languages.getOrDefault(language(p), languages.get("en_us"));
  }

  /**
   * 把內建語言檔寫到 {@code lang/} 給伺服器修改。外掛更新後跟著更新：沒改過的訊息換成新版、新的訊息補上，改過的保留。
   * 上次寫出的內建版本存在 {@code lang/.bundled/}，用來分辨哪些是伺服器改過的。
   */
  private static void export(Path configDir, String name, Logger log) {
    var file = configDir.resolve("lang/" + name + ".yml");
    var base = configDir.resolve("lang/.bundled/" + name + ".yml");
    try (var in = Messages.class.getResourceAsStream("/lang/" + name + ".yml")) {
      if (in == null) return;
      var template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Files.createDirectories(base.getParent());
      if (!Files.exists(file)) {
        Files.writeString(file, template);
      } else if (!Files.exists(base) || !Files.readString(base).equals(template)) {
        var custom = Yaml.read(file);
        var old = Files.exists(base) ? Yaml.load(base) : new Yaml();
        var now = Yaml.load(new ByteArrayInputStream(template.getBytes(StandardCharsets.UTF_8)));
        int[] kept = {0};
        var text = Yaml.fill(template, path -> {
          var v = custom.get(path);
          // 和上次的內建版本相同就是沒改過（沒有上次的版本時，檔案裡的都算改過）
          if (v == null || v.equals(now.get(path)) || Files.exists(base) && v.equals(old.get(path))) return null;
          kept[0]++;
          return v;
        });
        if (!text.equals(Files.readString(file))) {
          Yaml.replace(file, text);
          log.info("lang/" + name + ".yml was updated to the new messages, keeping " + kept[0] + " you changed; the old file is lang/" + name + ".yml.old");
        }
      }
      Files.writeString(base, template);
    } catch (IOException e) {
      log.warning("Cannot update language file " + file + ": " + e.getMessage());
    }
  }

  private static Map<String, String> load(Path configDir, String language, Logger log) {
    var result = new HashMap<String, String>();
    String name = "lang/" + language + ".yml";
    try (var in = Messages.class.getResourceAsStream("/" + name)) {
      if (in != null) result.putAll(Yaml.load(in).strings());
    } catch (IOException | RuntimeException e) {
      log.warning("Cannot read the bundled " + name);
    }
    Path custom = configDir.resolve(name);
    if (Files.isRegularFile(custom)) result.putAll(Yaml.load(custom).strings());
    return result;
  }

  /** 用 {@code language}（主控台、對所有人的訊息）。 */
  public Component get(String key, Object... placeholders) {
    return get((ServerPlayer) null, key, placeholders);
  }

  public Component get(Sender to, String key, Object... placeholders) {
    return get(to == null ? null : to.player(), key, placeholders);
  }

  /**
   * @param placeholders 名稱與值交替；值以純文字插入，不解析標籤。值是 {@link Workspace.Refused} 時照 {@code to} 的語言，
   *     其他例外用它的訊息
   */
  public Component get(ServerPlayer to, String key, Object... placeholders) {
    var resolvers = new TagResolver[placeholders.length / 2];
    for (int i = 0; i + 1 < placeholders.length; i += 2) {
      var name = (String) placeholders[i];
      resolvers[i / 2] = switch (placeholders[i + 1]) {
        case Component c -> Placeholder.component(name, c);
        case Workspace.Refused r -> Placeholder.component(name, get(to, r.key, r.args));
        case Throwable t -> Placeholder.unparsed(name, String.valueOf(t.getMessage()));
        case null, default -> Placeholder.unparsed(name, String.valueOf(placeholders[i + 1]));
      };
    }
    var template = of(to).getOrDefault(key, key);
    return MINI.deserialize(template, resolvers);
  }

  /** 未經 MiniMessage 解析的原文；沒有時是空字串。 */
  public String raw(String key) {
    return raw((ServerPlayer) null, key);
  }

  public String raw(Sender to, String key) {
    return raw(to == null ? null : to.player(), key);
  }

  public String raw(ServerPlayer to, String key) {
    return of(to).getOrDefault(key, "");
  }

  /** 加上前綴。 */
  public Component prefixed(Component body) {
    return prefixed((ServerPlayer) null, body);
  }

  public Component prefixed(Sender to, Component body) {
    return prefixed(to == null ? null : to.player(), body);
  }

  public Component prefixed(ServerPlayer to, Component body) {
    var prefix = of(to).get("prefix");
    return prefix == null ? body : MINI.deserialize(prefix).append(body);
  }

  public void send(Sender to, String key, Object... placeholders) {
    to.send(prefixed(to, get(to, key, placeholders)));
  }

  /** 給某位玩家（{@code null}：主控台）的訊息。 */
  public To to(ServerPlayer p) {
    return new To(this, p);
  }

  public To to(Sender s) {
    return new To(this, s == null ? null : s.player());
  }

  public record To(Messages messages, ServerPlayer player) {
    public Component get(String key, Object... placeholders) {
      return messages.get(player, key, placeholders);
    }

    public String raw(String key) {
      return messages.raw(player, key);
    }

    public Component prefixed(Component body) {
      return messages.prefixed(player, body);
    }

    public void send(Sender to, String key, Object... placeholders) {
      messages.send(to, key, placeholders);
    }
  }
}
