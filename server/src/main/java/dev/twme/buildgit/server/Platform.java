package dev.twme.buildgit.server;

import dev.twme.buildgit.core.model.Box;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;

/**
 * 平台（Paper、Fabric）提供給共用邏輯的功能。共用程式碼以原版類別（Mojang 名稱）操作世界，
 * 只有排程、世界的建立與卸載、權限、訊息、畫面等平台各自不同的部分透過這裡。
 */
public interface Platform {
  MinecraftServer server();

  Logger log();

  /** 設定檔與自訂語言檔所在的目錄（Paper：{@code plugins/BuildGit}；Fabric：{@code config/buildgit}）。 */
  Path configDir();

  /** 版本庫目錄（設計 §2.1 的 {@code buildgit/}）。 */
  Path repoDir();

  // ---- 執行緒 ----

  /** 在主執行緒執行；已經在主執行緒時立刻執行。 */
  void sync(Runnable task);

  void async(Runnable task);

  /** {@code ticks} 刻後在主執行緒執行。 */
  void later(long ticks, Runnable task);

  /** {@code ticks} 刻後在背景執行緒執行。 */
  void asyncLater(long ticks, Runnable task);

  /** 目前的遊戲刻（只用來量時間間隔）。 */
  long tick();

  default boolean isMainThread() {
    return server().isSameThread();
  }

  // ---- 世界 ----

  /** 已載入的 chunk，每個是 {@code x << 32 | z & 0xFFFFFFFFL}。 */
  long[] loadedChunks(ServerLevel level);

  /** 合成一個 region 檔。檔案不存在時才會呼叫；在 IO 執行緒呼叫。 */
  interface Synthesizer {
    boolean synthesize(Path target, int regionX, int regionZ) throws IOException;
  }

  /** 已載入的 BuildGit 世界；沒有時為 null。 */
  ServerLevel findLevel(String name);

  /**
   * 開啟 BuildGit 的世界（主執行緒）：不存在時建立，產生器、種子與維度類型照抄 {@code template}。
   * 必須在讀取任何 chunk 之前掛上合成讀取：region 檔第一次開啟時若不存在，先以 {@code blocks}／{@code entities} 合成。
   */
  ServerLevel openLevel(String name, ServerLevel template, Synthesizer blocks, Synthesizer entities) throws IOException;

  /** 卸載 BuildGit 的世界（主執行緒）。裡面不能有玩家。 */
  boolean unloadLevel(ServerLevel level, boolean save);

  /**
   * 存檔這個世界（主執行緒）；回傳的工作在背景執行緒執行，等存檔資料都寫進 region 檔後才返回。
   * 預設在主執行緒就等寫完。
   */
  default Runnable save(ServerLevel level) {
    level.save(null, true, false);
    return () -> {};
  }

  /** 世界在磁碟上的目錄（含 {@code region/}、{@code entities/}）。 */
  Path levelDirectory(ServerLevel level);

  /** BuildGit 世界的資料夾，不論是否載入。 */
  Path levelFolder(String name);

  /** 磁碟上所有 BuildGit 世界資料夾的名稱。 */
  List<String> levelFolders() throws IOException;

  /** 給玩家看的世界名稱。 */
  String levelName(ServerLevel level);

  /** 載入（必要時生成）chunk，在主執行緒完成。 */
  CompletableFuture<LevelChunk> loadChunk(ServerLevel level, int chunkX, int chunkZ);

  /** 這個 chunk 的實體是否已經載入（Paper 與原版的實體都和方塊分開載入）。 */
  boolean entitiesLoaded(ServerLevel level, int chunkX, int chunkZ);

  /** 世界的鄰居更新器欄位（{@code Level.neighborUpdater}，Paper 用反射、Fabric 用 accessor）。 */
  CollectingNeighborUpdater neighborUpdater(ServerLevel level);

  void neighborUpdater(ServerLevel level, CollectingNeighborUpdater updater);

  int maxChainedNeighborUpdates(CollectingNeighborUpdater updater);

  // ---- 玩家 ----

  Sender sender(CommandSourceStack source);

  Sender sender(ServerPlayer player);

  boolean hasPermission(ServerPlayer player, String permission);

  /** 傳送玩家；完成時回傳是否成功。 */
  CompletableFuture<Boolean> teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch);

  /** 以玩家身分執行指令（不含斜線）。 */
  void performCommand(ServerPlayer player, String command);

  // ---- 畫面 ----

  enum BarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }

  /** 顯示或更新玩家的 bossbar；{@code text} 為 null 時隱藏。 */
  void bar(ServerPlayer player, Component text, BarColor color);

  /** 隱藏所有 bossbar（停用時）。 */
  void clearBars();

  /** 箱子選單的排數（每排 9 格）。 */
  int MENU_ROWS = 5;

  /** 箱子選單的一格：物品 id、名稱、說明（null 表示分隔用、不顯示提示框），以及點了以後執行的 {@code /bg} 子指令（null 表示不能點）。 */
  record MenuItem(int slot, String item, Component name, List<Component> lore, String action) {
    /** 只有一行說明。 */
    MenuItem(int slot, String item, Component name, Component lore, String action) {
      this(slot, item, name, List.of(lore), action);
    }
  }

  /** 打開 54 格的箱子選單；點了有動作的格子就關閉並執行 {@code /bg <action>}。 */
  void openMenu(ServerPlayer player, Component title, List<MenuItem> items);

  /** WorldEdit 的選取範圍；沒有安裝或沒有完整選取時為 empty。 */
  Optional<Selection> worldEditSelection(ServerPlayer player);

  record Selection(ServerLevel level, Box box) {}

  // ---- 與單人世界同步（設計 §6.7） ----

  /** 玩家的用戶端裝了 BuildGit，收得到 {@code buildgit:sync}。 */
  boolean canSync(ServerPlayer player);

  /** 送一個 {@code buildgit:sync} 封包給玩家。收到的封包由平台轉給 {@link BuildGit#syncReceived}（主執行緒）。 */
  void sendSync(ServerPlayer player, byte[] data);

  /** 單人世界放範圍檔的資料夾（與用戶端共用）；專用伺服器為 null。 */
  default Path syncFolder() {
    return null;
  }
}
