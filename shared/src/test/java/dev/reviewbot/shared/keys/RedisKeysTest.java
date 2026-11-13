package dev.reviewbot.shared.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Expected strings are written from the spec §5.2 table, not computed from the builders. */
class RedisKeysTest {

  private static final PrKey PR = new PrKey(12345, "acme/app", 17);

  @Test
  void deliveryGolden() {
    assertThat(RedisKeys.delivery("72d3162e-cc78-11e3-81ab-4c9367dc0958"))
        .isEqualTo("delivery:72d3162e-cc78-11e3-81ab-4c9367dc0958");
  }

  @Test
  void ghcfgGolden() {
    assertThat(RedisKeys.ghcfg(12345, "acme/app")).isEqualTo("ghcfg:12345:acme/app");
  }

  @Test
  void dedupGolden() {
    assertThat(RedisKeys.dedup(PR, "abc123", 1)).isEqualTo("dedup:12345:acme/app:17:abc123:1");
  }

  @Test
  void dedupDiffersPerRound() {
    // C-4: a /rereview of the same SHA must not hit round 1's marker.
    assertThat(RedisKeys.dedup(PR, "abc123", 2))
        .isEqualTo("dedup:12345:acme/app:17:abc123:2")
        .isNotEqualTo(RedisKeys.dedup(PR, "abc123", 1));
  }

  @Test
  void lockGoldenHasNoSha() {
    assertThat(RedisKeys.lock(PR)).isEqualTo("lock:review:12345:acme/app:17");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "a:b"})
  void keySegmentsRejectNullBlankOrSeparator(String bad) {
    assertThatThrownBy(() -> RedisKeys.delivery(bad)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RedisKeys.ghcfg(12345, bad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RedisKeys.dedup(PR, bad, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void ghcfgRejectsInstallationIdBelowOne(long installationId) {
    assertThatThrownBy(() -> RedisKeys.ghcfg(installationId, "acme/app"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void dedupRejectsRoundBelowOne(int round) {
    assertThatThrownBy(() -> RedisKeys.dedup(PR, "abc123", round))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ttlsMatchSpecTable() {
    assertThat(RedisKeys.DELIVERY_TTL).isEqualTo(Duration.ofHours(24));
    assertThat(RedisKeys.DEDUP_TTL).isEqualTo(Duration.ofDays(7));
    assertThat(RedisKeys.LOCK_TTL).isEqualTo(Duration.ofSeconds(30));
    assertThat(RedisKeys.GHCFG_TTL).isEqualTo(Duration.ofMinutes(5));
  }
}
