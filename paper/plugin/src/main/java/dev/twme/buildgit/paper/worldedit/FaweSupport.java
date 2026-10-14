package dev.twme.buildgit.paper.worldedit;

import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.PassthroughExtent;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.ParallelQueueExtent;
import com.sk89q.worldedit.extent.Extent;
import java.util.ArrayList;
import java.util.Locale;
import java.util.logging.Logger;

/** FAWE 專用的程式碼；只在 FAWE 存在時載入。 */
final class FaweSupport {
  private FaweSupport() {}

  /**
   * 在 BEFORE_CHANGE 階段的 extent 裡找到 FAWE 的佇列並加上處理器。
   *
   * @return false 表示這次編輯不走佇列（直接寫世界），呼叫者改用 extent
   */
  static boolean addProcessor(Extent extent, IBatchProcessor processor) {
    for (int depth = 0; extent != null && depth < 32; depth++) {
      // 平行佇列：處理器加在範本佇列上，每條執行緒的佇列都會複製它。
      if (extent instanceof ParallelQueueExtent parallel) {
        parallel.getExtent().addProcessor(processor);
        return true;
      }
      if (extent instanceof IQueueExtent<?> queue) {
        queue.addProcessor(processor);
        return true;
      }
      if (extent instanceof PassthroughExtent p) extent = p.getExtent();
      else extent = WorldEditHook.unwrap(extent);
    }
    return false;
  }

  /** FAWE 只接受 {@code allowed-plugins} 裡的 extent 類別（以類別名稱子字串比對）。 */
  static void allowExtent(Class<?> type, Logger log) {
    String name = type.getPackageName().toLowerCase(Locale.ROOT);
    var settings = Settings.settings().EXTENT;
    for (String allowed : settings.ALLOWED_PLUGINS) if (name.contains(allowed.toLowerCase(Locale.ROOT))) return;
    try {
      settings.ALLOWED_PLUGINS.add(type.getPackageName());
    } catch (UnsupportedOperationException e) {
      var copy = new ArrayList<>(settings.ALLOWED_PLUGINS);
      copy.add(type.getPackageName());
      settings.ALLOWED_PLUGINS = copy;
    }
    log.info("已將 " + type.getPackageName() + " 加入 FAWE 的 allowed-plugins（只在記憶體中，不改設定檔）");
  }
}
