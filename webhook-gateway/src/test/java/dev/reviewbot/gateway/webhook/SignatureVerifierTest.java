package dev.reviewbot.gateway.webhook;

import static dev.reviewbot.gateway.webhook.TestSignatures.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class SignatureVerifierTest {

  // GitHub's published example for X-Hub-Signature-256
  private static final String SECRET = "It's a Secret to Everybody";
  private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
  private static final String GITHUB_SIGNATURE =
      "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

  private final SignatureVerifier verifier = new SignatureVerifier(SECRET);

  @Test
  void githubDocumentedVectorVerifies() {
    assertThat(verifier.verify(BODY, GITHUB_SIGNATURE)).isTrue();
  }

  @Test
  void tamperedBodyIsRejected() {
    byte[] tampered = "Hello, World?".getBytes(StandardCharsets.UTF_8);

    assertThat(verifier.verify(tampered, GITHUB_SIGNATURE)).isFalse();
  }

  @Test
  void signatureMadeWithAnotherSecretIsRejected() {
    String forged = sign("another secret", BODY);

    assertThat(verifier.verify(BODY, forged)).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "sha256=",
        "sha256=abc",
        "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
        "sha1=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
        "sha256=zz7107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
        "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e",
        "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17ab",
      })
  void missingOrMalformedHeaderIsRejectedWithoutThrowing(String header) {
    assertThat(verifier.verify(BODY, header)).isFalse();
  }

  // Surprising input: the signature is over the UTF-8 bytes; the same text in another encoding
  // is different bytes, so it must not verify (proves the HMAC input is the raw bytes).
  @Test
  void multiByteBodyVerifiesOverItsRawBytes() {
    String text = "héllo wörld — 日本語";
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    byte[] latin1 = text.getBytes(StandardCharsets.ISO_8859_1);

    assertThat(verifier.verify(utf8, sign(SECRET, utf8))).isTrue();
    assertThat(verifier.verify(utf8, sign(SECRET, latin1))).isFalse();
  }

  @Test
  void comparesThroughMessageDigestIsEqual() {
    try (MockedStatic<MessageDigest> digest = mockStatic(MessageDigest.class, CALLS_REAL_METHODS)) {
      verifier.verify(BODY, GITHUB_SIGNATURE);

      digest.verify(() -> MessageDigest.isEqual(any(byte[].class), any(byte[].class)));
    }
  }
}
