package dev.twme.buildgit.core.model;

import dev.twme.buildgit.core.nbt.Nbt;
import java.io.IOException;
import java.util.TreeMap;

/**
 * 把舊版 Minecraft 寫下的資料升級到伺服器目前的格式（設計 §4.6）。版本庫裡的歷史不改寫，
 * 而是在讀取時升級，所以比較、合併與合成都在目前的格式下進行。核心不認識 Minecraft，
 * 由伺服器層以原版的 DataFixer 實作。
 */
public interface Upgrader {
  /** 不升級（測試與不需要伺服器的工具）：資料版本一律視為 0。 */
  Upgrader NONE = new Upgrader() {
    @Override
    public int current() {
      return 0;
    }

    @Override
    public Cell cell(Cell cell, int from) {
      return cell;
    }

    @Override
    public byte[] entity(byte[] nbt, int from) {
      return nbt;
    }

    @Override
    public Nbt.Compound chunk(Nbt.Compound chunk) {
      return chunk;
    }

    @Override
    public Nbt.Compound entityChunk(Nbt.Compound chunk) {
      return chunk;
    }
  };

  /** 伺服器目前的資料版本。 */
  int current();

  /** {@code from} 版寫下的一格。 */
  Cell cell(Cell cell, int from);

  /** {@code from} 版寫下的實體 NBT（正規化後）。 */
  byte[] entity(byte[] nbt, int from);

  /** region 檔裡的 chunk，依它自己的 {@code DataVersion}；結果的 {@code DataVersion} 是目前版本。 */
  Nbt.Compound chunk(Nbt.Compound chunk);

  /** 實體 region 檔裡的 chunk，同上。 */
  Nbt.Compound entityChunk(Nbt.Compound chunk);

  /**
   * region 檔方塊調色盤的一項（目前版本的格式）→ 方塊狀態字串。預設是 26.2 以前的 {@code {Name, Properties}}；
   * 26.3 起預設狀態只存方塊 ID，由伺服器以原版的方塊狀態格式實作。
   */
  default String paletteState(Object entry) throws IOException {
    if (!(entry instanceof Nbt.Compound p)) throw new IOException("調色盤項目無效");
    var props = new TreeMap<String, String>();
    p.compound("Properties").forEach((k, v) -> props.put(k, String.valueOf(v)));
    return BlockStates.format(p.string("Name"), props);
  }

  /** 方塊狀態字串 → 目前版本格式的調色盤項目（{@link #paletteState} 的反向）。 */
  default Object paletteEntry(String state) {
    var parsed = BlockStates.parse(state);
    var c = new Nbt.Compound().with("Name", parsed.name());
    if (!parsed.properties().isEmpty()) {
      var props = new Nbt.Compound();
      parsed.properties().forEach(props::with);
      c.with("Properties", props);
    }
    return c;
  }
}
