package dev.reviewbot.shared.reviews;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Two claimers that both read "no row" before either inserts: the UNIQUE constraint must pick one
 * winner and the other must see RaceLost. A test-only trigger parks every INSERT on an advisory
 * lock the test holds, and the test opens the gate only once both INSERTs wait there, so both
 * reads have provably happened first. No sleeps, no timing assumption.
 */
class ReviewRepositoryClaimRaceTest extends PostgresTestSupport {

  private static final long GATE = 4242L;
  private static final ReviewKey KEY = new ReviewKey(42L, "octo/repo", 7, "abc123", 1);
  private static final ClaimRequest REQUEST =
      new ClaimRequest(KEY, Instant.parse("2026-11-20T10:15:30Z"), false, false);

  private final ReviewRepository repository = new ReviewRepository(JDBC);

  @BeforeEach
  void gateInserts() {
    JDBC.execute(
        """
        CREATE OR REPLACE FUNCTION test_gated_insert() RETURNS trigger AS $$
        BEGIN PERFORM pg_advisory_xact_lock(%d); RETURN NEW; END $$ LANGUAGE plpgsql
        """
            .formatted(GATE));
    JDBC.execute(
        "CREATE TRIGGER test_gated_insert BEFORE INSERT ON reviews "
            + "FOR EACH ROW EXECUTE FUNCTION test_gated_insert()");
  }

  @AfterEach
  void ungateInserts() {
    JDBC.execute("DROP TRIGGER IF EXISTS test_gated_insert ON reviews");
    JDBC.execute("DROP FUNCTION IF EXISTS test_gated_insert()");
  }

  @RepeatedTest(5)
  void twoClaimersThatBothSawNoRowGetOneWinnerAndOneRaceLost() throws Exception {
    List<ClaimResult> results;
    try (Connection gate =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ExecutorService pool = Executors.newFixedThreadPool(2)) {
      gate.createStatement().execute("SELECT pg_advisory_lock(" + GATE + ")");
      Future<ClaimResult> first = pool.submit(() -> repository.claim(REQUEST));
      Future<ClaimResult> second = pool.submit(() -> repository.claim(REQUEST));
      awaitInsertsWaitingAtTheGate(2);
      gate.createStatement().execute("SELECT pg_advisory_unlock(" + GATE + ")");
      results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    assertThat(results)
        .filteredOn(ClaimResult.Claimed.class::isInstance)
        .singleElement()
        .extracting(r -> ((ClaimResult.Claimed) r).errorAttempts())
        .isEqualTo(1);
    assertThat(results).filteredOn(ClaimResult.RaceLost.class::isInstance).hasSize(1);
    assertThat(JDBC.queryForObject("SELECT count(*) FROM reviews", Long.class)).isEqualTo(1L);
  }

  private static void awaitInsertsWaitingAtTheGate(int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (waitingAtTheGate() < expected) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("claimers never reached their INSERT");
      }
      Thread.onSpinWait();
    }
  }

  private static long waitingAtTheGate() {
    return JDBC.queryForObject(
        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = ? AND NOT granted",
        Long.class,
        GATE);
  }
}
