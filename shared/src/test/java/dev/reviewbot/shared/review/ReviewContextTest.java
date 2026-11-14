package dev.reviewbot.shared.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.shared.keys.PrKey;

class ReviewContextTest {

  private static final PrKey PR = new PrKey(7, "acme/app", 3);

  @Test
  void baseShaMayBeNullForAFirstReview() {
    ReviewContext context = new ReviewContext(PR, null, "head", 1);

    assertThat(context.baseSha()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void baseShaWhenPresentMustNotBeBlank(String base) {
    assertThatThrownBy(() -> new ReviewContext(PR, base, "head", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseSha");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void headShaMustNotBeNullOrBlank(String head) {
    assertThatThrownBy(() -> new ReviewContext(PR, "base", head, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("headSha");
  }

  @Test
  void rejectsANullPr() {
    assertThatThrownBy(() -> new ReviewContext(null, "base", "head", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pr");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void roundStartsAtOne(int round) {
    assertThatThrownBy(() -> new ReviewContext(PR, "base", "head", round))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("round");
  }
}
