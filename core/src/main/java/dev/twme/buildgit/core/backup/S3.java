package dev.twme.buildgit.core.backup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * S3 相容物件儲存的最小用戶端（設計 §2.5）：PUT、GET、HEAD、DELETE、ListObjectsV2，以 SigV4 簽名。
 * 用 Java 內建的 HttpClient，不帶 AWS SDK。
 */
public final class S3 {
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

  public void put(String key, byte[] data) throws IOException {
    send("PUT", key, Map.of(), data);
  }

  /** @return 不存在時為 null */
  public byte[] get(String key) throws IOException {
    var r = request("GET", key, Map.of(), null);
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

  public void delete(String key) throws IOException {
    send("DELETE", key, Map.of(), null);
  }

  /** 以 {@code prefix} 開頭的所有鍵，依字典順序。 */
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

  /** 連線失敗、伺服器錯誤（5xx）或要求放慢（429）時，隔一下再試，最多三次。 */
  private HttpResponse<byte[]> request(String method, String key, Map<String, String> query, byte[] body) throws IOException {
    for (int attempt = 1; ; attempt++) {
      try {
        var r = attempt(method, key, query, body);
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
  private HttpResponse<byte[]> attempt(String method, String key, Map<String, String> query, byte[] body) throws IOException {
    var base = URI.create(config.endpoint().replaceAll("/+$", ""));
    String host = (config.pathStyle() ? "" : config.bucket() + ".") + base.getHost()
        + (base.getPort() == -1 || base.getPort() == (base.getScheme().equals("https") ? 443 : 80) ? "" : ":" + base.getPort());
    String path = (config.pathStyle() ? "/" + encode(config.bucket(), false) : "") + "/" + encode(key, true);
    String q = canonicalQuery(query);
    String payload = hex(sha256(body == null ? new byte[0] : body));
    String date = AMZ_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
    var headers = new TreeMap<String, String>();
    headers.put("host", host);
    headers.put("x-amz-content-sha256", payload);
    headers.put("x-amz-date", date);
    var builder = HttpRequest.newBuilder(URI.create(base.getScheme() + "://" + host + path + (q.isEmpty() ? "" : "?" + q)))
        .timeout(Duration.ofMinutes(5))
        .header("x-amz-content-sha256", payload)
        .header("x-amz-date", date)
        .header("Authorization", authorization(config, method, path, q, headers, payload, date))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
    try {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("中斷", e);
    }
  }

  /** SigV4 的 Authorization 標頭。{@code headers} 是要簽的標頭（小寫名稱）。 */
  static String authorization(Config c, String method, String path, String query, SortedMap<String, String> headers, String payload, String date) {
    var canonicalHeaders = new StringBuilder();
    headers.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v.strip()).append('\n'));
    String signed = String.join(";", headers.keySet());
    String request = String.join("\n", method, path, query, canonicalHeaders.toString(), signed, payload);
    String scope = date.substring(0, 8) + "/" + c.region() + "/s3/aws4_request";
    String toSign = String.join("\n", "AWS4-HMAC-SHA256", date, scope, hex(sha256(request.getBytes(StandardCharsets.UTF_8))));
    byte[] key = hmac(("AWS4" + c.secretKey()).getBytes(StandardCharsets.UTF_8), date.substring(0, 8));
    key = hmac(key, c.region());
    key = hmac(key, "s3");
    key = hmac(key, "aws4_request");
    return "AWS4-HMAC-SHA256 Credential=" + c.accessKey() + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + hex(hmac(key, toSign));
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
}
