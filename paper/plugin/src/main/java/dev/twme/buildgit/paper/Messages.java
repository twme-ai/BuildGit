package dev.twme.buildgit.paper;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * MiniMessage 訊息。內建 {@code lang/zh_tw.yml} 與 {@code lang/en_us.yml}；
 * 伺服器可在 {@code plugins/BuildGit/lang/} 放同名檔案覆寫個別訊息。
 */
public final class Messages {
  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private final Map<String, String> messages = new HashMap<>();

  public Messages(Plugin plugin, String language) {
    load(plugin, "en_us");
    if (!language.equals("en_us")) load(plugin, language);
  }

  private void load(Plugin plugin, String language) {
    String name = "lang/" + language + ".yml";
    try (var in = plugin.getResource(name)) {
      if (in != null) put(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    } catch (java.io.IOException e) {
      plugin.getLogger().warning("無法讀取內建語言檔 " + name);
    }
    Path custom = plugin.getDataFolder().toPath().resolve(name);
    if (Files.isRegularFile(custom)) put(YamlConfiguration.loadConfiguration(custom.toFile()));
  }

  private void put(YamlConfiguration yaml) {
    for (var key : yaml.getKeys(true)) if (yaml.isString(key)) messages.put(key, yaml.getString(key));
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

  public void send(CommandSender to, String key, Object... placeholders) {
    var prefix = messages.get("prefix");
    var body = get(key, placeholders);
    to.sendMessage(prefix == null ? body : MINI.deserialize(prefix).append(body));
  }
}
