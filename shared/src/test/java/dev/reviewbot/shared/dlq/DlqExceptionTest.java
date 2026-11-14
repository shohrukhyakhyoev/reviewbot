package dev.reviewbot.shared.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DlqExceptionTest {

  private static final int CAP = 2048;

  @Test
  void snippetAtTheCapIsKeptWhole() {
    assertThat(snippet("x".repeat(CAP))).isEqualTo("x".repeat(CAP));
  }

  @Test
  void snippetOneOverTheCapIsTruncatedWithTheSuffixInsideTheCap() {
    String capped = snippet("x".repeat(CAP + 1));

    assertThat(capped).hasSize(CAP).endsWith("…[truncated]");
    assertThat(capped).startsWith("x".repeat(CAP - "…[truncated]".length()) + "…");
  }

  @Test
  void aCutThatSplitsASurrogatePairDropsTheHalfPair() {
    int keep = CAP - DlqException.TRUNCATED_SUFFIX.length();
    // The emoji's high surrogate would be the last kept char.
    String input = "x".repeat(keep - 1) + "😀" + "y".repeat(100);

    String capped = snippet(input);

    assertThat(capped).isEqualTo("x".repeat(keep - 1) + DlqException.TRUNCATED_SUFFIX);
  }

  @Test
  void loneSurrogatesAreReplacedSoTheBodyCanBeWritten() {
    DlqException e = new DlqException("T", "bad \uD83D end", 400, "\uDE00 x");

    assertThat(e.message()).isEqualTo("bad � end");
    assertThat(e.bodySnippet()).isEqualTo("� x");
  }

  @Test
  void aValidSurrogatePairIsKept() {
    assertThat(snippet("a😀b")).isEqualTo("a😀b");
  }

  @Test
  void messageIsCappedLikeTheSnippet() {
    DlqException e = new DlqException("T", "m".repeat(5000), 500, null);

    assertThat(e.message()).hasSize(CAP).endsWith("…[truncated]");
  }

  @Test
  void nullAndShortSnippetsPassThrough() {
    assertThat(snippet(null)).isNull();
    assertThat(snippet("short")).isEqualTo("short");
  }

  @Test
  void typeIsRequired() {
    assertThatThrownBy(() -> new DlqException(null, "m", 500, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static String snippet(String raw) {
    return new DlqException("T", "m", 400, raw).bodySnippet();
  }
}
