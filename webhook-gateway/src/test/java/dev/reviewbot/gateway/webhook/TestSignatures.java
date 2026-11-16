package dev.reviewbot.gateway.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent signer for tests, so they never depend on the code under test to make inputs. */
final class TestSignatures {

  private TestSignatures() {}

  static String sign(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
