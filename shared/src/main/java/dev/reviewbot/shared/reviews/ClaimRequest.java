package dev.reviewbot.shared.reviews;

import java.time.Instant;
import java.util.Objects;

/** Input to the step-6 claim; the flags come from the envelope, the timestamp from its header. */
public record ClaimRequest(ReviewKey key, Instant firstReceivedAt, boolean replay, boolean force) {

  public ClaimRequest {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(firstReceivedAt, "firstReceivedAt");
  }
}
