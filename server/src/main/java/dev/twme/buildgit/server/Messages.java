package dev.twme.buildgit.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * MiniMessage 訊息。內建 {@code lang/zh_tw.yml} 與 {@code lang/en_us.yml}；
 * 伺服器可在設定目錄的 {@code lang/} 放同名檔案覆寫個別訊息。
 */
public final class Messages {
  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private final Map<String, String> messages = new HashMap<>();

  public Messages(Path configDir, String language, Logger log) {
    load(configDir, "en_us", log);
    if (!language.equals("en_us")) load(configDir, language, log);
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

  /** 加上前綴。 */
  public Component prefixed(Component body) {
    var prefix = messages.get("prefix");
    return prefix == null ? body : MINI.deserialize(prefix).append(body);
  }

  public void send(Sender to, String key, Object... placeholders) {
    to.send(prefixed(get(key, placeholders)));
  }
}
