package dev.twme.buildgit.fabric.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.fabric.mc.worldedit.WorldEditWorlds;
import dev.twme.buildgit.server.track.Tracker;
import java.util.logging.Logger;

/**
 * Fabric 版 WorldEdit 的記錄掛點（設計 §3.2）：和 Paper 一樣在編輯之前包一層 {@link MarkingExtent}。
 * 只在裝了 WorldEdit 時載入。Fabric 沒有 FAWE。
 *
 * <p>作者取自執行編輯的玩家；其他模組以 API 呼叫 WorldEdit 時沒有玩家，改用目前執行緒的原因。
 */
public final class WorldEditHook {
  private final Tracker tracker;
  private final Logger log;

  public WorldEditHook(Tracker tracker, Logger log) {
    this.tracker = tracker;
    this.log = log;
  }

  public void register() {
    WorldEdit.getInstance().getEventBus().register(this);
    log.info("記錄 WorldEdit 的編輯");
  }

  public void unregister() {
    WorldEdit.getInstance().getEventBus().unregister(this);
  }

  @Subscribe
  public void onEditSession(EditSessionEvent event) {
    if (event.getStage() != EditSession.Stage.BEFORE_CHANGE || event.getWorld() == null) return;
    var level = WorldEditWorlds.level(event.getWorld());
    var dimension = level == null ? null : tracker.dimension(level);
    if (dimension == null) return;
    Author author;
    var actor = event.getActor();
    if (actor != null && actor.isPlayer()) author = tracker.author(actor.getUniqueId(), actor.getName());
    else author = tracker.cause();
    if (author == null) return;
    event.setExtent(new MarkingExtent(event.getExtent(), tracker, dimension, author));
  }
}
