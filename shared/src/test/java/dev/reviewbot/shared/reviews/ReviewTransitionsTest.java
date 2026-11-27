package dev.reviewbot.shared.reviews;

import static dev.reviewbot.shared.reviews.ReviewStatus.AWAITING_RETRY;
import static dev.reviewbot.shared.reviews.ReviewStatus.FAILED;
import static dev.reviewbot.shared.reviews.ReviewStatus.POSTING;
import static dev.reviewbot.shared.reviews.ReviewStatus.PROCESSING;
import static dev.reviewbot.shared.reviews.ReviewStatus.SKIPPED;
import static dev.reviewbot.shared.reviews.ReviewStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class ReviewTransitionsTest {

  private static final Set<ReviewStatus> SPEC_INSERTABLE = EnumSet.of(PROCESSING, SKIPPED, FAILED);

  /** §5.1 written out independently of the class under test, as (from, to, replay). */
  private static final Set<List<Object>> SPEC_LEGAL =
      Set.of(
          List.of(PROCESSING, POSTING, false),
          List.of(PROCESSING, SUCCEEDED, false),
          List.of(PROCESSING, FAILED, false),
          List.of(PROCESSING, AWAITING_RETRY, false),
          List.of(PROCESSING, SKIPPED, false),
          List.of(AWAITING_RETRY, PROCESSING, false),
          List.of(AWAITING_RETRY, SKIPPED, false),
          List.of(AWAITING_RETRY, FAILED, false),
          List.of(POSTING, SUCCEEDED, false),
          List.of(POSTING, FAILED, false),
          List.of(POSTING, AWAITING_RETRY, false),
          List.of(FAILED, PROCESSING, true),
          // Not in §5.1 (ADR 0005): a reopened PR must be claimable over its SKIPPED(PR_CLOSED) row,
          // and an unclaimed TERMINAL over a SKIPPED row leaves it FAILED (§7.2, C-6).
          List.of(SKIPPED, PROCESSING, false),
          List.of(SKIPPED, FAILED, false));

  static Stream<Arguments> everyMove() {
    List<Arguments> all = new ArrayList<>();
    for (ReviewStatus from : ReviewStatus.values()) {
      for (ReviewStatus to : ReviewStatus.values()) {
        for (boolean replay : new boolean[] {false, true}) {
          all.add(Arguments.of(from, to, replay));
        }
      }
    }
    return all.stream();
  }

  /**
   * replay only unlocks FAILED -> PROCESSING; every other move is equally legal with or without it
   * (a replayed event still walks the normal machine after its claim).
   */
  private static boolean specAllows(ReviewStatus from, ReviewStatus to, boolean replay) {
    return SPEC_LEGAL.contains(List.of(from, to, replay))
        || (replay && SPEC_LEGAL.contains(List.of(from, to, false)));
  }

  @ParameterizedTest(name = "{0} -> {1}, replay={2}")
  @MethodSource("everyMove")
  void everyMoveMatchesTheSpecTable(ReviewStatus from, ReviewStatus to, boolean replay) {
    if (specAllows(from, to, replay)) {
      assertThatCode(() -> ReviewTransitions.requireLegal(from, to, replay))
          .doesNotThrowAnyException();
    } else {
      assertThatThrownBy(() -> ReviewTransitions.requireLegal(from, to, replay))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(from + " -> " + to);
    }
  }

  @ParameterizedTest
  @EnumSource(ReviewStatus.class)
  void onlyProcessingSkippedAndFailedMayBeInserted(ReviewStatus to) {
    if (SPEC_INSERTABLE.contains(to)) {
      assertThatCode(() -> ReviewTransitions.requireInsertable(to)).doesNotThrowAnyException();
    } else {
      assertThatThrownBy(() -> ReviewTransitions.requireInsertable(to))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
