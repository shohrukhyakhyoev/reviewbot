package dev.reviewbot.gateway.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Decides whether a webhook body was signed with our secret (§6.1 step 1, §19.6). */
public final class SignatureVerifier {

  private static final String ALGORITHM = "HmacSHA256";
  private static final String PREFIX = "sha256=";

  private final SecretKeySpec key;

  public SignatureVerifier(String webhookSecret) {
    this.key = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  public boolean verify(byte[] rawBody, String signatureHeader) {
    if (signatureHeader == null) {
      return false;
    }
    String expected = PREFIX + HexFormat.of().formatHex(hmacSha256(rawBody));
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] hmacSha256(byte[] data) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
