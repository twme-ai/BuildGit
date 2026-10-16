package dev.twme.buildgit.server.nms;

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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

/** 以原版的 DataFixer 把舊版的內容升級到伺服器目前的格式（與 Minecraft 載入舊世界的方式相同）。 */
public final class DataUpgrader implements Upgrader {
  private final int current = LiveReader.dataVersion();
  /** 「版本|方塊狀態」→ 升級後的方塊狀態。 */
  private final Map<String, String> states = new ConcurrentHashMap<>();

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

  private String state(String state, int from) {
    var parsed = BlockStates.parse(state);
    var tag = new CompoundTag();
    tag.putString("Name", parsed.name());
    if (!parsed.properties().isEmpty()) {
      var props = new CompoundTag();
      parsed.properties().forEach(props::putString);
      tag.put("Properties", props);
    }
    var fixed = fix(References.BLOCK_STATE, tag, from);
    var props = new TreeMap<String, String>();
    var p = fixed.getCompoundOrEmpty("Properties");
    for (var key : p.keySet()) props.put(key, p.getStringOr(key, ""));
    return BlockStates.format(fixed.getStringOr("Name", parsed.name()), props);
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
