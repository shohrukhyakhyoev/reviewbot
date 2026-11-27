package dev.reviewbot.shared.reviews;

/** A {@code reviews.status} value (§5.1); the DB stores {@link #name()}. */
public enum ReviewStatus {
  PROCESSING,
  /** v2 only (fenced claim to post); no v1 code path enters it. */
  POSTING,
  AWAITING_RETRY,
  SUCCEEDED,
  FAILED,
  SKIPPED
}
