package dev.twme.buildgit.core.sync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 用戶端這一邊（設計 §6.7）：把伺服器送來的範圍存進資料夾，伺服器要求時把改過的範圍送回。
 * 單人世界讀寫的也是這個資料夾。只處理位元組與檔案，平台負責收發。
 *
 * <p>上傳單人世界（03-worlds §7）：伺服器要求時把存檔的主世界 region 與實體檔打包（不壓縮，region 本來就壓縮過），
 * 回報大小與雜湊，再上傳到伺服器給的預簽網址。打包與上傳在背景進行，回覆交給 {@code later}（可能在其他執行緒呼叫）。
 * 伺服器不能自己拿存檔：要求先交給 {@code ask} 問玩家，玩家同意（{@link #answer}）才打包。
 */
public final class SyncClient {
  /** 取出的範圍：{@code <名稱>.bgpack}；單人世界改過後存成 {@code <名稱>.edited.bgpack}。 */
  public static final String PULLED = ".bgpack", EDITED = ".edited.bgpack";
  private static final long LIMIT = 64L * 1024 * 1024;
  /** 點了「在單人世界打開」後留下的記號，單人世界載入時讀走。 */
  private static final String OPEN = ".open";

  /** 要在單人世界打開的範圍：世界資料夾名稱，以及玩家在伺服器上的位置（打開後傳送到這裡）。 */
  public record Open(String name, String level, double x, double y, double z, float yaw, float pitch) {}

  /** 打包的檔案：主世界的 region 與實體檔（含外部的 .mcc）。 */
  public static final Pattern PACKED = Pattern.compile("(region|entities)/(r\\.-?\\d+\\.-?\\d+\\.mca|c\\.-?\\d+\\.-?\\d+\\.mcc)");
  public static final String IMPORT = "import";

  private final Path folder, saves;
  private final Consumer<Frame> later;
  /** 一次只收一個範圍，開始收新的就丟掉舊的。 */
  private Frame.Assembler receiving;
  /** 打包好、等著上傳的存檔。 */
  private volatile Path packed;
  /** 伺服器要上傳的存檔與大小上限，等玩家回答。 */
  public record ImportRequest(String save, long max) {}

  private final Consumer<ImportRequest> ask;
  private volatile ImportRequest asking;

  /** 不上傳單人世界（測試用）。 */
  public SyncClient(Path folder) {
    this(folder, null, f -> {}, r -> {});
  }

  /**
   * @param saves 單人世界存檔的資料夾（{@code .minecraft/saves}）
   * @param ask 伺服器要上傳存檔時問玩家（收封包的執行緒）
   */
  public SyncClient(Path folder, Path saves, Consumer<Frame> later, Consumer<ImportRequest> ask) {
    this.folder = folder;
    this.saves = saves;
    this.later = later;
    this.ask = ask;
  }

  /** 玩家回答上傳的要求：同意就打包，否則告訴伺服器拒絕了。沒有等著回答的要求時回傳 false。 */
  public boolean answer(boolean yes) {
    var r = asking;
    asking = null;
    if (r == null) return false;
    if (yes) background(() -> later.accept(pack(r.save(), r.max())));
    else later.accept(importError("declined"));
    return true;
  }

  /** 離開伺服器時：沒回答的要求與打包好的存檔作廢，不會上傳到下一台伺服器。 */
  public void reset() {
    asking = null;
    packed = null;
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
        case Frame.DONE -> {
          Files.deleteIfExists(folder.resolve(f.name() + EDITED));
          return List.of();
        }
        case Frame.PUSH -> {
          var file = folder.resolve(f.name() + EDITED);
          if (!Files.exists(file)) return error(f.name(), "missing");
          return Frame.split(Frame.UPLOAD, f.name(), Files.readAllBytes(file), Frame.CLIENT_PART);
        }
        case Frame.IMPORT -> {
          var a = new String(f.data(), StandardCharsets.UTF_8).split("\n");
          if (a.length != 2 || !a[1].matches("\\d{1,15}")) return List.of();
          asking = new ImportRequest(a[0], Long.parseLong(a[1]));
          ask.accept(asking);
          return List.of();
        }
        case Frame.IMPORT_URL -> {
          var url = new String(f.data(), StandardCharsets.UTF_8);
          background(() -> later.accept(upload(url)));
          return List.of();
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

  /** 連上伺服器時送出：改過、還沒送回的範圍名稱。 */
  public Frame edited() {
    var names = new ArrayList<String>();
    try (var files = Files.list(folder)) {
      files.forEach(f -> {
        var n = f.getFileName().toString();
        if (n.endsWith(EDITED)) names.add(n.substring(0, n.length() - EDITED.length()));
      });
    } catch (IOException e) {
      // 還沒有範圍
    }
    Collections.sort(names);
    return new Frame(Frame.EDITED, "list", 0, 0, String.join("\n", names).getBytes(StandardCharsets.UTF_8));
  }

  public static boolean pulled(Path folder, String name) {
    return Files.exists(folder.resolve(name + PULLED));
  }

  public static void writeOpen(Path folder, Open o) throws IOException {
    Files.createDirectories(folder);
    Files.writeString(folder.resolve(OPEN), String.join("\n", o.name(), o.level(), String.valueOf(o.x()), String.valueOf(o.y()),
        String.valueOf(o.z()), String.valueOf(o.yaw()), String.valueOf(o.pitch())));
  }

  /** 讀走記號；沒有或讀不懂時為 null。 */
  public static Open takeOpen(Path folder) {
    var file = folder.resolve(OPEN);
    try {
      if (!Files.exists(file)) return null;
      var l = Files.readAllLines(file);
      Files.delete(file);
      if (l.size() != 7 || !Frame.NAME.matcher(l.get(0)).matches()) return null;
      return new Open(l.get(0), l.get(1), Double.parseDouble(l.get(2)), Double.parseDouble(l.get(3)), Double.parseDouble(l.get(4)),
          Float.parseFloat(l.get(5)), Float.parseFloat(l.get(6)));
    } catch (IOException | NumberFormatException e) {
      return null;
    }
  }

  private static void background(Runnable task) {
    Thread.ofVirtual().name("BuildGit import").start(task);
  }

  /** 把存檔的主世界打包成 {@code import.zip}，回報大小與 SHA-256。 */
  private Frame pack(String save, long max) {
    try {
      if (saves == null || save.isEmpty() || save.contains("/") || save.contains("\\") || save.equals(".") || save.equals(".."))
        return importError("no-save");
      // 26.1 起主世界放在 dimensions/minecraft/overworld，之前在存檔的最上層
      var world = saves.resolve(save).resolve("dimensions/minecraft/overworld");
      if (!Files.isDirectory(world.resolve("region"))) world = saves.resolve(save);
      if (!Files.isDirectory(world.resolve("region"))) return importError("no-save");
      Files.createDirectories(folder);
      var zip = folder.resolve("import.zip");
      var md = MessageDigest.getInstance("SHA-256");
      long total = 0;
      try (var out = new ZipOutputStream(new DigestOutputStream(Files.newOutputStream(zip), md))) {
        out.setLevel(Deflater.NO_COMPRESSION);
        for (var sub : List.of("region", "entities")) {
          var dir = world.resolve(sub);
          if (!Files.isDirectory(dir)) continue;
          List<Path> files;
          try (var s = Files.list(dir)) {
            files = s.sorted().toList();
          }
          for (var file : files) {
            var name = sub + "/" + file.getFileName();
            if (!PACKED.matcher(name).matches() || Files.size(file) == 0) continue;
            if ((total += Files.size(file)) > max) {
              out.close();
              Files.deleteIfExists(zip);
              return importError("too-big");
            }
            out.putNextEntry(new ZipEntry(name));
            Files.copy(file, out);
            out.closeEntry();
          }
        }
      }
      if (total == 0 || Files.size(zip) > max) {
        Files.deleteIfExists(zip);
        return importError(total == 0 ? "no-save" : "too-big");
      }
      packed = zip;
      var text = Files.size(zip) + "\n" + HexFormat.of().formatHex(md.digest());
      return new Frame(Frame.IMPORT_READY, IMPORT, 0, 0, text.getBytes(StandardCharsets.UTF_8));
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      return importError("pack-failed");
    }
  }

  /** 上傳到預簽網址，完成後刪掉打包的檔案。 */
  private Frame upload(String url) {
    var zip = packed;
    if (zip == null || !url.startsWith("http")) return importError("upload-failed");
    try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
      var r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).PUT(HttpRequest.BodyPublishers.ofFile(zip)).build(),
          HttpResponse.BodyHandlers.discarding());
      if (r.statusCode() / 100 != 2) return importError("upload-failed " + r.statusCode());
      return new Frame(Frame.IMPORT_DONE, IMPORT, 0, 0, new byte[0]);
    } catch (IOException | RuntimeException e) {
      return importError("upload-failed");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return importError("upload-failed");
    } finally {
      packed = null;
      try {
        Files.deleteIfExists(zip);
      } catch (IOException e) {
        // 下次打包時覆寫
      }
    }
  }

  private static Frame importError(String code) {
    return new Frame(Frame.IMPORT_ERROR, IMPORT, 0, 0, code.getBytes(StandardCharsets.UTF_8));
  }

  private static List<Frame> error(String name, String code) {
    return List.of(new Frame(Frame.ERROR, name, 0, 0, code.getBytes(StandardCharsets.UTF_8)));
  }
}
