package dev.reviewbot.shared.reviews;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The pre-claim reads: dedup fallback (§7.1 steps 1/4) and rate count (step 2). */
class ReviewRepositoryReadsTest extends PostgresTestSupport {

  private static final long INSTALLATION = 42L;
  private static final ReviewKey KEY = new ReviewKey(INSTALLATION, "octo/repo", 7, "abc123", 1);

  private final ReviewRepository repository = new ReviewRepository(JDBC);

  @Test
  void aSucceededRowIsReviewed() {
    seed(KEY, ReviewStatus.SUCCEEDED);

    assertThat(isReviewed(KEY)).isTrue();
  }

  /** §19 item 14 / P1-1: only SUCCEEDED means done; a skip or failure must never dedup. */
  @ParameterizedTest
  @EnumSource(value = ReviewStatus.class, names = "SUCCEEDED", mode = EnumSource.Mode.EXCLUDE)
  void noOtherStatusCountsAsReviewed(ReviewStatus status) {
    seed(KEY, status);

    assertThat(isReviewed(KEY)).isFalse();
  }

  @Test
  void dedupMatchesOnlyTheSameInstallationRepoPrAndSha() {
    seed(KEY, ReviewStatus.SUCCEEDED);

    assertThat(isReviewed(new ReviewKey(INSTALLATION, "octo/repo", 7, "other-sha", 1))).isFalse();
    assertThat(isReviewed(new ReviewKey(INSTALLATION, "octo/repo", 8, "abc123", 1))).isFalse();
    assertThat(isReviewed(new ReviewKey(INSTALLATION, "octo/other", 7, "abc123", 1))).isFalse();
    assertThat(isReviewed(new ReviewKey(99L, "octo/repo", 7, "abc123", 1))).isFalse();
  }

  @Test
  void anEmptyTableIsNotReviewed() {
    assertThat(isReviewed(KEY)).isFalse();
  }

  @Test
  void rateCountIncludesProcessingAndPostingOnly() {
    seed(sha("p1"), ReviewStatus.PROCESSING);
    seed(sha("p2"), ReviewStatus.PROCESSING);
    seed(sha("post"), ReviewStatus.POSTING);
    seed(sha("parked"), ReviewStatus.AWAITING_RETRY);
    seed(sha("done"), ReviewStatus.SUCCEEDED);
    seed(sha("dead"), ReviewStatus.FAILED);
    seed(sha("skip"), ReviewStatus.SKIPPED);

    assertThat(repository.countInFlight(INSTALLATION)).isEqualTo(3);
  }

  @Test
  void rateCountIgnoresOtherInstallations() {
    seed(new ReviewKey(99L, "octo/repo", 7, "abc123", 1), ReviewStatus.PROCESSING);

    assertThat(repository.countInFlight(INSTALLATION)).isZero();
  }

  private boolean isReviewed(ReviewKey key) {
    return repository.isReviewed(
        key.installationId(), key.repoFullName(), key.prNumber(), key.headSha());
  }

  private static ReviewKey sha(String headSha) {
    return new ReviewKey(INSTALLATION, "octo/repo", 7, headSha, 1);
  }

  private static void seed(ReviewKey key, ReviewStatus status) {
    JDBC.update(
        """
        INSERT INTO reviews (installation_id, repo_full_name, pr_number, head_sha, round, status,
                             first_received_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        key.installationId(),
        key.repoFullName(),
        key.prNumber(),
        key.headSha(),
        key.round(),
        status.name(),
        Timestamp.from(Instant.parse("2026-11-20T10:15:30Z")));
  }
}
