package dev.twme.buildgit.paper.nms;

import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.EntityState;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.TagValueInput;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.entity.CreatureSpawnEvent;

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

  public static void setCell(World world, int x, int y, int z, Cell cell) throws IOException {
    var level = ((CraftWorld) world).getHandle();
    net.minecraft.world.level.block.state.BlockState state;
    try {
      state = BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), cell.state(), false).blockState();
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
      throw new IOException("無法解析方塊狀態：" + cell.state(), e);
    }
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

  /**
   * 依目前的鄰居重算這一格的形狀（設計 §5.2 形狀修補），例如柵欄、牆、玻璃片的連接。
   * 只接受同一種方塊、只有屬性不同的結果；會變成別種方塊（例如失去支撐而消失）的不改。含流體的格子不動，避免排程流動。
   *
   * @return 是否改變
   */
  public static boolean fixShape(World world, int x, int y, int z) {
    var level = ((CraftWorld) world).getHandle();
    var pos = new BlockPos(x, y, z);
    var state = level.getBlockState(pos);
    if (state.isAir() || !state.getFluidState().isEmpty()) return false;
    var updated = Block.updateFromNeighbourShapes(state, level, pos);
    if (updated == state || !updated.is(state.getBlock())) return false;
    level.setBlock(pos, updated, FLAGS);
    return true;
  }

  public static void removeEntity(World world, UUID uuid) {
    var level = ((CraftWorld) world).getHandle();
    var entity = level.getEntity(uuid);
    if (entity != null) entity.discard();
  }

  /** 以版本中的內容放置實體；同 UUID 的舊實體先移除。 */
  public static void spawnEntity(World world, EntityState state) throws IOException {
    var level = ((CraftWorld) world).getHandle();
    removeEntity(world, state.uuid());
    var tag = read(state.nbt());
    var input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
    var entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
    if (entity == null) throw new IOException("無法建立實體：" + state.uuid());
    level.tryAddFreshEntityWithPassengers(entity, CreatureSpawnEvent.SpawnReason.CUSTOM);
  }

  private static CompoundTag read(byte[] bytes) throws IOException {
    return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
  }
}
