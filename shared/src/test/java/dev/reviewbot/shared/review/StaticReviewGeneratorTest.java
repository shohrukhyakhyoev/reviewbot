package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Severity.CRITICAL;
import static dev.reviewbot.shared.review.Severity.NIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.keys.PrKey;

class StaticReviewGeneratorTest {

  private static final Finding CRIT = new Finding("A.java", "npe", CRITICAL, "x is null");
  private static final Finding NITPICK = new Finding("B.java", "name", NIT, "rename y");

  private static final Diff DIFF =
      new Diff(List.of(new DiffFile("A.java", "@@ -1 +1 @@", "modified")));
  private static final ReviewContext CONTEXT =
      new ReviewContext(new PrKey(7, "acme/app", 3), null, "abc123", 1);
  private static final ModelRef MODEL = new ModelRef("test-model");

  @Test
  void returnsTheSameFindingsInOrderOnEveryCall() {
    ReviewGenerator generator = StaticReviewGenerator.returning(CRIT, NITPICK);

    Review first = generator.generate(DIFF, CONTEXT, MODEL);
    Review second = generator.generate(DIFF, CONTEXT, MODEL);

    assertThat(first.findings()).containsExactly(CRIT, NITPICK);
    assertThat(second).isEqualTo(first);
  }

  @Test
  void canReturnNoFindings() {
    ReviewGenerator generator = StaticReviewGenerator.returning();

    assertThat(generator.generate(DIFF, CONTEXT, MODEL).findings()).isEmpty();
  }

  @Test
  void rejectsANullReviewAtConstruction() {
    assertThatThrownBy(() -> new StaticReviewGenerator(null))
        .isInstanceOf(NullPointerException.class);
  }
}
