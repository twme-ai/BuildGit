package dev.twme.buildgit.server.nms;

import dev.twme.buildgit.core.model.BlockStates;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.nbt.Normalizer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * 從執行中的世界讀取格子與實體，格式必須與存檔讀取（{@code AnvilSource}）完全相同，兩者才能比較：
 * 方塊狀態屬性依鍵名排序；方塊實體與實體以存檔時相同的方法輸出 NBT，再經過同一個正規化。
 *
 * <p>只能在主執行緒呼叫。
 */
public final class LiveReader {
  private final Map<BlockState, Cell> plain = new IdentityHashMap<>();

  /** 目前伺服器版本的資料版本，新建的存檔資料要標上它。 */
  public static int dataVersion() {
    return net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
  }

  public static void saveNow(ServerLevel level) {
    // flush=true：等 IO 執行緒把資料寫進 region 檔，複製起點世界前必須如此。
    level.save(null, true, false);
  }

  /**
   * chunk 目前內容的快照（主執行緒）；回傳的工作在任何執行緒執行，產生與存檔相同的未壓縮 NBT。
   */
  public static java.util.concurrent.Callable<byte[]> snapshot(ServerLevel level, LevelChunk chunk) {
    var data = net.minecraft.world.level.chunk.storage.SerializableChunkData.copyOf(level, chunk);
    return () -> {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        NbtIo.write(data.write(), out);
      }
      return bytes.toByteArray();
    };
  }

  /** 讀取已載入 chunk 的一個 section。 */
  public Cell[] readSection(ServerLevel level, LevelChunk chunk, int sectionY, BitSet wanted) throws IOException {
    var cells = new Cell[4096];
    int index = level.getSectionIndexFromSectionY(sectionY);
    if (index < 0 || index >= chunk.getSections().length) {
      for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) cells[i] = Cell.AIR;
      return cells;
    }
    var section = chunk.getSections()[index];
    var origin = chunk.getPos();
    var pos = new BlockPos.MutableBlockPos();
    for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
      int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
      var state = section.getBlockState(x, y, z);
      if (state.hasBlockEntity()) {
        pos.set(origin.getMinBlockX() | x, (sectionY << 4) | y, origin.getMinBlockZ() | z);
        var be = chunk.getBlockEntities().get(pos);
        if (be != null) {
          var nbt = Normalizer.blockEntity(convert(be.saveWithFullMetadata(level.registryAccess())));
          cells[i] = new Cell(state(state), Nbt.write(nbt));
          continue;
        }
      }
      cells[i] = plain.computeIfAbsent(state, s -> Cell.of(state(s)));
    }
    return cells;
  }

  /** 已載入 chunk 一個 section 的 64 個生態域格；section 不存在時為 null。 */
  public static String[] readBiomes(ServerLevel level, LevelChunk chunk, int sectionY) {
    int index = level.getSectionIndexFromSectionY(sectionY);
    if (index < 0 || index >= chunk.getSections().length) return null;
    var section = chunk.getSections()[index];
    var result = new String[64];
    for (int i = 0; i < 64; i++) result[i] = section.getNoiseBiome(i & 3, i >> 4, i >> 2 & 3).getRegisteredName();
    return result;
  }

  /** 實體目前的內容，與實體存檔（entities/*.mca）相同的輸出。 */
  public static EntityState readEntity(Entity entity) throws IOException {
    var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
    if (!entity.save(out)) {
      // 乘客不會單獨存檔；改用不含 id 的輸出並補上類型。
      out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
      entity.saveWithoutId(out);
      var tag = out.buildResult();
      tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
      return new EntityState(entity.getUUID(), Nbt.write(Normalizer.entity(convert(tag))));
    }
    return new EntityState(entity.getUUID(), Nbt.write(Normalizer.entity(convert(out.buildResult()))));
  }

  static String state(BlockState state) {
    var props = new TreeMap<String, String>();
    for (Property<?> p : state.getProperties()) props.put(p.getName(), value(state, p));
    return BlockStates.format(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), props);
  }

  private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  private static Nbt.Compound convert(CompoundTag tag) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      NbtIo.write(tag, out);
    }
    return Nbt.read(bytes.toByteArray());
  }
}
