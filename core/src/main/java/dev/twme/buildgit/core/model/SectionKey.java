package dev.twme.buildgit.core.model;

/** 某維度裡的一個 16×16×16 section。 */
public record SectionKey(String dimension, int chunkX, int sectionY, int chunkZ)
    implements Comparable<SectionKey> {
  public static SectionKey of(String dimension, BlockPos pos) {
    return new SectionKey(dimension, pos.chunkX(), pos.sectionY(), pos.chunkZ());
  }

  public BlockPos pos(int index) {
    return BlockPos.fromSection(chunkX, sectionY, chunkZ, index);
  }

  public Box bounds() {
    return new Box(chunkX << 4, sectionY << 4, chunkZ << 4, (chunkX << 4) + 15, (sectionY << 4) + 15, (chunkZ << 4) + 15);
  }

  @Override
  public int compareTo(SectionKey o) {
    int c = dimension.compareTo(o.dimension);
    if (c == 0) c = Integer.compare(chunkX, o.chunkX);
    if (c == 0) c = Integer.compare(chunkZ, o.chunkZ);
    if (c == 0) c = Integer.compare(sectionY, o.sectionY);
    return c;
  }
}
