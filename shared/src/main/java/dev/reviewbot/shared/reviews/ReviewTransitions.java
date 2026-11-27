package dev.reviewbot.shared.reviews;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The §5.1 state machine. An illegal move is a bug in the caller, never an outcome. */
public final class ReviewTransitions {

  private static final Set<ReviewStatus> INSERTABLE =
      EnumSet.of(ReviewStatus.PROCESSING, ReviewStatus.FAILED, ReviewStatus.SKIPPED);

  /**
   * SUCCEEDED and FAILED have no key: nothing leaves them (FAILED's replay exit lives in isLegal).
   * SKIPPED's exits deviate from §5.1 on purpose (ADR 0005): → PROCESSING because a skip is not
   * done, so a reopened PR or a newly permitted author must be claimable (§7.1 step 3, P1-1); →
   * FAILED because an unclaimed TERMINAL leaves the row FAILED (§7.2, C-6), matching its DLQ entry.
   */
  private static final Map<ReviewStatus, Set<ReviewStatus>> LEGAL =
      new EnumMap<>(ReviewStatus.class);

  static {
    LEGAL.put(
        ReviewStatus.PROCESSING,
        EnumSet.of(
            ReviewStatus.POSTING,
            ReviewStatus.SUCCEEDED,
            ReviewStatus.FAILED,
            ReviewStatus.AWAITING_RETRY,
            ReviewStatus.SKIPPED));
    LEGAL.put(
        ReviewStatus.AWAITING_RETRY,
        EnumSet.of(ReviewStatus.PROCESSING, ReviewStatus.SKIPPED, ReviewStatus.FAILED));
    LEGAL.put(
        ReviewStatus.POSTING,
        EnumSet.of(ReviewStatus.SUCCEEDED, ReviewStatus.FAILED, ReviewStatus.AWAITING_RETRY));
    LEGAL.put(ReviewStatus.SKIPPED, EnumSet.of(ReviewStatus.PROCESSING, ReviewStatus.FAILED));
  }

  public static boolean isLegal(ReviewStatus from, ReviewStatus to, boolean replay) {
    boolean tableAllows = LEGAL.getOrDefault(from, Set.of()).contains(to);
    boolean dlqReplay = replay && from == ReviewStatus.FAILED && to == ReviewStatus.PROCESSING;
    return tableAllows || dlqReplay;
  }

  private ReviewTransitions() {}

  /** For a write that creates the row (no prior status). */
  public static void requireInsertable(ReviewStatus to) {
    if (!INSERTABLE.contains(to)) {
      throw new IllegalStateException("illegal insert as " + to);
    }
  }

  /** For a write that changes an existing row's status. */
  public static void requireLegal(ReviewStatus from, ReviewStatus to, boolean replay) {
    if (!isLegal(from, to, replay)) {
      throw new IllegalStateException(
          "illegal transition " + from + " -> " + to + (replay ? " (replay)" : ""));
    }
  }
}
