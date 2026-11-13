package dev.reviewbot.shared.keys;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The only place Redis key strings are formatted (spec §5.2).
 *
 * <p>There is deliberately no {@code skip:} builder: a skipped or superseded SHA must never be
 * written under {@code dedup:} (§19.14).
 */
public final class RedisKeys {

  public static final Duration DELIVERY_TTL = Duration.ofHours(24);
  public static final Duration DEDUP_TTL = Duration.ofDays(7);

  /** Redisson watchdog timeout. Never pass it as {@code leaseTime}: that disables renewal (§19.3). */
  public static final Duration LOCK_TTL = Duration.ofSeconds(30);

  public static final Duration GHCFG_TTL = Duration.ofMinutes(5);

  private static final String SEP = PrKey.SEPARATOR;

  private RedisKeys() {}

  /** {@code delivery:{guid}} */
  public static String delivery(String deliveryGuid) {
    return key("delivery", requireToken("deliveryGuid", deliveryGuid));
  }

  /** {@code ghcfg:{inst}:{repo}} */
  public static String ghcfg(long installationId, String repoFullName) {
    PrKey.requireInstallationId(installationId);
    PrKey.requireRepoFullName(repoFullName);
    return key("ghcfg", installationId, repoFullName);
  }

  /**
   * {@code dedup:{inst}:{repo}:{pr}:{sha}:{round}}. Existence means reviewed-and-posted, nothing
   * else.
   */
  public static String dedup(PrKey prKey, String headSha, int round) {
    if (round < 1) {
      throw new IllegalArgumentException("round must be >= 1, got " + round);
    }
    return key("dedup", prKey.render(), requireToken("headSha", headSha), round);
  }

  /** {@code lock:review:{inst}:{repo}:{pr}}. No SHA: one SHA of a PR in flight at a time. */
  public static String lock(PrKey prKey) {
    return key("lock", "review", prKey.render());
  }

  private static String key(Object... parts) {
    return Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining(SEP));
  }

  private static String requireToken(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is null or blank");
    }
    if (value.contains(SEP)) {
      throw new IllegalArgumentException(name + " must not contain '" + SEP + "', got " + value);
    }
    return value;
  }
}
