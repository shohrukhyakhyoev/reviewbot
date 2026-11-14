package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Checks.requireText;

import dev.reviewbot.shared.keys.PrKey;

/**
 * Which PR and commits a review is for. {@code baseSha} is null on a first review (full-PR diff,
 * §7.1 step 8). E5/E9 may add fields; the port signature stays frozen.
 */
public record ReviewContext(PrKey pr, String baseSha, String headSha, int round) {

  public ReviewContext {
    if (pr == null) {
      throw new IllegalArgumentException("pr must not be null");
    }
    if (baseSha != null) {
      requireText(baseSha, "baseSha");
    }
    requireText(headSha, "headSha");
    if (round < 1) {
      throw new IllegalArgumentException("round must be >= 1, got " + round);
    }
  }
}
