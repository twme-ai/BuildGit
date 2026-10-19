package dev.twme.buildgit.core.backup;

import static org.junit.jupiter.api.Assertions.*;

import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class S3Test {
  /** AWS 文件的 SigV4 範例（GET Object，帶 Range）。 */
  @Test
  void signatureMatchesAwsExample() {
    var c = new S3.Config("https://s3.amazonaws.com", "us-east-1", "examplebucket", "AKIAIOSFODNN7EXAMPLE",
        "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", false);
    String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    var headers = new TreeMap<String, String>();
    headers.put("host", "examplebucket.s3.amazonaws.com");
    headers.put("range", "bytes=0-9");
    headers.put("x-amz-content-sha256", empty);
    headers.put("x-amz-date", "20130524T000000Z");
    var auth = S3.authorization(c, "GET", "/test.txt", "", headers, empty, "20130524T000000Z");
    assertTrue(auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), auth);
  }
}
