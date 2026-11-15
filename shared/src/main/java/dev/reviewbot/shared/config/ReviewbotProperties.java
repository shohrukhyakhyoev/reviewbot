package dev.reviewbot.shared.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Spec §12 as {@code reviewbot.*} properties; Redis TTLs and the snippet cap live elsewhere. */
@ConfigurationProperties("reviewbot")
public record ReviewbotProperties(
    @DefaultValue Backoff backoff,
    @DefaultValue Timeouts timeouts,
    @DefaultValue Consumer consumer,
    @DefaultValue Health health,
    @DefaultValue Limits limits) {

  /** Error delays apply to attempts 1-4; attempt 5 dead-letters in the handler (C-2). */
  public record Backoff(
      @DefaultValue({"10s", "1m", "5m", "15m"}) List<Duration> errorDelays,
      @DefaultValue("30s") Duration lockHeldDelay,
      // Attempt n waits the nth entry; later attempts stay on the last one (capped).
      @DefaultValue({"1m", "2m", "5m"}) List<Duration> rateLimitDelays,
      @DefaultValue("2h") Duration deferSla,
      // Also the not_before of a BREAKER_PARKED record.
      @DefaultValue("60s") Duration breakerCooldown) {

    public Backoff {
      errorDelays = positiveCopy(errorDelays, "errorDelays");
      rateLimitDelays = positiveCopy(rateLimitDelays, "rateLimitDelays");
      positive(lockHeldDelay, "lockHeldDelay");
      positive(deferSla, "deferSla");
      positive(breakerCooldown, "breakerCooldown");
    }
  }

  public record Timeouts(
      @DefaultValue("2s") Duration githubConnect,
      @DefaultValue("10s") Duration githubRead,
      @DefaultValue("120s") Duration anthropic,
      @DefaultValue("5s") Duration postgres,
      @DefaultValue("2s") Duration redis,
      @DefaultValue("2s") Duration lockWait,
      @DefaultValue("5s") Duration gatewayProduce) {

    public Timeouts {
      positive(githubConnect, "githubConnect");
      positive(githubRead, "githubRead");
      positive(anthropic, "anthropic");
      positive(postgres, "postgres");
      positive(redis, "redis");
      positive(lockWait, "lockWait");
      positive(gatewayProduce, "gatewayProduce");
    }
  }

  public record Consumer(
      @DefaultValue("45s") Duration sessionTimeout,
      @DefaultValue("3s") Duration heartbeatInterval,
      @DefaultValue("300s") Duration maxPollInterval,
      @DefaultValue("200ms") Duration pollTimeout,
      @DefaultValue("100") int maxPollRecords,
      @DefaultValue("200") int inFlightCap,
      @DefaultValue("100") int resumeThreshold,
      @DefaultValue("30s") Duration shutdownGrace) {

    public Consumer {
      positive(sessionTimeout, "sessionTimeout");
      positive(heartbeatInterval, "heartbeatInterval");
      positive(maxPollInterval, "maxPollInterval");
      positive(pollTimeout, "pollTimeout");
      positive(shutdownGrace, "shutdownGrace");
      // Kafka's guidance: a heartbeat at most a third of the session, so one miss is not a rebalance.
      if (heartbeatInterval.multipliedBy(3).compareTo(sessionTimeout) > 0) {
        throw new IllegalArgumentException("heartbeatInterval must be at most sessionTimeout / 3");
      }
      if (pollTimeout.compareTo(maxPollInterval) >= 0) {
        throw new IllegalArgumentException("pollTimeout must be below maxPollInterval");
      }
      // I6 / CF-1, CF-2: a full poll batch must fit the permits that let the poller resume.
      if (maxPollRecords < 1 || maxPollRecords > resumeThreshold || resumeThreshold > inFlightCap) {
        throw new IllegalArgumentException(
            "need 1 <= maxPollRecords <= resumeThreshold <= inFlightCap, got "
                + maxPollRecords
                + " / "
                + resumeThreshold
                + " / "
                + inFlightCap);
      }
    }
  }

  public record Health(
      @DefaultValue("5s") Duration checkInterval,
      @DefaultValue("3") int failuresToPause,
      @DefaultValue("3") int successesToResume,
      @DefaultValue("30s") Duration minSuccessSpan,
      @DefaultValue("15s") Duration probeInterval) {

    public Health {
      positive(checkInterval, "checkInterval");
      positive(minSuccessSpan, "minSuccessSpan");
      positive(probeInterval, "probeInterval");
      atLeastOne(failuresToPause, "failuresToPause");
      atLeastOne(successesToResume, "successesToResume");
    }
  }

  public record Limits(
      @DefaultValue("5") int rateCapPerInstallation,
      @DefaultValue("10m") Duration staleProcessing,
      @DefaultValue("5m") Duration authorCacheTtl,
      // §12 says "bounded" without a number; this one is ours.
      @DefaultValue("10000") int authorCacheMaxEntries,
      @DefaultValue("5m") Duration installationTokenRefreshMargin,
      @DefaultValue("4") int charsPerToken,
      @DefaultValue("150000") int defaultTokenBudget,
      @DefaultValue("0.9") double tokenGuardFactor,
      Map<String, Integer> tokenBudgets) {

    public Limits {
      atLeastOne(rateCapPerInstallation, "rateCapPerInstallation");
      atLeastOne(authorCacheMaxEntries, "authorCacheMaxEntries");
      atLeastOne(charsPerToken, "charsPerToken");
      atLeastOne(defaultTokenBudget, "defaultTokenBudget");
      positive(staleProcessing, "staleProcessing");
      positive(authorCacheTtl, "authorCacheTtl");
      positive(installationTokenRefreshMargin, "installationTokenRefreshMargin");
      if (!(tokenGuardFactor > 0 && tokenGuardFactor <= 1)) {
        throw new IllegalArgumentException("tokenGuardFactor must be in (0, 1], got " + tokenGuardFactor);
      }
      tokenBudgets = tokenBudgets == null ? Map.of() : Map.copyOf(tokenBudgets);
      tokenBudgets.forEach((model, budget) -> atLeastOne(budget, "tokenBudgets[" + model + "]"));
    }

    /** Estimated tokens above this trip the size guard (§7.1 step 9); null model = the default. */
    public long guardThresholdTokens(String modelId) {
      var budget = modelId == null ? defaultTokenBudget : tokenBudgets.getOrDefault(modelId, defaultTokenBudget);
      return (long) (budget * tokenGuardFactor);
    }
  }

  private static List<Duration> positiveCopy(List<Duration> delays, String name) {
    if (delays == null || delays.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    delays.forEach(d -> positive(d, name));
    return List.copyOf(delays);
  }

  private static void positive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive, got " + value);
    }
  }

  private static void atLeastOne(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be >= 1, got " + value);
    }
  }
}
