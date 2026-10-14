package dev.twme.buildgit.paper.nms;

import dev.twme.buildgit.core.model.BlockStates;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.nbt.Normalizer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;

/**
 * 從執行中的世界讀取格子與實體，格式必須與存檔讀取（{@code AnvilSource}）完全相同，兩者才能比較：
 * 方塊狀態屬性依鍵名排序；方塊實體與實體以存檔時相同的方法輸出 NBT，再經過同一個正規化。
 *
 * <p>只能在主執行緒呼叫。
 */
public final class LiveReader {
  private final Map<BlockState, Cell> plain = new IdentityHashMap<>();

  /** 世界在磁碟上的目錄（含 {@code region/}、{@code entities/}）。 */
  public static Path directory(World world) {
    return ((CraftWorld) world).getWorldPath();
  }

  public static void saveNow(World world) {
    // flush=true：等 IO 執行緒把資料寫進 region 檔，複製起點世界前必須如此。
    ((CraftWorld) world).getHandle().save(null, true, false);
  }

  /** 讀取已載入 chunk 的一個 section。 */
  public Cell[] readSection(Chunk chunk, int sectionY, BitSet wanted) throws IOException {
    var cells = new Cell[4096];
    var level = ((CraftWorld) chunk.getWorld()).getHandle();
    var levelChunk = (LevelChunk) ((CraftChunk) chunk).getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
    int index = level.getSectionIndexFromSectionY(sectionY);
    if (index < 0 || index >= levelChunk.getSections().length) {
      for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) cells[i] = Cell.AIR;
      return cells;
    }
    var section = levelChunk.getSections()[index];
    var pos = new BlockPos.MutableBlockPos();
    for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
      int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
      var state = section.getBlockState(x, y, z);
      if (state.hasBlockEntity()) {
        pos.set((chunk.getX() << 4) | x, (sectionY << 4) | y, (chunk.getZ() << 4) | z);
        var be = levelChunk.getBlockEntities().get(pos);
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

  /** 實體目前的內容，與實體存檔（entities/*.mca）相同的輸出。 */
  public static EntityState readEntity(org.bukkit.entity.Entity bukkit) throws IOException {
    var entity = ((CraftEntity) bukkit).getHandle();
    var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
    if (!entity.save(out)) {
      // 乘客不會單獨存檔；改用不含 id 的輸出並補上類型。
      out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
      entity.saveWithoutId(out);
      var tag = out.buildResult();
      tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
      return new EntityState(bukkit.getUniqueId(), Nbt.write(Normalizer.entity(convert(tag))));
    }
    return new EntityState(bukkit.getUniqueId(), Nbt.write(Normalizer.entity(convert(out.buildResult()))));
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
