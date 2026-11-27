package dev.reviewbot.shared.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ReviewRepositoryClaimTest extends PostgresTestSupport {

  private static final ReviewKey KEY = new ReviewKey(42L, "octo/repo", 7, "abc123", 1);
  private static final Instant RECEIVED = Instant.parse("2026-11-20T10:15:30Z");

  private final ReviewRepository repository = new ReviewRepository(JDBC);

  @Test
  void noRowInsertsProcessingWithOneErrorAttemptStampedWithTheHeaderTime() {
    ClaimResult result = repository.claim(new ClaimRequest(KEY, RECEIVED, false, false));

    assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
    ClaimResult.Claimed claimed = (ClaimResult.Claimed) result;
    assertThat(claimed.errorAttempts()).isEqualTo(1);
    assertThat(claimed.postAttemptedAt()).isNull();

    ReviewRow row = repository.find(KEY).orElseThrow();
    assertThat(row.id()).isEqualTo(claimed.rowId());
    assertThat(row.status()).isEqualTo(ReviewStatus.PROCESSING);
    assertThat(row.errorAttempts()).isEqualTo(1);
    assertThat(row.deferAttempts()).isZero();
    assertThat(row.firstReceivedAt()).isEqualTo(RECEIVED);
    assertThat(row.firstDeferredAt()).isNull();
    assertThat(row.failureClass()).isNull();
  }

  @Test
  void aDifferentRoundIsADifferentRow() {
    repository.claim(new ClaimRequest(KEY, RECEIVED, false, false));
    ReviewKey round2 = new ReviewKey(42L, "octo/repo", 7, "abc123", 2);

    ClaimResult result = repository.claim(new ClaimRequest(round2, RECEIVED, false, false));

    assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
    Map<String, Object> count = JDBC.queryForMap("SELECT count(*) AS n FROM reviews");
    assertThat(count.get("n")).isEqualTo(2L);
  }

  @Test
  void forceIsRejectedLoudlyInV1() {
    assertThatThrownBy(() -> repository.claim(new ClaimRequest(KEY, RECEIVED, false, true)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(repository.find(KEY)).isEmpty();
  }
}
