package dev.twme.buildgit.api;

import java.util.BitSet;
import java.util.Optional;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

/**
 * 把其他插件造成的世界變更記到作者名下，下次存檔時收進版本。
 *
 * <p>BuildGit 只記「哪些位置被誰動過」，存檔時才讀取世界的最終內容。所以：
 * <ul>
 *   <li>可以在寫入之前或之後呼叫，只要在同一次存檔之前即可；</li>
 *   <li>多記沒有關係：內容沒變的格子不會產生變更；</li>
 *   <li>所有方法都是執行緒安全的，可以在非同步執行緒呼叫，而且不會讀取世界。</li>
 * </ul>
 *
 * <p>BuildGit 只追蹤版本控制中的世界；其他世界的呼叫會被忽略（見 {@link #isTracked}）。
 */
public interface Recorder {
  /** 這個世界的變更是否會被記錄。 */
  boolean isTracked(World world);

  /** 記下一格。 */
  void recordBlock(Actor actor, Block block);

  /** 記下一格。 */
  void recordBlock(Actor actor, World world, int x, int y, int z);

  /** 記下一個長方體範圍（含兩端）。 */
  void recordBox(Actor actor, World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

  /**
   * 一次記下一個 16×16×16 section 裡的多格，適合大量寫入。
   *
   * @param cells 索引為 {@code (y & 15) << 8 | (z & 15) << 4 | (x & 15)}
   */
  void recordSection(Actor actor, World world, int chunkX, int sectionY, int chunkZ, BitSet cells);

  /** 記下一個實體（生成、移動、修改或移除前呼叫都可以）。 */
  void recordEntity(Actor actor, Entity entity);

  /**
   * 在目前執行緒上把之後發生的變化歸給 {@code actor}，直到關閉為止：
   * 方塊的連帶更新（形狀、紅石、水流起點）、生成的實體、執行的指令都會記到他名下。
   *
   * <pre>{@code
   * try (var scope = recorder.attribute(Actor.of(player))) {
   *   block.setType(Material.STONE); // 連同鄰居的形狀變化一起記錄
   * }
   * }</pre>
   *
   * <p>在這段期間建立的 WorldEdit／FAWE {@code EditSession} 也會整個記到他名下，即使之後在別的執行緒套用。
   *
   * <p>只能在主執行緒（或擁有該區域的執行緒）上使用，而且必須在同一個執行緒關閉。可以巢狀使用。
   *
   * <p>注意：在指令執行期間以 {@code Bukkit.dispatchCommand} 派送的原版指令，會排到外層指令結束後才執行，
   * 屆時這個範圍已經關閉。這種情況請改在排程工作（下一刻）中派送。
   */
  Scope attribute(Actor actor);

  /** 尚未存檔的標記作者；這一格自上次存檔後沒被動過時為 empty。 */
  Optional<Actor> pendingAuthor(Block block);

  /** {@link #attribute} 的範圍。 */
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
