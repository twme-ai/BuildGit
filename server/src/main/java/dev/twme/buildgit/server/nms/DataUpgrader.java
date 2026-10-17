package dev.twme.buildgit.server.nms;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import dev.twme.buildgit.core.model.BlockStates;
import dev.twme.buildgit.core.model.Cell;
import dev.twme.buildgit.core.model.Upgrader;
import dev.twme.buildgit.core.nbt.Nbt;
import dev.twme.buildgit.core.nbt.Normalizer;
import java.io.*;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.state.BlockState;

/** 以原版的 DataFixer 把舊版的內容升級到伺服器目前的格式（與 Minecraft 載入舊世界的方式相同）。 */
public final class DataUpgrader implements Upgrader {
  private final int current = LiveReader.dataVersion();
  /** 「版本|方塊狀態」→ 升級後的方塊狀態。 */
  private final Map<String, String> states = new ConcurrentHashMap<>();
  /** region 檔調色盤項目與方塊狀態字串的對照（兩個方向各一份）。 */
  private final Map<Object, String> paletteStates = new ConcurrentHashMap<>();
  private final Map<String, Object> paletteEntries = new ConcurrentHashMap<>();

  @Override
  public int current() {
    return current;
  }

  @Override
  public Cell cell(Cell cell, int from) {
    if (from >= current) return cell;
    var state = states.computeIfAbsent(from + "|" + cell.state(), k -> state(cell.state(), from));
    var be = cell.blockEntity();
    if (be != null) be = Nbt.write(Normalizer.blockEntity(convert(fix(References.BLOCK_ENTITY, read(be), from))));
    return new Cell(state, be);
  }

  @Override
  public String biome(String id, int from) {
    if (from >= current) return id;
    var fixed = DataFixers.getDataFixer().update(References.BIOME, new Dynamic<>(NbtOps.INSTANCE, (Tag) StringTag.valueOf(id)), from, current);
    return fixed.getValue().asString().orElse(id);
  }

  @Override
  public byte[] entity(byte[] nbt, int from) {
    if (from >= current) return nbt;
    return Nbt.write(Normalizer.entity(convert(fix(References.ENTITY, read(nbt), from))));
  }

  @Override
  public Nbt.Compound chunk(Nbt.Compound chunk) {
    return chunk(DataFixTypes.CHUNK, chunk);
  }

  @Override
  public Nbt.Compound entityChunk(Nbt.Compound chunk) {
    return chunk(DataFixTypes.ENTITY_CHUNK, chunk);
  }

  private Nbt.Compound chunk(DataFixTypes type, Nbt.Compound chunk) {
    int from = chunk.get("DataVersion") instanceof Integer v ? v : current;
    if (from >= current) return chunk;
    var fixed = type.updateToCurrentVersion(DataFixers.getDataFixer(), read(Nbt.write(chunk)), from);
    fixed.putInt("DataVersion", current);
    return convert(fixed);
  }

  /** 以原版的方塊狀態格式讀：伺服器是哪個版本，就認得哪個版本的寫法（26.3 起預設狀態只存方塊 ID）。 */
  @Override
  public String paletteState(Object entry) throws IOException {
    var cached = paletteStates.get(entry);
    if (cached != null) return cached;
    var parsed = BlockState.CODEC.parse(NbtOps.INSTANCE, toTag(entry)).result();
    if (parsed.isEmpty()) throw new IOException("調色盤項目無效：" + entry);
    var state = LiveReader.state(parsed.get());
    paletteStates.put(entry instanceof Nbt.Compound c ? Nbt.copy(c) : entry, state);
    return state;
  }

  @Override
  public Object paletteEntry(String state) {
    var entry = paletteEntries.computeIfAbsent(state, s -> {
      try {
        var parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
        return fromTag(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, parsed).getOrThrow());
      } catch (CommandSyntaxException e) {
        return Upgrader.super.paletteEntry(s);
      }
    });
    return entry instanceof Nbt.Compound c ? Nbt.copy(c) : entry;
  }

  /** 單一 NBT 值（不一定是 compound）在兩種表示之間轉換：包進一個 compound 再轉。 */
  private static Tag toTag(Object value) {
    return read(Nbt.write(new Nbt.Compound().with("v", value))).get("v");
  }

  private static Object fromTag(Tag tag) {
    var wrapper = new CompoundTag();
    wrapper.put("v", tag);
    return convert(wrapper).get("v");
  }

  /**
   * 方塊狀態的欄位在這個資料版本（26.3 開發期間，{@code BlockStateFieldNamesFix}）從 {@code Name}／{@code Properties}
   * 改名為 {@code id}／{@code properties}。交給 DataFixer 的輸入要用寫下時那一版的名稱。
   */
  private static final int STATE_FIELDS_RENAMED = 5006;

  private String state(String state, int from) {
    var parsed = BlockStates.parse(state);
    boolean renamed = from >= STATE_FIELDS_RENAMED;
    var tag = new CompoundTag();
    tag.putString(renamed ? "id" : "Name", parsed.name());
    if (!parsed.properties().isEmpty()) {
      var props = new CompoundTag();
      parsed.properties().forEach(props::putString);
      tag.put(renamed ? "properties" : "Properties", props);
    }
    var fixed = fix(References.BLOCK_STATE, tag, from);
    // 升級後是目前版本的寫法，由目前版本的格式讀；方塊已經不存在時保留升級後的名稱與屬性
    var block = BlockState.CODEC.parse(NbtOps.INSTANCE, fixed).result();
    if (block.isPresent()) return LiveReader.state(block.get());
    var props = new TreeMap<String, String>();
    var p = fixed.getCompoundOrEmpty(fixed.contains("properties") ? "properties" : "Properties");
    for (var key : p.keySet()) props.put(key, p.getStringOr(key, ""));
    return BlockStates.format(fixed.getStringOr("id", fixed.getStringOr("Name", parsed.name())), props);
  }

  private CompoundTag fix(DSL.TypeReference type, CompoundTag tag, int from) {
    var result = DataFixers.getDataFixer().update(type, new Dynamic<>(NbtOps.INSTANCE, tag), from, current).getValue();
    return result instanceof CompoundTag c ? c : tag;
  }

  private static CompoundTag read(byte[] bytes) {
    try {
      return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Nbt.Compound convert(CompoundTag tag) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        NbtIo.write(tag, out);
      }
      return Nbt.read(bytes.toByteArray());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
