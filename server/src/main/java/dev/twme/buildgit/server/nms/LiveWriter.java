package dev.twme.buildgit.server.nms;

import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * 把版本內容寫進執行中的世界（合併結果、合成時沒有起點的 chunk）。
 *
 * <p>不觸發任何連帶效果：不通知鄰居、不更新形狀、不掉落、不執行放置／移除的副作用。
 * 寫入的就是版本裡記錄的最終狀態，連帶變化當初已經各自記成格子。只能在主執行緒呼叫，
 * 並且應包在 {@code Tracker.systemWrite} 裡，避免被當成玩家變更。
 */
public final class LiveWriter {
  private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

  private LiveWriter() {}

  public static void setCell(ServerLevel level, int x, int y, int z, Cell cell) throws IOException {
    setCell(level, x, y, z, cell, new java.util.HashMap<>());
  }

  /** 同上；{@code parsed} 記住解析過的方塊狀態，大量寫入時同一種狀態只解析一次。 */
  public static void setCell(ServerLevel level, int x, int y, int z, Cell cell,
      java.util.Map<String, net.minecraft.world.level.block.state.BlockState> parsed) throws IOException {
    var state = state(level, cell, parsed);
    var pos = new BlockPos(x, y, z);
    level.setBlock(pos, state, FLAGS);
    var bytes = cell.blockEntity();
    if (bytes == null) return;
    var be = level.getBlockEntity(pos);
    if (be == null) return;
    var tag = read(bytes);
    tag.putInt("x", x);
    tag.putInt("y", y);
    tag.putInt("z", z);
    be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
    be.setChanged();
    level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
  }

  /** 格子的方塊狀態；{@code parsed} 同 {@link #setCell}。 */
  public static net.minecraft.world.level.block.state.BlockState state(ServerLevel level, Cell cell,
      java.util.Map<String, net.minecraft.world.level.block.state.BlockState> parsed) throws IOException {
    var state = parsed.get(cell.state());
    if (state == null) {
      try {
        state = BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), cell.state(), false).blockState();
      } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
        throw new IOException("無法解析方塊狀態：" + cell.state(), e);
      }
      parsed.put(cell.state(), state);
    }
    return state;
  }

  /**
   * 從別處送來的一格能不能寫進世界：狀態解析得了、方塊實體 NBT 讀得了；
   * {@code gameMaster} 為 false 時不接受指令方塊、結構方塊等管理員方塊。
   */
  public static boolean allowed(net.minecraft.core.HolderLookup.Provider registries, Cell cell, boolean gameMaster) {
    try {
      var state = BlockStateParser.parseForBlock(registries.lookupOrThrow(Registries.BLOCK), cell.state(), false).blockState();
      var bytes = cell.blockEntity();
      if (bytes != null) dev.twme.buildgit.core.nbt.Nbt.read(bytes); // 有深度與節點數上限
      return gameMaster || !(state.getBlock() instanceof net.minecraft.world.level.block.GameMasterBlock);
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | IOException | RuntimeException e) {
      return false;
    }
  }

  /**
   * 把一個 section 的生態域格設成指定的值（索引見 {@code SectionOverlay.biomeIndex}），沒指定的不變。
   * 不認得的生態域（例如資料包已移除）略過。之後要以 {@link #resendBiomes} 通知玩家。
   *
   * @return 是否有寫入
   */
  public static boolean setBiomes(ServerLevel level, LevelChunk chunk, int sectionY, Map<Integer, String> biomes) {
    int index = level.getSectionIndexFromSectionY(sectionY);
    if (index < 0 || index >= chunk.getSections().length) return false;
    var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
    var holders = new HashMap<String, Holder<Biome>>();
    registry.listElements().forEach(h -> holders.put(h.getRegisteredName(), h));
    // 生態域容器的實際型別是可寫的 PalettedContainer（Axiom 也這樣寫）；各版本的 fillBiomesFromNoise 參數不同。
    @SuppressWarnings("unchecked")
    var container = (PalettedContainer<Holder<Biome>>) chunk.getSections()[index].getBiomes();
    boolean changed = false;
    for (var e : biomes.entrySet()) {
      int i = e.getKey(), x = i & 3, y = i >> 4, z = i >> 2 & 3;
      var holder = holders.get(e.getValue());
      if (holder == null || holder.equals(container.get(x, y, z))) continue;
      container.set(x, y, z, holder);
      changed = true;
    }
    if (changed) chunk.markUnsaved();
    return changed;
  }

  /** 讓看得到這些 chunk 的玩家重新載入生態域。 */
  public static void resendBiomes(ServerLevel level, List<LevelChunk> chunks) {
    if (!chunks.isEmpty()) level.getChunkSource().chunkMap.resendBiomesForChunks(List.copyOf(chunks));
  }

  public static void removeEntity(ServerLevel level, UUID uuid) {
    var entity = level.getEntity(uuid);
    if (entity != null) entity.discard();
  }

  /** 以版本中的內容放置實體；同 UUID 的舊實體先移除。 */
  public static void spawnEntity(ServerLevel level, EntityState state) throws IOException {
    removeEntity(level, state.uuid());
    var tag = read(state.nbt());
    var input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
    var entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
    if (entity == null) throw new IOException("無法建立實體：" + state.uuid());
    level.tryAddFreshEntityWithPassengers(entity);
  }

  private static CompoundTag read(byte[] bytes) throws IOException {
    return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
  }
}
