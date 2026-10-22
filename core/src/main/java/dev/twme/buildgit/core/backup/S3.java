package dev.twme.buildgit.core.backup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * S3 相容物件儲存的最小用戶端（設計 §2.5）：PUT、GET、HEAD、DELETE、ListObjectsV2、伺服器端複製與版本，以 SigV4 簽名。
 * 用 Java 內建的 HttpClient，不帶 AWS SDK。
 */
public final class S3 implements BackupStore {
  /**
   * @param endpoint 例如 {@code https://s3.us-east-1.amazonaws.com}、{@code http://127.0.0.1:9000}
   * @param pathStyle bucket 放在路徑（{@code endpoint/bucket/key}）而不是主機名稱；MinIO 等需要
   */
  public record Config(String endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyle) {}

  private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
  private static final Pattern KEY = Pattern.compile("<Key>(.*?)</Key>"), NEXT = Pattern.compile("<NextContinuationToken>(.*?)</NextContinuationToken>");

  private final Config config;
  private static final int RETRIES = 3;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

  public S3(Config config) {
    this.config = config;
  }

  @Override
  public void put(String key, byte[] data) throws IOException {
    send("PUT", key, Map.of(), data);
  }

  /** 從檔案上傳（不整個讀進記憶體）。 */
  @Override
  public String putFile(String key, Path file) throws IOException {
    var r = request("PUT", key, Map.of(), Map.of(), file);
    check(r);
    return r.headers().firstValue("x-amz-version-id").filter(v -> !v.equals("null")).orElse(null);
  }

  /** @return 不存在時為 null */
  @Override
  public byte[] get(String key) throws IOException {
    return getVersion(key, null);
  }

  /**
   * 下載到檔案，超過 {@code max} 位元組時中止（下載不可信任的上傳時用）。
   *
   * @return 不存在時為 false
   */
  public boolean getFile(String key, Path to, long max) throws IOException {
    HttpResponse<java.io.InputStream> r;
    try {
      r = http.send(signed("GET", key, Map.of(), Map.of(), null), HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("中斷", e);
    }
    try (var in = r.body()) {
      if (r.statusCode() == 404) return false;
      if (r.statusCode() / 100 != 2) throw new IOException("S3 回應 " + r.statusCode());
      try (var out = Files.newOutputStream(to)) {
        var buf = new byte[1 << 16];
        long total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
          if ((total += n) > max) throw new IOException("檔案超過上限");
          out.write(buf, 0, n);
        }
      }
    }
    return true;
  }

  /**
   * 預簽的 PUT 網址（SigV4 query 簽名）：拿到的人在 {@code seconds} 秒內可以上傳到 {@code key} 一次，內容長度必須是
   * {@code length}。{@code endpoint} 是拿到網址的人看得到的位址（可以和伺服器自己連的不同），簽名綁著它的主機名稱。
   */
  public String presignPut(String endpoint, String key, long length, int seconds) {
    var base = URI.create(endpoint.replaceAll("/+$", ""));
    String host = host(base), path = path(key);
    String date = AMZ_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
    String scope = date.substring(0, 8) + "/" + config.region() + "/s3/aws4_request";
    var query = new TreeMap<String, String>();
    query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
    query.put("X-Amz-Credential", config.accessKey() + "/" + scope);
    query.put("X-Amz-Date", date);
    query.put("X-Amz-Expires", String.valueOf(seconds));
    query.put("X-Amz-SignedHeaders", "content-length;host");
    String q = canonicalQuery(query);
    String request = String.join("\n", "PUT", path, q, "content-length:" + length + "\nhost:" + host + "\n", "content-length;host", "UNSIGNED-PAYLOAD");
    return base.getScheme() + "://" + host + path + "?" + q + "&X-Amz-Signature=" + signature(config, date, request);
  }

  /** 物件的某個版本；{@code version} 為 null 時是最新的。不存在時為 null。 */
  public byte[] getVersion(String key, String version) throws IOException {
    var r = request("GET", key, version == null ? Map.of() : Map.of("versionId", version), null);
    if (r.statusCode() == 404) return null;
    check(r);
    return r.body();
  }

  public boolean exists(String key) throws IOException {
    var r = request("HEAD", key, Map.of(), null);
    if (r.statusCode() == 404) return false;
    check(r);
    return true;
  }

  @Override
  public void delete(String key) throws IOException {
    send("DELETE", key, Map.of(), null);
  }

  /** 刪掉物件的某個版本（bucket 開了版本控制時，一般的刪除只會加上刪除標記）。 */
  public void deleteVersion(String key, String version) throws IOException {
    send("DELETE", key, Map.of("versionId", version), null);
  }

  @Override
  public void copy(String from, String to) throws IOException {
    var r = request("PUT", to, Map.of(), Map.of("x-amz-copy-source", "/" + encode(config.bucket(), false) + "/" + encode(from, true)), null);
    check(r);
    // 伺服器端複製可能回 200 但內容是錯誤
    if (r.body() != null && new String(r.body(), StandardCharsets.UTF_8).contains("<Error>")) {
      var text = new String(r.body(), StandardCharsets.UTF_8);
      var code = Pattern.compile("<Code>(.*?)</Code>").matcher(text);
      throw new IOException("S3 複製失敗" + (code.find() ? "：" + code.group(1) : ""));
    }
  }

  private Boolean versioning;

  /** bucket 是否開了版本控制（查一次就記住）。 */
  public boolean versioning() throws IOException {
    if (versioning == null) {
      var r = request("GET", "", Map.of("versioning", ""), null);
      check(r);
      versioning = new String(r.body(), StandardCharsets.UTF_8).contains("<Status>Enabled</Status>");
    }
    return versioning;
  }

  private static final Pattern VERSION = Pattern.compile("<(Version|DeleteMarker)>(.*?)</\\1>", Pattern.DOTALL),
      VERSION_ID = Pattern.compile("<VersionId>(.*?)</VersionId>");

  /** 永久刪掉：bucket 開了版本控制時，連同這個鍵的所有版本與刪除標記一起刪；沒開時就是一般的刪除。 */
  public void purge(String key) throws IOException {
    delete(key);
    if (!versioning()) return;
    var r = request("GET", "", Map.of("versions", "", "prefix", key), null);
    check(r);
    var m = VERSION.matcher(new String(r.body(), StandardCharsets.UTF_8));
    while (m.find()) {
      var k = KEY.matcher(m.group(2));
      var v = VERSION_ID.matcher(m.group(2));
      if (k.find() && unescape(k.group(1)).equals(key) && v.find()) deleteVersion(key, unescape(v.group(1)));
    }
  }

  /** 以 {@code prefix} 開頭的所有鍵，依字典順序。 */
  @Override
  public List<String> list(String prefix) throws IOException {
    var keys = new ArrayList<String>();
    String token = null;
    do {
      var query = new TreeMap<String, String>();
      query.put("list-type", "2");
      query.put("prefix", prefix);
      if (token != null) query.put("continuation-token", token);
      var r = request("GET", "", query, null);
      check(r);
      var xml = new String(r.body(), StandardCharsets.UTF_8);
      var m = KEY.matcher(xml);
      while (m.find()) keys.add(unescape(m.group(1)));
      var next = NEXT.matcher(xml);
      token = xml.contains("<IsTruncated>true</IsTruncated>") && next.find() ? unescape(next.group(1)) : null;
    } while (token != null);
    return keys;
  }

  private void send(String method, String key, Map<String, String> query, byte[] body) throws IOException {
    check(request(method, key, query, body));
  }

  private static void check(HttpResponse<byte[]> r) throws IOException {
    if (r.statusCode() / 100 == 2) return;
    var text = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
    var code = Pattern.compile("<Code>(.*?)</Code>").matcher(text);
    throw new IOException("S3 回應 " + r.statusCode() + (code.find() ? " " + code.group(1) : ""));
  }

  private HttpResponse<byte[]> request(String method, String key, Map<String, String> query, byte[] body) throws IOException {
    return request(method, key, query, Map.of(), body);
  }

  /**
   * 連線失敗、伺服器錯誤（5xx）或要求放慢（429）時，隔一下再試，最多三次。
   *
   * @param body {@code byte[]}、檔案（{@link Path}）或 null
   */
  private HttpResponse<byte[]> request(String method, String key, Map<String, String> query, Map<String, String> headers, Object body)
      throws IOException {
    for (int attempt = 1; ; attempt++) {
      try {
        var r = attempt(method, key, query, headers, body);
        if (attempt < RETRIES && (r.statusCode() >= 500 || r.statusCode() == 429)) {
          pause(attempt);
          continue;
        }
        return r;
      } catch (IOException e) {
        if (Thread.currentThread().isInterrupted()) throw e;
        if (attempt >= RETRIES)
          throw new IOException("無法連上 " + config.endpoint() + "：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        pause(attempt);
      }
    }
  }

  private static void pause(int attempt) throws IOException {
    try {
      Thread.sleep(2000L * attempt * attempt); // 2 秒、8 秒
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("中斷", e);
    }
  }

  /** 一次請求；每次重新簽名（簽名帶時間）。 */
  private HttpResponse<byte[]> attempt(String method, String key, Map<String, String> query, Map<String, String> extra, Object body)
      throws IOException {
    try {
      return http.send(signed(method, key, query, extra, body), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("中斷", e);
    }
  }

  private String host(URI base) {
    return (config.pathStyle() ? "" : config.bucket() + ".") + base.getHost()
        + (base.getPort() == -1 || base.getPort() == (base.getScheme().equals("https") ? 443 : 80) ? "" : ":" + base.getPort());
  }

  private String path(String key) {
    return (config.pathStyle() ? "/" + encode(config.bucket(), false) : "") + "/" + encode(key, true);
  }

  private HttpRequest signed(String method, String key, Map<String, String> query, Map<String, String> extra, Object body) throws IOException {
    var base = URI.create(config.endpoint().replaceAll("/+$", ""));
    String host = host(base), path = path(key);
    String q = canonicalQuery(query);
    String payload = body instanceof Path file ? hex(sha256(file)) : hex(sha256(body == null ? new byte[0] : (byte[]) body));
    String date = AMZ_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
    var headers = new TreeMap<String, String>();
    headers.put("host", host);
    headers.put("x-amz-content-sha256", payload);
    headers.put("x-amz-date", date);
    headers.putAll(extra);
    var builder = HttpRequest.newBuilder(URI.create(base.getScheme() + "://" + host + path + (q.isEmpty() ? "" : "?" + q)))
        .timeout(Duration.ofMinutes(5))
        .header("Authorization", authorization(config, method, path, q, headers, payload, date))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : body instanceof Path file ? HttpRequest.BodyPublishers.ofFile(file) : HttpRequest.BodyPublishers.ofByteArray((byte[]) body));
    headers.forEach((k, v) -> {
      if (!k.equals("host")) builder.header(k, v);
    });
    return builder.build();
  }

  /** SigV4 的 Authorization 標頭。{@code headers} 是要簽的標頭（小寫名稱）。 */
  static String authorization(Config c, String method, String path, String query, SortedMap<String, String> headers, String payload, String date) {
    var canonicalHeaders = new StringBuilder();
    headers.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v.strip()).append('\n'));
    String signed = String.join(";", headers.keySet());
    String request = String.join("\n", method, path, query, canonicalHeaders.toString(), signed, payload);
    String scope = date.substring(0, 8) + "/" + c.region() + "/s3/aws4_request";
    return "AWS4-HMAC-SHA256 Credential=" + c.accessKey() + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + signature(c, date, request);
  }

  /** canonical request 的 SigV4 簽名。 */
  private static String signature(Config c, String date, String request) {
    String scope = date.substring(0, 8) + "/" + c.region() + "/s3/aws4_request";
    String toSign = String.join("\n", "AWS4-HMAC-SHA256", date, scope, hex(sha256(request.getBytes(StandardCharsets.UTF_8))));
    byte[] key = hmac(("AWS4" + c.secretKey()).getBytes(StandardCharsets.UTF_8), date.substring(0, 8));
    key = hmac(key, c.region());
    key = hmac(key, "s3");
    key = hmac(key, "aws4_request");
    return hex(hmac(key, toSign));
  }

  private static String canonicalQuery(Map<String, String> query) {
    var parts = new ArrayList<String>();
    new TreeMap<>(query).forEach((k, v) -> parts.add(encode(k, false) + "=" + encode(v, false)));
    return String.join("&", parts);
  }

  /** SigV4 的 URI 編碼：只保留 {@code A-Za-z0-9-._~}（路徑中另外保留 {@code /}）。 */
  static String encode(String s, boolean keepSlash) {
    var out = new StringBuilder();
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~'
          || (keepSlash && c == '/')) out.append(c);
      else out.append('%').append(String.format("%02X", b & 0xFF));
    }
    return out.toString();
  }

  private static String unescape(String s) {
    return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
  }

  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 檔案的 SHA-256，不整個讀進記憶體。 */
  public static byte[] sha256(Path file) throws IOException {
    try (var in = Files.newInputStream(file)) {
      var md = MessageDigest.getInstance("SHA-256");
      var buf = new byte[1 << 16];
      for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
      return md.digest();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hmac(byte[] key, String data) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  @Override
  public String toString() {
    return config.endpoint() + " " + config.bucket();
  }
}
