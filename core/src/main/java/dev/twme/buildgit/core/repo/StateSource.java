package dev.twme.buildgit.core.repo;

import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.model.SectionKey;
import java.io.IOException;
import java.util.BitSet;
import java.util.Optional;
import java.util.UUID;

/**
 * 由平台提供的世界內容讀取：工作世界的目前內容，或起點世界的內容。
 * 讀取必須在擁有該 chunk 的執行緒上進行，或讀取已存檔的 region 檔，由平台負責。
 */
public interface StateSource {
  /**
   * @param wanted 要讀的格子索引
   * @return 長度 4096 的陣列，至少填好 {@code wanted} 的格子；不存在的方塊視為空氣
   */
  Cell[] readSection(SectionKey key, BitSet wanted) throws IOException;

  /**
   * @return 長度 64 的生態域 ID（索引見 {@link dev.twme.buildgit.core.overlay.SectionOverlay#biomeIndex}）；
   *     不提供生態域的來源回傳 null，比較時略過這個 section 的生態域
   */
  default String[] readBiomes(SectionKey key) throws IOException {
    return null;
  }

  /** @return 實體目前的內容；不存在時回傳 {@code Optional.empty()} */
  Optional<EntityState> readEntity(String dimension, UUID uuid) throws IOException;
}
