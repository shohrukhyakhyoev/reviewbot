package dev.reviewbot.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import dev.reviewbot.shared.dlq.DlqException;
import dev.reviewbot.shared.keys.RedisKeys;

/** Expected values are typed from the spec §12 table, never read back from the code under test. */
class ReviewbotPropertiesTest {

  private static ReviewbotProperties bind(Map<String, String> overrides) {
    return new Binder(new MapConfigurationPropertySource(overrides))
        .bindOrCreate("reviewbot", ReviewbotProperties.class);
  }

  private final ReviewbotProperties defaults = bind(Map.of());

  @Test
  void backoffDefaultsMatchSpec12() {
    var backoff = defaults.backoff();
    assertThat(backoff.errorDelays())
        .containsExactly(
            Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
    assertThat(backoff.lockHeldDelay()).isEqualTo(Duration.ofSeconds(30));
    assertThat(backoff.rateLimitDelays())
        .containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5));
    assertThat(backoff.deferSla()).isEqualTo(Duration.ofHours(2));
    assertThat(backoff.breakerCooldown()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void timeoutDefaultsMatchSpec12() {
    var timeouts = defaults.timeouts();
    assertThat(timeouts.githubConnect()).isEqualTo(Duration.ofSeconds(2));
    assertThat(timeouts.githubRead()).isEqualTo(Duration.ofSeconds(10));
    assertThat(timeouts.anthropic()).isEqualTo(Duration.ofSeconds(120));
    assertThat(timeouts.postgres()).isEqualTo(Duration.ofSeconds(5));
    assertThat(timeouts.redis()).isEqualTo(Duration.ofSeconds(2));
    assertThat(timeouts.lockWait()).isEqualTo(Duration.ofSeconds(2));
    assertThat(timeouts.gatewayProduce()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void consumerDefaultsMatchSpec12() {
    var consumer = defaults.consumer();
    assertThat(consumer.sessionTimeout()).isEqualTo(Duration.ofSeconds(45));
    assertThat(consumer.heartbeatInterval()).isEqualTo(Duration.ofSeconds(3));
    assertThat(consumer.maxPollInterval()).isEqualTo(Duration.ofSeconds(300));
    assertThat(consumer.pollTimeout()).isEqualTo(Duration.ofMillis(200));
    assertThat(consumer.maxPollRecords()).isEqualTo(100);
    assertThat(consumer.inFlightCap()).isEqualTo(200);
    assertThat(consumer.resumeThreshold()).isEqualTo(100);
    assertThat(consumer.shutdownGrace()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void healthDefaultsMatchSpec12() {
    var health = defaults.health();
    assertThat(health.checkInterval()).isEqualTo(Duration.ofSeconds(5));
    assertThat(health.failuresToPause()).isEqualTo(3);
    assertThat(health.successesToResume()).isEqualTo(3);
    assertThat(health.minSuccessSpan()).isEqualTo(Duration.ofSeconds(30));
    assertThat(health.probeInterval()).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void limitDefaultsMatchSpec12() {
    var limits = defaults.limits();
    assertThat(limits.rateCapPerInstallation()).isEqualTo(5);
    assertThat(limits.staleProcessing()).isEqualTo(Duration.ofMinutes(10));
    assertThat(limits.authorCacheTtl()).isEqualTo(Duration.ofMinutes(5));
    assertThat(limits.installationTokenRefreshMargin()).isEqualTo(Duration.ofMinutes(5));
    assertThat(limits.charsPerToken()).isEqualTo(4);
    assertThat(limits.defaultTokenBudget()).isEqualTo(150_000);
    assertThat(limits.tokenGuardFactor()).isEqualTo(0.9);
    assertThat(limits.tokenBudgets()).isEmpty();
  }

  @Test
  void numbersOwnedElsewhereMatchSpec12() {
    assertThat(RedisKeys.DELIVERY_TTL).isEqualTo(Duration.ofHours(24));
    assertThat(RedisKeys.DEDUP_TTL).isEqualTo(Duration.ofDays(7));
    assertThat(RedisKeys.GHCFG_TTL).isEqualTo(Duration.ofMinutes(5));
    assertThat(RedisKeys.LOCK_TTL).isEqualTo(Duration.ofSeconds(30));
    assertThat(DlqException.MAX_BODY_SNIPPET).isEqualTo(2048);
  }

  @Test
  void defaultModelMatchesSpec51() {
    assertThat(ModelDefaults.DEFAULT_MODEL).isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void guardThresholdIsGuardFactorTimesTheModelsBudget() {
    var limits = bind(Map.of("reviewbot.limits.token-budgets.small-model", "1000")).limits();

    assertThat(limits.guardThresholdTokens("small-model")).isEqualTo(900);
    assertThat(limits.guardThresholdTokens("unlisted-model")).isEqualTo(135_000);
    assertThat(limits.guardThresholdTokens(null)).isEqualTo(135_000);
  }

  @Test
  void overridesBind() {
    var bound =
        bind(
            Map.of(
                "reviewbot.backoff.error-delays", "1s,2s",
                "reviewbot.consumer.max-poll-records", "50",
                "reviewbot.timeouts.anthropic", "90s"));

    assertThat(bound.backoff().errorDelays()).isEqualTo(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    assertThat(bound.consumer().maxPollRecords()).isEqualTo(50);
    assertThat(bound.timeouts().anthropic()).isEqualTo(Duration.ofSeconds(90));
    assertThat(bound.timeouts().githubRead()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void aPollBatchLargerThanTheResumeThresholdFailsAtBind() {
    // CF-1: a full batch must always fit the permits that let the poller resume.
    assertThatThrownBy(() -> bind(Map.of("reviewbot.consumer.max-poll-records", "101")))
        .isInstanceOf(BindException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resumeThresholdAboveTheInFlightCapFailsAtBind() {
    assertThatThrownBy(() -> bind(Map.of("reviewbot.consumer.resume-threshold", "201")))
        .isInstanceOf(BindException.class);
  }

  @Test
  void guardFactorOutsideZeroToOneFailsAtBind() {
    assertThatThrownBy(() -> bind(Map.of("reviewbot.limits.token-guard-factor", "1.5")))
        .isInstanceOf(BindException.class);
    assertThatThrownBy(() -> bind(Map.of("reviewbot.limits.token-guard-factor", "0")))
        .isInstanceOf(BindException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "reviewbot.backoff.defer-sla=0s",
        "reviewbot.backoff.error-delays=10s,-1s",
        "reviewbot.timeouts.redis=-2s",
        "reviewbot.consumer.heartbeat-interval=45s",
        "reviewbot.consumer.heartbeat-interval=16s",
        "reviewbot.consumer.poll-timeout=300s",
        "reviewbot.consumer.poll-timeout=0s",
        "reviewbot.health.failures-to-pause=0",
        "reviewbot.health.probe-interval=0s",
        "reviewbot.limits.rate-cap-per-installation=0",
        "reviewbot.limits.chars-per-token=0",
        "reviewbot.limits.default-token-budget=0",
        "reviewbot.limits.author-cache-ttl=0s",
        "reviewbot.limits.token-budgets.small-model=0"
      })
  void nonPositiveValuesFailAtBind(String assignment) {
    var parts = assignment.split("=", 2);

    assertThatThrownBy(() -> bind(Map.of(parts[0], parts[1]))).isInstanceOf(BindException.class);
  }

  @Test
  void aModelIdWithDotsKeepsItsDotsAsAMapKey() {
    // Checked because Map<String, Object> keys lose dots; for Integer values both forms keep them.
    var bracketed = bind(Map.of("reviewbot.limits.token-budgets[claude-3.5-x]", "1000")).limits();
    var plain = bind(Map.of("reviewbot.limits.token-budgets.claude-3.5-x", "1000")).limits();

    assertThat(bracketed.guardThresholdTokens("claude-3.5-x")).isEqualTo(900);
    assertThat(plain.guardThresholdTokens("claude-3.5-x")).isEqualTo(900);
  }

  @Test
  void emptyBackoffListFailsAtBind() {
    assertThatThrownBy(() -> bind(Map.of("reviewbot.backoff.error-delays", "")))
        .isInstanceOf(BindException.class);
  }
}
