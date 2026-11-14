package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Severity.MAJOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReviewTest {

  private static final Finding FINDING = new Finding("A.java", "s", MAJOR, "d");

  @Test
  void aReviewWithNoFindingsIsValid() {
    assertThat(new Review(List.of()).findings()).isEmpty();
  }

  @Test
  void copiesTheListSoLaterChangesToTheSourceDoNotLeakIn() {
    List<Finding> source = new ArrayList<>(List.of(FINDING));
    Review review = new Review(source);

    source.clear();

    assertThat(review.findings()).containsExactly(FINDING);
  }

  @Test
  void exposedListCannotBeModified() {
    Review review = new Review(List.of(FINDING));

    assertThatThrownBy(() -> review.findings().add(FINDING))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsANullList() {
    assertThatThrownBy(() -> new Review(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("findings");
  }

  @Test
  void rejectsANullElement() {
    assertThatThrownBy(() -> new Review(Arrays.asList(FINDING, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("findings");
  }
}
