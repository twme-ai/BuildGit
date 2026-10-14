package dev.twme.buildgit.core.model;

/** 方塊座標。 */
public record BlockPos(int x, int y, int z) {
  public int chunkX() {
    return x >> 4;
  }

  public int chunkZ() {
    return z >> 4;
  }

  public int sectionY() {
    return y >> 4;
  }

  /** 在 section 內的索引：y、z、x 各 4 位元，與 Minecraft 的排列相同。 */
  public int sectionIndex() {
    return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
  }

  public static BlockPos fromSection(int chunkX, int sectionY, int chunkZ, int index) {
    return new BlockPos(
        (chunkX << 4) | (index & 15), (sectionY << 4) | (index >> 8), (chunkZ << 4) | ((index >> 4) & 15));
  }

  @Override
  public String toString() {
    return x + " " + y + " " + z;
  }
}
