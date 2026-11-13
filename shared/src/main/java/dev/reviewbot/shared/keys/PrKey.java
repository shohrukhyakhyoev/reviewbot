package dev.reviewbot.shared.keys;

import java.util.regex.Pattern;

/**
 * Identity of one pull request. Its rendered form is the Kafka key on all three topics (spec §3.1)
 * and the PR part of the Redis lock and dedup keys (§5.2).
 *
 * <p>Every instance is valid and round-trips through {@link #render()} and {@link #parse(String)}.
 */
public record PrKey(long installationId, String repoFullName, int prNumber) {

  static final String SEPARATOR = ":";

  // GitHub's full_name charset. Excludes ':' (the separator), so the rendered key stays parseable.
  private static final Pattern REPO_FULL_NAME = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

  public PrKey {
    requireInstallationId(installationId);
    if (prNumber < 1) {
      throw new IllegalArgumentException("prNumber must be >= 1, got " + prNumber);
    }
    requireRepoFullName(repoFullName);
  }

  // Shared with RedisKeys.ghcfg so the rule lives in one place.
  static void requireInstallationId(long installationId) {
    // < 1 also rejects 0, the value of an unset primitive.
    if (installationId < 1) {
      throw new IllegalArgumentException("installationId must be >= 1, got " + installationId);
    }
  }

  static void requireRepoFullName(String repoFullName) {
    if (repoFullName == null || !REPO_FULL_NAME.matcher(repoFullName).matches()) {
      throw new IllegalArgumentException("repoFullName must be 'owner/name', got " + repoFullName);
    }
  }

  public String render() {
    return installationId + SEPARATOR + repoFullName + SEPARATOR + prNumber;
  }

  /**
   * Inverse of {@link #render()}, canonical form only: one PR must have exactly one key string,
   * or it would hash to several Kafka partitions.
   *
   * @throws IllegalArgumentException for a malformed or non-canonical key (the message includes
   *     the key)
   */
  public static PrKey parse(String key) {
    if (key == null) {
      throw new IllegalArgumentException("PR key is null");
    }
    // Limit -1 keeps trailing empty parts: "1:acme/app:" is 3 parts, not 2.
    String[] parts = key.split(SEPARATOR, -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException(
          "PR key must have 3 parts, got " + parts.length + ": " + key);
    }
    PrKey parsed;
    try {
      parsed = new PrKey(Long.parseLong(parts[0]), parts[1], Integer.parseInt(parts[2]));
    } catch (IllegalArgumentException e) { // also NumberFormatException
      throw new IllegalArgumentException("invalid PR key " + key + ": " + e.getMessage(), e);
    }
    // parseLong/parseInt also accept "+5", leading zeros and non-ASCII digits.
    if (!parsed.render().equals(key)) {
      throw new IllegalArgumentException("PR key is not in canonical form: " + key);
    }
    return parsed;
  }
}
