package dev.twme.buildgit.paper;

import dev.twme.buildgit.server.WorldConfig;
import java.util.List;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * 建築世界的超平坦與虛空（03-worlds §3）：一層層的方塊、單一生態域，沒有地形、洞穴、裝飾、結構與生物。
 * Paper 的 {@code WorldCreator} 只能讓主世界類型用原版的超平坦，地獄、終界類型的超平坦要自己產生。
 */
final class LayerGenerator extends ChunkGenerator {
  private final List<WorldConfig.Layer> layers;
  private final String biome;
  private BlockData[] blocks;

  LayerGenerator(List<WorldConfig.Layer> layers, String biome) {
    this.layers = layers;
    this.biome = biome;
  }

  @Override
  public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
    if (blocks == null) blocks = layers.stream().map(l -> Bukkit.createBlockData(l.block())).toArray(BlockData[]::new);
    int y = info.getMinHeight();
    for (int i = 0; i < blocks.length && y < info.getMaxHeight(); i++) {
      int top = Math.min(info.getMaxHeight(), y + layers.get(i).count());
      data.setRegion(0, y, 0, 16, top, 16, blocks[i]);
      y = top;
    }
  }

  @Override
  public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
    var key = NamespacedKey.fromString(biome);
    var b = key == null ? null : Registry.BIOME.get(key);
    var resolved = b != null ? b : Biome.PLAINS;
    return new BiomeProvider() {
      @Override
      public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return resolved;
      }

      @Override
      public List<Biome> getBiomes(WorldInfo worldInfo) {
        return List.of(resolved);
      }
    };
  }

  @Override
  public boolean shouldGenerateNoise() {
    return false;
  }

  @Override
  public boolean shouldGenerateSurface() {
    return false;
  }

  @Override
  public boolean shouldGenerateCaves() {
    return false;
  }

  @Override
  public boolean shouldGenerateDecorations() {
    return false;
  }

  @Override
  public boolean shouldGenerateMobs() {
    return false;
  }

  @Override
  public boolean shouldGenerateStructures() {
    return false;
  }
}
