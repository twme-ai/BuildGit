package dev.twme.buildgit.core.model;

/** 含端點的方塊範圍，例如玩家的選取範圍。 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  public Box {
    if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("範圍的最小值大於最大值");
  }

  public static Box of(BlockPos a, BlockPos b) {
    return new Box(
        Math.min(a.x(), b.x()),
        Math.min(a.y(), b.y()),
        Math.min(a.z(), b.z()),
        Math.max(a.x(), b.x()),
        Math.max(a.y(), b.y()),
        Math.max(a.z(), b.z()));
  }

  public boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }

  public boolean contains(BlockPos p) {
    return contains(p.x(), p.y(), p.z());
  }

  public boolean contains(double x, double y, double z) {
    return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
  }

  public boolean contains(Box o) {
    return o.minX >= minX && o.maxX <= maxX && o.minY >= minY && o.maxY <= maxY && o.minZ >= minZ && o.maxZ <= maxZ;
  }

  public boolean intersects(Box o) {
    return o.minX <= maxX && o.maxX >= minX && o.minY <= maxY && o.maxY >= minY && o.minZ <= maxZ && o.maxZ >= minZ;
  }
}
