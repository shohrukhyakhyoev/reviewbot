package dev.reviewbot.shared.reviews;

import java.time.Instant;

/** What the step-6 claim tells the pipeline to do next. */
public sealed interface ClaimResult {

  /** The row is ours; continue at step 7. A non-null {@code postAttemptedAt} triggers §7.1 12b. */
  record Claimed(long rowId, int errorAttempts, Instant postAttemptedAt) implements ClaimResult {}

  /** Dedup raced: repopulate the marker, then SKIP. */
  record AlreadySucceeded() implements ClaimResult {}

  /** A redelivered duplicate of a dead-lettered event: SKIP, its DLQ record already exists. */
  record AlreadyFailed() implements ClaimResult {}

  /** Backstop: {@code error_attempts > 5} at claim → TERMINAL(EXHAUSTED_POISON). */
  record ExhaustedPoison() implements ClaimResult {}

  /** Another claimer inserted the same key between our read and our insert. */
  record RaceLost() implements ClaimResult {}
}
