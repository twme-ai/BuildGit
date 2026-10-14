package dev.twme.buildgit.core.overlay;

import dev.twme.buildgit.core.model.EntityState;
import dev.twme.buildgit.core.store.Bin;
import java.io.IOException;
import java.util.*;

/**
 * 一個分片中與起點世界不同的實體：UUID → 內容。{@link Optional#empty()} 是墓碑，表示起點世界的這個實體已被移除。
 */
public record EntityOverlay(SortedMap<UUID, Optional<EntityState>> entities) {
  private static final int VERSION = 1;
  public static final EntityOverlay EMPTY = new EntityOverlay(new TreeMap<>());

  public EntityOverlay {
    entities = Collections.unmodifiableSortedMap(new TreeMap<>(entities));
  }

  public Optional<EntityState> get(UUID uuid) {
    return entities.get(uuid);
  }

  public boolean isEmpty() {
    return entities.isEmpty();
  }

  public byte[] encode() {
    var out = new Bin.Out().u8(VERSION).i32(entities.size());
    entities.forEach(
        (uuid, state) -> {
          out.uuid(uuid);
          if (state.isEmpty()) out.u8(0);
          else out.u8(1).bytes(state.get().nbt());
        });
    return out.toByteArray();
  }

  public static EntityOverlay decode(byte[] data) throws IOException {
    var in = new Bin.In(data);
    if (in.u8() != VERSION) throw new IOException("不支援的實體分片版本");
    int n = in.count();
    var map = new TreeMap<UUID, Optional<EntityState>>();
    for (int i = 0; i < n; i++) {
      var uuid = in.uuid();
      map.put(uuid, switch (in.u8()) {
        case 0 -> Optional.empty();
        case 1 -> Optional.of(new EntityState(uuid, in.bytes()));
        default -> throw new IOException("實體分片標記無效");
      });
    }
    in.end();
    return new EntityOverlay(map);
  }
}
