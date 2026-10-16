package dev.twme.buildgit.core.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * 用戶端這一邊（設計 §6.7）：把伺服器送來的範圍存進資料夾，伺服器要求時把改過的範圍送回。
 * 單人世界讀寫的也是這個資料夾。只處理位元組與檔案，平台負責收發。
 */
public final class SyncClient {
  /** 取出的範圍：{@code <名稱>.bgpack}；單人世界改過後存成 {@code <名稱>.edited.bgpack}。 */
  public static final String PULLED = ".bgpack", EDITED = ".edited.bgpack";
  private static final long LIMIT = 64L * 1024 * 1024;

  private final Path folder;
  /** 一次只收一個範圍，開始收新的就丟掉舊的。 */
  private Frame.Assembler receiving;

  public SyncClient(Path folder) {
    this.folder = folder;
  }

  /** @return 要回給伺服器的封包 */
  public List<Frame> receive(Frame f) {
    try {
      switch (f.type()) {
        case Frame.BUNDLE -> {
          if (f.index() == 0) receiving = new Frame.Assembler(f.name(), LIMIT);
          if (receiving == null) return error(f.name(), "save-failed");
          var data = receiving.add(f);
          if (data != null) {
            receiving = null;
            Files.createDirectories(folder);
            var tmp = folder.resolve(f.name() + PULLED + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, folder.resolve(f.name() + PULLED), StandardCopyOption.REPLACE_EXISTING);
            // 新取出的範圍取代之前改到一半的
            Files.deleteIfExists(folder.resolve(f.name() + EDITED));
          }
          return List.of();
        }
        case Frame.PUSH -> {
          var file = folder.resolve(f.name() + EDITED);
          if (!Files.exists(file)) return error(f.name(), "missing");
          return Frame.split(Frame.UPLOAD, f.name(), Files.readAllBytes(file), Frame.CLIENT_PART);
        }
        default -> {
          return List.of();
        }
      }
    } catch (IOException e) {
      receiving = null;
      return error(f.name(), "save-failed");
    }
  }

  private static List<Frame> error(String name, String code) {
    return List.of(new Frame(Frame.ERROR, name, 0, 0, code.getBytes(StandardCharsets.UTF_8)));
  }
}
