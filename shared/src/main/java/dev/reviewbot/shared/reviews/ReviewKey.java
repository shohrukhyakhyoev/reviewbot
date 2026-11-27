package dev.reviewbot.shared.reviews;

import java.util.Objects;

/** The {@code reviews} UNIQUE key: one row per reviewed SHA per round (§5.1). */
public record ReviewKey(
    long installationId, String repoFullName, int prNumber, String headSha, int round) {

  public ReviewKey {
    Objects.requireNonNull(repoFullName, "repoFullName");
    Objects.requireNonNull(headSha, "headSha");
  }
}
