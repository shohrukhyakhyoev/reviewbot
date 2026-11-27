package dev.reviewbot.shared.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.failure.FailureClass;

/** The §7.2 handler writes and the step-12a/13 writes. */
class ReviewRepositoryWritesTest extends PostgresTestSupport {

  private static final ReviewKey KEY = new ReviewKey(42L, "octo/repo", 7, "abc123", 1);
  private static final Instant RECEIVED = Instant.parse("2026-11-20T10:15:30Z");

  private final ReviewRepository repository = new ReviewRepository(JDBC);

  @Test
  void parkingOnAnErrorRecordsTheClassAndChangesNoCounter() {
    long id = seed(ReviewStatus.PROCESSING, 2, 1);

    repository.parkForRetry(id, FailureClass.ANTHROPIC_UNAVAILABLE);

    assertRow(ReviewStatus.AWAITING_RETRY, 2, 1, "ANTHROPIC_UNAVAILABLE");
  }

  @Test
  void parkingOnADeferAddsOneDeferAttempt() {
    long id = seed(ReviewStatus.PROCESSING, 2, 1);

    repository.parkForRetry(id, FailureClass.RATE_LIMIT);

    assertRow(ReviewStatus.AWAITING_RETRY, 2, 2, "RATE_LIMIT");
  }

  @Test
  void parkingOnAnOpenBreakerChangesNoCounter() {
    long id = seed(ReviewStatus.PROCESSING, 2, 1);

    repository.parkForRetry(id, FailureClass.BREAKER_PARKED);

    assertRow(ReviewStatus.AWAITING_RETRY, 2, 1, "BREAKER_PARKED");
  }

  @Test
  void parkingWithAClassThatDoesNotParkIsRejected() {
    long id = seed(ReviewStatus.PROCESSING, 1, 0);

    assertThatThrownBy(() -> repository.parkForRetry(id, FailureClass.LLM_REQUEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertRow(ReviewStatus.PROCESSING, 1, 0, null);
  }

  /** §7.2 "row (if claimed)": an already-parked row is not ours, so parking it again is a bug. */
  @Test
  void parkingARowThatIsNotClaimedThrows() {
    long id = seed(ReviewStatus.AWAITING_RETRY, 1, 0);

    assertThatThrownBy(() -> repository.parkForRetry(id, FailureClass.RATE_LIMIT))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theDeferClockStartsOnceAndNeverMoves() {
    long id = seed(ReviewStatus.PROCESSING, 1, 0);
    Instant before = Instant.now().minusSeconds(5);

    Instant first = repository.startDeferClock(id);
    Instant second = repository.startDeferClock(id);

    assertThat(first).isAfter(before).isBefore(Instant.now().plusSeconds(5));
    assertThat(second).isEqualTo(first);
  }

  @Test
  void failingAClaimedRowRecordsTheTerminalClass() {
    long id = seed(ReviewStatus.PROCESSING, 3, 0);

    repository.failClaimed(id, FailureClass.DIFF_TOO_LARGE);

    assertRow(ReviewStatus.FAILED, 3, 0, "DIFF_TOO_LARGE");
  }

  /** SKIPPED → FAILED is legal for the unclaimed path (C-6); the claimed path must still refuse it. */
  @Test
  void failingARowThatIsNotClaimedThrows() {
    long id = seed(ReviewStatus.SKIPPED, 0, 0);

    assertThatThrownBy(() -> repository.failClaimed(id, FailureClass.DIFF_TOO_LARGE))
        .isInstanceOf(IllegalStateException.class);
    assertRow(ReviewStatus.SKIPPED, 0, 0, null);
  }

  @Test
  void failingWithANonTerminalClassIsRejected() {
    long id = seed(ReviewStatus.PROCESSING, 1, 0);

    assertThatThrownBy(() -> repository.failClaimed(id, FailureClass.RATE_LIMIT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** C-6: a terminal before any claim still leaves a FAILED row. */
  @Test
  void anUnclaimedFailureWithNoRowInsertsFailed() {
    repository.failUnclaimed(KEY, RECEIVED, FailureClass.MISSING_OR_INVALID_API_KEY);

    assertRow(ReviewStatus.FAILED, 0, 0, "MISSING_OR_INVALID_API_KEY");
    assertThat(repository.find(KEY).orElseThrow().firstReceivedAt()).isEqualTo(RECEIVED);
  }

  @Test
  void anUnclaimedFailureOverAParkedRowFailsIt() {
    seed(ReviewStatus.AWAITING_RETRY, 2, 0);

    repository.failUnclaimed(KEY, RECEIVED, FailureClass.MISSING_OR_INVALID_API_KEY);

    assertRow(ReviewStatus.FAILED, 2, 0, "MISSING_OR_INVALID_API_KEY");
  }

  @Test
  void aSkipWithNoRowInsertsSkippedWithItsReason() {
    repository.skip(KEY, RECEIVED, FailureClass.PR_CLOSED);

    assertRow(ReviewStatus.SKIPPED, 0, 0, "PR_CLOSED");
    assertThat(repository.isReviewed(42L, "octo/repo", 7, "abc123")).isFalse();
  }

  @Test
  void aSkipOverAParkedRowSkipsIt() {
    seed(ReviewStatus.AWAITING_RETRY, 2, 0);

    repository.skip(KEY, RECEIVED, FailureClass.AUTHOR_NOT_PERMITTED);

    assertRow(ReviewStatus.SKIPPED, 2, 0, "AUTHOR_NOT_PERMITTED");
  }

  /** Kafka redelivers after a crash before commit: the same skip must not throw. */
  @Test
  void aRedeliveredSkipLeavesTheSkippedRowAlone() {
    seedWithReason(ReviewStatus.SKIPPED, "PR_CLOSED");

    repository.skip(KEY, RECEIVED, FailureClass.PR_CLOSED);

    assertRow(ReviewStatus.SKIPPED, 0, 0, "PR_CLOSED");
  }

  @Test
  void aNewSkipOverASkippedRowRecordsTheLatestReason() {
    seedWithReason(ReviewStatus.SKIPPED, "PR_CLOSED");

    repository.skip(KEY, RECEIVED, FailureClass.AUTHOR_NOT_PERMITTED);

    assertRow(ReviewStatus.SKIPPED, 0, 0, "AUTHOR_NOT_PERMITTED");
  }

  @Test
  void aRedeliveredUnclaimedFailureLeavesTheFailedRowAlone() {
    seedWithReason(ReviewStatus.FAILED, "MISSING_OR_INVALID_API_KEY");

    repository.failUnclaimed(KEY, RECEIVED, FailureClass.MISSING_OR_INVALID_API_KEY);

    assertRow(ReviewStatus.FAILED, 0, 0, "MISSING_OR_INVALID_API_KEY");
  }

  /** C-6: the row must match the DLQ entry the TERMINAL produces, not the older skip reason. */
  @Test
  void anUnclaimedFailureOverASkippedRowFailsIt() {
    seedWithReason(ReviewStatus.SKIPPED, "PR_CLOSED");

    repository.failUnclaimed(KEY, RECEIVED, FailureClass.MISSING_OR_INVALID_API_KEY);

    assertRow(ReviewStatus.FAILED, 0, 0, "MISSING_OR_INVALID_API_KEY");
  }

  @Test
  void aSkipOverAFailedRowKeepsItFailed() {
    seedWithReason(ReviewStatus.FAILED, "DIFF_TOO_LARGE");

    repository.skip(KEY, RECEIVED, FailureClass.PR_CLOSED);

    assertRow(ReviewStatus.FAILED, 0, 0, "DIFF_TOO_LARGE");
  }

  @Test
  void aSkipOverASucceededRowKeepsItSucceeded() {
    seedWithReason(ReviewStatus.SUCCEEDED, null);

    repository.skip(KEY, RECEIVED, FailureClass.PR_CLOSED);

    assertRow(ReviewStatus.SUCCEEDED, 0, 0, null);
  }

  @Test
  void supersededNeverWritesARow() {
    assertThatThrownBy(() -> repository.skip(KEY, RECEIVED, FailureClass.SUPERSEDED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(repository.find(KEY)).isEmpty();
  }

  @Test
  void stampingThePostAttemptKeepsTheRowProcessing() {
    long id = seed(ReviewStatus.PROCESSING, 1, 0);

    repository.stampPostAttempt(id);

    ReviewRow row = repository.find(KEY).orElseThrow();
    assertThat(row.status()).isEqualTo(ReviewStatus.PROCESSING);
    assertThat(Duration.between(row.postAttemptedAt(), Instant.now()).abs())
        .isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void stampingARowThatIsNotProcessingThrows() {
    long id = seed(ReviewStatus.AWAITING_RETRY, 1, 0);

    assertThatThrownBy(() -> repository.stampPostAttempt(id))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void finalizingRecordsTheReviewIdAndCompletionAndThenDedups() {
    long id = seed(ReviewStatus.PROCESSING, 1, 0);

    repository.finalizeSucceeded(id, 987L);

    Map<String, Object> row =
        JDBC.queryForMap("SELECT status, github_review_id, completed_at FROM reviews");
    assertThat(row.get("status")).isEqualTo("SUCCEEDED");
    assertThat(row.get("github_review_id")).isEqualTo(987L);
    assertThat(row.get("completed_at")).isNotNull();
    assertThat(repository.isReviewed(42L, "octo/repo", 7, "abc123")).isTrue();
  }

  @Test
  void finalizingAParkedRowThrows() {
    long id = seed(ReviewStatus.AWAITING_RETRY, 1, 0);

    assertThatThrownBy(() -> repository.finalizeSucceeded(id, 987L))
        .isInstanceOf(IllegalStateException.class);
  }

  private static long seed(ReviewStatus status, int errorAttempts, int deferAttempts) {
    return JDBC.queryForObject(
        """
        INSERT INTO reviews (installation_id, repo_full_name, pr_number, head_sha, round, status,
                             error_attempts, defer_attempts, first_received_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """,
        Long.class,
        KEY.installationId(),
        KEY.repoFullName(),
        KEY.prNumber(),
        KEY.headSha(),
        KEY.round(),
        status.name(),
        errorAttempts,
        deferAttempts,
        Timestamp.from(RECEIVED));
  }

  private static void seedWithReason(ReviewStatus status, String failureClass) {
    long id = seed(status, 0, 0);
    JDBC.update("UPDATE reviews SET failure_class = ? WHERE id = ?", failureClass, id);
  }

  private void assertRow(
      ReviewStatus status, int errorAttempts, int deferAttempts, String failureClass) {
    ReviewRow row = repository.find(KEY).orElseThrow();
    assertThat(row.status()).isEqualTo(status);
    assertThat(row.errorAttempts()).isEqualTo(errorAttempts);
    assertThat(row.deferAttempts()).isEqualTo(deferAttempts);
    assertThat(row.failureClass()).isEqualTo(failureClass);
  }
}
