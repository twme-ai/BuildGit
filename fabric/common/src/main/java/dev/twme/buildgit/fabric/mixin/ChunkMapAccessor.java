package dev.twme.buildgit.fabric.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 已載入的 chunk（init 時優先複製成起點世界）。 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
  @Accessor("visibleChunkMap")
  Long2ObjectLinkedOpenHashMap<ChunkHolder> buildgit$chunks();
}
