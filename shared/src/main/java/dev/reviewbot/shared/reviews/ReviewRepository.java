package dev.reviewbot.shared.reviews;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.failure.FailureKind;

/** The {@code reviews} table (§5.1): the claim (§7.1 step 6) and the handler writes (§7.2). */
public final class ReviewRepository {

  /** §7.1 step 6 backstop; the §7.2 handler normally dead-letters at 5 first. */
  private static final int MAX_ERROR_ATTEMPTS_AT_CLAIM = 5;

  /** A claimed row: what the §7.2 handlers act on after step 6 (POSTING is v2). */
  private static final Set<ReviewStatus> CLAIMED =
      EnumSet.of(ReviewStatus.PROCESSING, ReviewStatus.POSTING);

  private static final Set<FailureKind> PARKABLE =
      EnumSet.of(FailureKind.TRANSIENT, FailureKind.DEFER, FailureKind.BREAKER_REMNANT);

  private final JdbcTemplate jdbc;

  public ReviewRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** §7.1 step 6. Call only while holding the PR lock (step 5). */
  public ClaimResult claim(ClaimRequest request) {
    if (request.force()) {
      throw new UnsupportedOperationException("/rereview force claims are v1.5");
    }
    Optional<ReviewRow> existing = find(request.key());
    return existing.isPresent() ? claimExisting(existing.get(), request) : insertFresh(request);
  }

  private ClaimResult claimExisting(ReviewRow row, ClaimRequest request) {
    return switch (row.status()) {
      case AWAITING_RETRY -> reclaim(row, parkCostsAnErrorAttempt(row.failureClass()));
      case PROCESSING -> reclaim(row, true);
      // §7.1 step 6 says take over a stale POSTING row, but §5.1 has no POSTING → PROCESSING, so
      // the guard throws. Unreachable in v1: nothing writes POSTING until the v2 fence.
      case POSTING -> reclaim(row, true);
      case SUCCEEDED -> new ClaimResult.AlreadySucceeded();
      case FAILED -> request.replay() ? restart(row, request) : new ClaimResult.AlreadyFailed();
      case SKIPPED -> restart(row, request);
    };
  }

  /** P1-2: only an error-class park costs an attempt; DEFER and BREAKER_PARKED hops are free. */
  private static boolean parkCostsAnErrorAttempt(String parkedClass) {
    return FailureClass.parse(parkedClass).kind() == FailureKind.TRANSIENT;
  }

  /**
   * Back to PROCESSING, adding one error attempt or none (P1-2). A stale PROCESSING row stays
   * PROCESSING: no status change, so no guard. The compare-and-set WHERE turns a concurrent change
   * since {@link #find} into RaceLost.
   */
  private ClaimResult reclaim(ReviewRow row, boolean countsAsError) {
    if (row.status() != ReviewStatus.PROCESSING) {
      ReviewTransitions.requireLegal(row.status(), ReviewStatus.PROCESSING, false);
    }
    int attempts = row.errorAttempts() + (countsAsError ? 1 : 0);
    int updated =
        jdbc.update(
            """
            UPDATE reviews SET status = 'PROCESSING', error_attempts = ?, updated_at = now()
             WHERE id = ? AND status = ? AND error_attempts = ?
            """,
            attempts,
            row.id(),
            row.status().name(),
            row.errorAttempts());
    if (updated == 0) {
      return new ClaimResult.RaceLost();
    }
    if (attempts > MAX_ERROR_ATTEMPTS_AT_CLAIM) {
      return new ClaimResult.ExhaustedPoison();
    }
    return new ClaimResult.Claimed(row.id(), attempts, row.postAttemptedAt());
  }

  /**
   * A fresh start on an existing row: DLQ replay over FAILED, or a no-longer-valid skip over
   * SKIPPED. post_attempted_at is kept: a prior post may have landed, so §7.1 12b must still run.
   */
  private ClaimResult restart(ReviewRow row, ClaimRequest request) {
    ReviewTransitions.requireLegal(row.status(), ReviewStatus.PROCESSING, request.replay());
    int updated =
        jdbc.update(
            """
            UPDATE reviews SET status = 'PROCESSING', error_attempts = 1, defer_attempts = 0,
                               first_received_at = ?, first_deferred_at = NULL, updated_at = now()
             WHERE id = ? AND status = ?
            """,
            timestamp(request.firstReceivedAt()),
            row.id(),
            row.status().name());
    if (updated == 0) {
      return new ClaimResult.RaceLost();
    }
    return new ClaimResult.Claimed(row.id(), 1, row.postAttemptedAt());
  }

  private ClaimResult insertFresh(ClaimRequest request) {
    ReviewTransitions.requireInsertable(ReviewStatus.PROCESSING);
    ReviewKey key = request.key();
    try {
      long id =
          jdbc.queryForObject(
              """
              INSERT INTO reviews (installation_id, repo_full_name, pr_number, head_sha, round,
                                   status, error_attempts, first_received_at)
              VALUES (?, ?, ?, ?, ?, ?, 1, ?)
              RETURNING id
              """,
              Long.class,
              key.installationId(),
              key.repoFullName(),
              key.prNumber(),
              key.headSha(),
              key.round(),
              ReviewStatus.PROCESSING.name(),
              timestamp(request.firstReceivedAt()));
      return new ClaimResult.Claimed(id, 1, null);
    } catch (DuplicateKeyException e) {
      // Another claimer inserted between our find() and this INSERT; its row is live, not stale.
      return new ClaimResult.RaceLost();
    }
  }

  /** §7.1 steps 1/4 DB fallback when the Redis marker misses: reviewed AND posted, any round. */
  public boolean isReviewed(long installationId, String repoFullName, int prNumber, String headSha) {
    return jdbc.queryForObject(
        """
        SELECT EXISTS (
          SELECT 1 FROM reviews
           WHERE installation_id = ? AND repo_full_name = ? AND pr_number = ?
             AND head_sha = ? AND status = 'SUCCEEDED'
        )
        """,
        Boolean.class,
        installationId,
        repoFullName,
        prNumber,
        headSha);
  }

  /** §7.1 step 2: reviews a worker is running right now for this installation (index-backed). */
  public long countInFlight(long installationId) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FROM reviews
         WHERE installation_id = ? AND status IN ('PROCESSING', 'POSTING')
        """,
        Long.class,
        installationId);
  }

  /** §7.2 DEFER / TRANSIENT / BREAKER_PARKED on a claimed row: park it for the retry topic. */
  public void parkForRetry(long rowId, FailureClass parkedClass) {
    FailureKind kind = parkedClass.kind();
    if (!PARKABLE.contains(kind)) {
      throw new IllegalArgumentException(parkedClass + " is " + kind + "; it does not park");
    }
    String counters = kind == FailureKind.DEFER ? ", defer_attempts = defer_attempts + 1" : "";
    transitionClaimed(
        rowId, ReviewStatus.AWAITING_RETRY, "failure_class = ?" + counters, parkedClass.wire());
  }

  /** §7.2 DEFER: starts the 2 h SLA clock on the first defer only, and returns it (C-3). */
  public Instant startDeferClock(long rowId) {
    OffsetDateTime started =
        jdbc.queryForObject(
            """
            UPDATE reviews SET first_deferred_at = COALESCE(first_deferred_at, now()),
                               updated_at = now()
             WHERE id = ?
            RETURNING first_deferred_at
            """,
            OffsetDateTime.class,
            rowId);
    return started.toInstant();
  }

  /** §7.2 TERMINAL on a claimed row. */
  public void failClaimed(long rowId, FailureClass terminalClass) {
    requireKind(terminalClass, FailureKind.TERMINAL);
    transitionClaimed(rowId, ReviewStatus.FAILED, "failure_class = ?", terminalClass.wire());
  }

  /** §7.2 TERMINAL before any claim (C-6): upsert the row as FAILED. */
  public void failUnclaimed(ReviewKey key, Instant firstReceivedAt, FailureClass terminalClass) {
    requireKind(terminalClass, FailureKind.TERMINAL);
    recordUnclaimed(key, firstReceivedAt, ReviewStatus.FAILED, terminalClass);
  }

  /**
   * A gate's skip (PR_CLOSED, AUTHOR_NOT_PERMITTED, SUSPENDED): upsert SKIPPED with the reason.
   * SUPERSEDED never gets a row (§7.1 step 0), and no SKIPPED row ever satisfies the dedup read.
   */
  public void skip(ReviewKey key, Instant firstReceivedAt, FailureClass reason) {
    requireKind(reason, FailureKind.SKIP);
    if (reason.equals(FailureClass.SUPERSEDED)) {
      throw new IllegalArgumentException("SUPERSEDED is completed without a row (§7.1 step 0)");
    }
    recordUnclaimed(key, firstReceivedAt, ReviewStatus.SKIPPED, reason);
  }

  /** §7.1 step 12a, immediately before the GitHub POST. */
  public void stampPostAttempt(long rowId) {
    int updated =
        jdbc.update(
            """
            UPDATE reviews SET post_attempted_at = now(), updated_at = now()
             WHERE id = ? AND status = 'PROCESSING'
            """,
            rowId);
    if (updated == 0) {
      throw new IllegalStateException("row " + rowId + " is not a claimed PROCESSING row");
    }
  }

  /** §7.1 step 13, first of the I8 order (DB → Redis marker → unlock). */
  public void finalizeSucceeded(long rowId, long githubReviewId) {
    transitionClaimed(
        rowId,
        ReviewStatus.SUCCEEDED,
        "github_review_id = ?, completed_at = now()",
        githubReviewId);
  }

  private void recordUnclaimed(
      ReviewKey key, Instant firstReceivedAt, ReviewStatus to, FailureClass reason) {
    Optional<ReviewRow> existing = find(key);
    if (existing.isEmpty()) {
      ReviewTransitions.requireInsertable(to);
      try {
        jdbc.update(
            """
            INSERT INTO reviews (installation_id, repo_full_name, pr_number, head_sha, round,
                                 status, failure_class, first_received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            key.installationId(),
            key.repoFullName(),
            key.prNumber(),
            key.headSha(),
            key.round(),
            to.name(),
            reason.wire(),
            timestamp(firstReceivedAt));
        return;
      } catch (DuplicateKeyException e) {
        existing = find(key);
      }
    }
    ReviewRow row = existing.orElseThrow();
    if (row.status() == to) {
      // Redelivery or a newer verdict of the same kind: keep the status, record the latest reason.
      jdbc.update(
          "UPDATE reviews SET failure_class = ?, updated_at = now() WHERE id = ? AND status = ?",
          reason.wire(),
          row.id(),
          to.name());
    } else if (ReviewTransitions.isLegal(row.status(), to, false)) {
      transition(row.id(), row.status(), to, "failure_class = ?", reason.wire());
    }
    // Otherwise the row is SUCCEEDED, or FAILED meeting a skip: the earlier verdict stands.
  }

  /** The handler paths: the table alone would also let e.g. SKIPPED → FAILED through. */
  private void transitionClaimed(long rowId, ReviewStatus to, String assignments, Object... values) {
    ReviewStatus from = statusOf(rowId);
    if (!CLAIMED.contains(from)) {
      throw new IllegalStateException("row " + rowId + " is " + from + ", not claimed");
    }
    transition(rowId, from, to, assignments, values);
  }

  /** Guarded compare-and-set: a row that changed since it was read is a bug, not a race to win. */
  private void transition(
      long rowId, ReviewStatus from, ReviewStatus to, String assignments, Object... values) {
    ReviewTransitions.requireLegal(from, to, false);
    Object[] args = new Object[values.length + 3];
    args[0] = to.name();
    System.arraycopy(values, 0, args, 1, values.length);
    args[values.length + 1] = rowId;
    args[values.length + 2] = from.name();
    int updated =
        jdbc.update(
            "UPDATE reviews SET status = ?, "
                + assignments
                + ", updated_at = now() WHERE id = ? AND status = ?",
            args);
    if (updated == 0) {
      throw new IllegalStateException("row " + rowId + " changed since it was read");
    }
  }

  private ReviewStatus statusOf(long rowId) {
    return jdbc
        .query(
            "SELECT status FROM reviews WHERE id = ?",
            (rs, rowNum) -> ReviewStatus.valueOf(rs.getString("status")),
            rowId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no reviews row " + rowId));
  }

  private static void requireKind(FailureClass failureClass, FailureKind expected) {
    if (failureClass.kind() != expected) {
      throw new IllegalArgumentException(
          failureClass + " is " + failureClass.kind() + ", expected " + expected);
    }
  }

  Optional<ReviewRow> find(ReviewKey key) {
    return jdbc
        .query(
            """
            SELECT id, status, error_attempts, defer_attempts, failure_class,
                   post_attempted_at, first_received_at, first_deferred_at
              FROM reviews
             WHERE installation_id = ? AND repo_full_name = ? AND pr_number = ?
               AND head_sha = ? AND round = ?
            """,
            ReviewRepository::mapRow,
            key.installationId(),
            key.repoFullName(),
            key.prNumber(),
            key.headSha(),
            key.round())
        .stream()
        .findFirst();
  }

  private static ReviewRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ReviewRow(
        rs.getLong("id"),
        ReviewStatus.valueOf(rs.getString("status")),
        rs.getInt("error_attempts"),
        rs.getInt("defer_attempts"),
        rs.getString("failure_class"),
        instant(rs, "post_attempted_at"),
        instant(rs, "first_received_at"),
        instant(rs, "first_deferred_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** pgjdbc binds {@code Instant} poorly; a {@code Timestamp} maps cleanly onto TIMESTAMPTZ. */
  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
