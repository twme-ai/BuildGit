package dev.twme.buildgit.core.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一格的內容：方塊狀態與正規化後的方塊實體 NBT。合併、差異、作者歸屬的最小單位。
 *
 * @param state 方塊狀態字串，例如 {@code minecraft:oak_stairs[facing=north,half=bottom]}；屬性依鍵名排序
 * @param blockEntity 正規化後的方塊實體 NBT（{@link dev.twme.buildgit.core.nbt.Nbt#write}），沒有則為 null
 */
public record Cell(String state, byte[] blockEntity) {
  public static final Cell AIR = new Cell("minecraft:air", null);

  public Cell {
    Objects.requireNonNull(state, "state");
    if (blockEntity != null) blockEntity = blockEntity.clone();
  }

  public static Cell of(String state) {
    return new Cell(state, null);
  }

  @Override
  public byte[] blockEntity() {
    return blockEntity == null ? null : blockEntity.clone();
  }

  /** 空氣類方塊（含洞穴空氣、虛空空氣）。 */
  public boolean isAir() {
    return state.equals("minecraft:air")
        || state.equals("minecraft:cave_air")
        || state.equals("minecraft:void_air");
  }

  /** 方塊名稱，不含屬性。 */
  public String block() {
    int i = state.indexOf('[');
    return i < 0 ? state : state.substring(0, i);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Cell c && state.equals(c.state) && Arrays.equals(blockEntity, c.blockEntity);
  }

  @Override
  public int hashCode() {
    return state.hashCode() * 31 + Arrays.hashCode(blockEntity);
  }

  @Override
  public String toString() {
    return blockEntity == null ? state : state + "{" + blockEntity.length + "B}";
  }
}
