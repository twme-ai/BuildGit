package dev.twme.buildgit.api;

import org.bukkit.Bukkit;

/**
 * BuildGit 的公開 API。BuildGit 啟用後以 Bukkit {@code ServicesManager} 提供。
 *
 * <pre>{@code
 * // plugin.yml: softdepend: [BuildGit]
 * BuildGitApi.find().ifPresent(api -> api.recorder().recordBlock(Actor.of(player), block));
 * }</pre>
 */
public interface BuildGitApi {
  /** API 版本；只增加方法時遞增小版本。 */
  String API_VERSION = "1.0";

  Recorder recorder();

  /** @return BuildGit 沒有安裝或尚未啟用時為 empty */
  static java.util.Optional<BuildGitApi> find() {
    var registration = Bukkit.getServicesManager().getRegistration(BuildGitApi.class);
    return java.util.Optional.ofNullable(registration == null ? null : registration.getProvider());
  }

  /** @throws IllegalStateException BuildGit 沒有安裝或尚未啟用 */
  static BuildGitApi get() {
    return find().orElseThrow(() -> new IllegalStateException("BuildGit 未啟用"));
  }
}
