package dev.twme.buildgit.core.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import dev.twme.buildgit.core.nbt.Nbt;

/**
 * 被追蹤實體的內容。
 *
 * @param uuid 實體 UUID
 * @param nbt 正規化後的 NBT（{@link Nbt#write}），含 {@code id} 與 {@code Pos}
 */
public record EntityState(UUID uuid, byte[] nbt) {
  public EntityState {
    Objects.requireNonNull(uuid, "uuid");
    nbt = nbt.clone();
  }

  @Override
  public byte[] nbt() {
    return nbt.clone();
  }

  /** 實體類型 id（{@code minecraft:armor_stand}）；NBT 損壞或沒有 id 時為空字串。 */
  public String type() {
    try {
      return Nbt.read(nbt).string("id");
    } catch (IOException | RuntimeException e) {
      return "";
    }
  }

  /** 實體的位置；NBT 沒有 {@code Pos} 時回傳 null。 */
  public double[] position() {
    try {
      var pos = Nbt.read(nbt).list("Pos").values();
      if (pos.size() != 3) return null;
      return new double[] {(Double) pos.get(0), (Double) pos.get(1), (Double) pos.get(2)};
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ClassCastException e) {
      return null;
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof EntityState e && uuid.equals(e.uuid) && Arrays.equals(nbt, e.nbt);
  }

  @Override
  public int hashCode() {
    return uuid.hashCode() * 31 + Arrays.hashCode(nbt);
  }
}
