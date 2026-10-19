package dev.twme.buildgit.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * MiniMessage 訊息。內建 {@code lang/zh_tw.yml} 與 {@code lang/en_us.yml}，寫到設定目錄的 {@code lang/} 給伺服器修改；
 * 那裡缺的訊息用內建的。其他語言可以在那裡放自己的檔案。
 */
public final class Messages {
  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private static final List<String> BUNDLED = List.of("zh_tw", "en_us");
  private final Map<String, String> messages = new HashMap<>();

  public Messages(Path configDir, String language, Logger log) {
    for (var name : BUNDLED) export(configDir, name, log);
    load(configDir, "en_us", log);
    if (!language.equals("en_us")) load(configDir, language, log);
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
          log.info("lang/" + name + ".yml 換成了新版本的訊息，保留改過的 " + kept[0] + " 則；原本的檔案留在 lang/" + name + ".yml.old");
        }
      }
      Files.writeString(base, template);
    } catch (IOException e) {
      log.warning("無法更新語言檔 " + file + "：" + e.getMessage());
    }
  }

  private void load(Path configDir, String language, Logger log) {
    String name = "lang/" + language + ".yml";
    try (var in = Messages.class.getResourceAsStream("/" + name)) {
      if (in != null) messages.putAll(Yaml.load(in).strings());
    } catch (IOException | RuntimeException e) {
      log.warning("無法讀取內建語言檔 " + name);
    }
    Path custom = configDir.resolve(name);
    if (Files.isRegularFile(custom)) messages.putAll(Yaml.load(custom).strings());
  }

  /** @param placeholders 名稱與值交替；值以純文字插入，不解析標籤 */
  public Component get(String key, Object... placeholders) {
    var resolvers = new TagResolver[placeholders.length / 2];
    for (int i = 0; i + 1 < placeholders.length; i += 2) {
      var value = placeholders[i + 1];
      resolvers[i / 2] =
          value instanceof Component c
              ? Placeholder.component((String) placeholders[i], c)
              : Placeholder.unparsed((String) placeholders[i], String.valueOf(value));
    }
    var template = messages.getOrDefault(key, key);
    return MINI.deserialize(template, resolvers);
  }

  /** 未經 MiniMessage 解析的原文；沒有時是空字串。 */
  public String raw(String key) {
    return messages.getOrDefault(key, "");
  }

  /** 加上前綴。 */
  public Component prefixed(Component body) {
    var prefix = messages.get("prefix");
    return prefix == null ? body : MINI.deserialize(prefix).append(body);
  }

  public void send(Sender to, String key, Object... placeholders) {
    to.send(prefixed(get(key, placeholders)));
  }
}
