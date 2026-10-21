package dev.twme.buildgit.core.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 備份的目的地（設計 §2.7）：S3 相容的物件儲存或本機資料夾。鍵用 {@code /} 分隔。 */
public interface BackupStore {
  void put(String key, byte[] data) throws IOException;

  /** @return 版本 id（S3 的 bucket 開了版本控制時）；沒有時為 null */
  String putFile(String key, Path file) throws IOException;

  /** @return 不存在時為 null */
  byte[] get(String key) throws IOException;

  void delete(String key) throws IOException;

  /** 以 {@code prefix} 開頭的所有鍵，依字典順序。 */
  List<String> list(String prefix) throws IOException;

  /** 在目的地裡複製（S3 的伺服器端複製、本機的硬連結），不經過伺服器重傳。 */
  void copy(String from, String to) throws IOException;
}
