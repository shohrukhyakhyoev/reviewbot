package dev.reviewbot.gateway.dedup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stand-in until E7 adds a metrics registry: one log line per outcome. */
final class LoggingDedupMetrics implements DedupMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingDedupMetrics.class);

  @Override
  public void hit() {
    LOG.info("delivery dedup hit");
  }

  @Override
  public void checkFailedOpen(Exception cause) {
    LOG.warn("delivery dedup check failed open, producing anyway cause={}", name(cause));
  }

  @Override
  public void markFailed(Exception cause) {
    LOG.warn(
        "delivery dedup mark failed, a redelivery may produce a duplicate cause={}", name(cause));
  }

  // Class name only: during an outage every webhook logs this, and a Redis message names the host.
  private static String name(Exception cause) {
    return cause.getClass().getSimpleName();
  }
}
