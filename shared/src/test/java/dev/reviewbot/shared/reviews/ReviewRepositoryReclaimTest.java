package dev.reviewbot.shared.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** §7.1 step 6 on an existing row: one test per branch of the claim table. */
class ReviewRepositoryReclaimTest extends PostgresTestSupport {

  private static final ReviewKey KEY = new ReviewKey(42L, "octo/repo", 7, "abc123", 1);
  private static final Instant OLD_RECEIVED = Instant.parse("2026-11-20T10:15:30Z");
  private static final Instant NEW_RECEIVED = Instant.parse("2026-11-26T08:00:00Z");
  private static final Instant DEFERRED = Instant.parse("2026-11-20T10:20:00Z");
  private static final Instant POSTED = Instant.parse("2026-11-20T10:30:00Z");

  private final ReviewRepository repository = new ReviewRepository(JDBC);

  @Test
  void awaitingRetryParkedOnAnErrorIsReclaimedAndCountsTheAttempt() {
    seed(ReviewStatus.AWAITING_RETRY, 2, 0, "ANTHROPIC_UNAVAILABLE", null, null);

    ClaimResult result = claim(false);

    assertClaimed(result, 3);
    assertRow(ReviewStatus.PROCESSING, 3, 0);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"RATE_LIMIT", "LOCK_HELD", "PROVIDER_THROTTLE", "BREAKER_PARKED"})
  void awaitingRetryParkedOnADeferOrBreakerIsReclaimedWithoutCounting(String parkedClass) {
    seed(ReviewStatus.AWAITING_RETRY, 2, 3, parkedClass, null, DEFERRED);

    ClaimResult result = claim(false);

    assertClaimed(result, 2);
    assertRow(ReviewStatus.PROCESSING, 2, 3);
    assertThat(repository.find(KEY).orElseThrow().firstDeferredAt()).isEqualTo(DEFERRED);
  }

  @Test
  void staleProcessingIsTakenOverAndCountsTheAttempt() {
    seed(ReviewStatus.PROCESSING, 2, 0, null, null, null);

    ClaimResult result = claim(false);

    assertClaimed(result, 3);
    assertRow(ReviewStatus.PROCESSING, 3, 0);
  }

  /** A poison event that crashes the JVM never reaches a handler; only this backstop stops it. */
  @Test
  void staleProcessingPastTheBudgetIsExhaustedPoison() {
    seed(ReviewStatus.PROCESSING, 5, 0, null, null, null);

    assertThat(claim(false)).isInstanceOf(ClaimResult.ExhaustedPoison.class);
  }

  /** v1 pins the §5.1 side of the conflict: no POSTING → PROCESSING until the v2 fence. */
  @Test
  void aPostingRowCannotBeTakenOverInV1() {
    seed(ReviewStatus.POSTING, 2, 0, null, POSTED, null);

    assertThatThrownBy(() -> claim(false)).isInstanceOf(IllegalStateException.class);
    assertRow(ReviewStatus.POSTING, 2, 0);
  }

  @Test
  void succeededAnswersAlreadySucceededAndLeavesTheRowAlone() {
    seed(ReviewStatus.SUCCEEDED, 1, 0, null, POSTED, null);

    assertThat(claim(false)).isInstanceOf(ClaimResult.AlreadySucceeded.class);
    assertRow(ReviewStatus.SUCCEEDED, 1, 0);
  }

  @Test
  void failedWithoutReplayAnswersAlreadyFailedAndLeavesTheRowAlone() {
    seed(ReviewStatus.FAILED, 5, 0, "EXHAUSTED_ANTHROPIC_UNAVAILABLE", null, null);

    assertThat(claim(false)).isInstanceOf(ClaimResult.AlreadyFailed.class);
    assertRow(ReviewStatus.FAILED, 5, 0);
  }

  @Test
  void failedWithReplayRestartsFromScratch() {
    seed(ReviewStatus.FAILED, 5, 3, "EXHAUSTED_ANTHROPIC_UNAVAILABLE", null, DEFERRED);

    ClaimResult result = claim(true);

    assertClaimed(result, 1);
    assertRow(ReviewStatus.PROCESSING, 1, 0);
    ReviewRow row = repository.find(KEY).orElseThrow();
    assertThat(row.firstReceivedAt()).isEqualTo(NEW_RECEIVED);
    assertThat(row.firstDeferredAt()).isNull();
  }

  @Test
  void skippedIsReclaimedLikeAFreshClaim() {
    seed(ReviewStatus.SKIPPED, 0, 0, "PR_CLOSED", null, null);

    ClaimResult result = claim(false);

    assertClaimed(result, 1);
    assertRow(ReviewStatus.PROCESSING, 1, 0);
    assertThat(repository.find(KEY).orElseThrow().firstReceivedAt()).isEqualTo(NEW_RECEIVED);
  }

  /** §7.1 12b: a prior post may have landed, so every claim branch must report the stamp. */
  @Test
  void aPriorPostAttemptIsReportedOnReclaimAndOnReplay() {
    seed(ReviewStatus.AWAITING_RETRY, 2, 0, "AMBIGUOUS_POST", POSTED, null);
    assertThat(((ClaimResult.Claimed) claim(false)).postAttemptedAt()).isEqualTo(POSTED);

    JDBC.update("UPDATE reviews SET status = 'FAILED'");
    assertThat(((ClaimResult.Claimed) claim(true)).postAttemptedAt()).isEqualTo(POSTED);
  }

  private ClaimResult claim(boolean replay) {
    return repository.claim(new ClaimRequest(KEY, NEW_RECEIVED, replay, false));
  }

  private static void seed(
      ReviewStatus status,
      int errorAttempts,
      int deferAttempts,
      String failureClass,
      Instant postAttemptedAt,
      Instant firstDeferredAt) {
    JDBC.update(
        """
        INSERT INTO reviews (installation_id, repo_full_name, pr_number, head_sha, round, status,
                             error_attempts, defer_attempts, failure_class, post_attempted_at,
                             first_received_at, first_deferred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        KEY.installationId(),
        KEY.repoFullName(),
        KEY.prNumber(),
        KEY.headSha(),
        KEY.round(),
        status.name(),
        errorAttempts,
        deferAttempts,
        failureClass,
        ts(postAttemptedAt),
        ts(OLD_RECEIVED),
        ts(firstDeferredAt));
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static void assertClaimed(ClaimResult result, int errorAttempts) {
    assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
    assertThat(((ClaimResult.Claimed) result).errorAttempts()).isEqualTo(errorAttempts);
  }

  private void assertRow(ReviewStatus status, int errorAttempts, int deferAttempts) {
    ReviewRow row = repository.find(KEY).orElseThrow();
    assertThat(row.status()).isEqualTo(status);
    assertThat(row.errorAttempts()).isEqualTo(errorAttempts);
    assertThat(row.deferAttempts()).isEqualTo(deferAttempts);
  }
}
