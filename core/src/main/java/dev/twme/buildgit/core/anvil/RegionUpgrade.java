package dev.twme.buildgit.core.anvil;

import dev.twme.buildgit.core.nbt.Nbt;
import java.io.IOException;
import java.nio.file.*;
import java.util.function.UnaryOperator;

/**
 * 把 region 檔裡較舊版本存的 chunk 升級成目前的格式後寫回（起點世界的升級，設計 §4.5）。
 *
 * <p>先在暫存目錄寫好整個檔案再一次換掉：當機時不是舊檔就是新檔。換掉時還開著舊檔的讀取者繼續讀到舊的內容，
 * 舊內容讀取時照樣會升級，所以兩者等價。
 */
public final class RegionUpgrade {
  /** 1.13 以前存的 chunk（舊的結構資料要由伺服器自己轉）保留原樣，讀取時照舊升級。 */
  public static final int OLDEST = 1493;

  private RegionUpgrade() {}

  /**
   * @param upgrade 把一個 chunk 升級到 {@code current}
   * @param beforeReplace 換掉檔案之前執行（關掉自己開著的寫入）
   * @return 升級的 chunk 數；0 表示檔案沒有動
   */
  public static int rewrite(Path file, int current, UnaryOperator<Nbt.Compound> upgrade, Runnable beforeReplace) throws IOException {
    Path tmpDir = file.resolveSibling(".buildgit-upgrade");
    Files.createDirectories(tmpDir);
    Path tmp = tmpDir.resolve(file.getFileName());
    Files.deleteIfExists(tmp);
    try {
      int upgraded = 0;
      try (var in = new RegionFile(file); var out = new RegionWriter(tmp)) {
        for (int i = 0; i < 1024; i++) {
          var bytes = in.readBytes(i);
          if (bytes == null) continue;
          var chunk = Nbt.read(bytes);
          if (chunk.get("DataVersion") instanceof Integer v && v >= OLDEST && v < current) {
            bytes = Nbt.write(upgrade.apply(chunk));
            upgraded++;
          }
          out.write(i, bytes);
        }
      }
      if (upgraded == 0) return 0;
      beforeReplace.run();
      // 外部 .mcc 檔要比 region 先就位。
      try (var s = Files.list(tmpDir)) {
        for (var f : s.filter(p -> p.getFileName().toString().endsWith(".mcc")).toList())
          Files.move(f, file.resolveSibling(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return upgraded;
    } finally {
      Files.deleteIfExists(tmp);
      try (var s = Files.list(tmpDir)) {
        for (var f : s.toList()) Files.deleteIfExists(f);
      }
      Files.deleteIfExists(tmpDir);
    }
  }
}
