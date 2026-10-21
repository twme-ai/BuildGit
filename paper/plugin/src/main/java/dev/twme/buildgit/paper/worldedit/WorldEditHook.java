package dev.twme.buildgit.paper.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.paper.track.BukkitTracker;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * WorldEdit／FAWE 的記錄掛點（設計 §3.2）。只在伺服器裝了 WorldEdit 或 FAWE 時載入。
 *
 * <ul>
 *   <li>FAWE 佇列模式：在佇列上加 {@link FaweRecorder} 批次處理器，整個 section 一次標記。
 *       CoreProtect 改用 extent，被 FAWE 視為不安全而略過，所以 FAWE 的編輯常常沒被記錄。
 *   <li>FAWE 非佇列模式與原版 WorldEdit：包一層 {@link MarkingExtent}，並把套件名稱加進 FAWE 的
 *       {@code allowed-plugins}，否則 FAWE 會丟掉這層 extent。
 * </ul>
 *
 * <p>作者取自執行編輯的玩家；其他插件以 API 呼叫 WorldEdit 時沒有玩家，改用目前執行緒的原因
 * （{@code Recorder#attribute}）。
 */
public final class WorldEditHook {
  private final BukkitTracker tracker;
  private final Logger log;
  private final boolean fawe;
  /** 這個玩家現在不能改世界（檢視世界、建築世界的訪客）；會提醒玩家。 */
  private final Predicate<UUID> readOnly;

  public WorldEditHook(BukkitTracker tracker, Logger log, Predicate<UUID> readOnly) {
    this.tracker = tracker;
    this.log = log;
    this.readOnly = readOnly;
    this.fawe = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
  }

  public void register() {
    if (fawe) FaweSupport.allowExtent(MarkingExtent.class, log);
    WorldEdit.getInstance().getEventBus().register(this);
    log.info(fawe ? "記錄 FastAsyncWorldEdit 的編輯（批次處理器）" : "記錄 WorldEdit 的編輯");
  }

  public void unregister() {
    WorldEdit.getInstance().getEventBus().unregister(this);
  }

  @Subscribe
  public void onEditSession(EditSessionEvent event) {
    if (event.getStage() != EditSession.Stage.BEFORE_CHANGE || event.getWorld() == null) return;
    var world = Bukkit.getWorld(event.getWorld().getName());
    var dimension = tracker.dimension(world);
    if (dimension == null) return;
    Author author;
    var actor = event.getActor();
    if (actor != null && actor.isPlayer() && readOnly.test(actor.getUniqueId())) {
      // 訪客也能下 WorldEdit 指令（03-worlds §4.1）：整個編輯丟掉
      event.setExtent(new NullExtent());
      return;
    }
    if (actor != null && actor.isPlayer()) author = tracker.author(actor.getUniqueId(), actor.getName());
    else author = tracker.cause();
    if (author == null) return;

    Extent extent = event.getExtent();
    if (fawe && FaweSupport.addProcessor(extent, new FaweRecorder(tracker, world, dimension, author))) return;
    event.setExtent(new MarkingExtent(extent, tracker, dimension, author));
  }

  /** 沿著委派鏈往下找。 */
  static Extent unwrap(Extent extent) {
    return extent instanceof AbstractDelegateExtent d ? d.getExtent() : null;
  }
}
