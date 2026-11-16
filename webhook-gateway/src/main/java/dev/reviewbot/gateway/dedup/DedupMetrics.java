package dev.reviewbot.gateway.dedup;

/** What the gateway counts about delivery dedup; E7 backs it with a real registry. */
interface DedupMetrics {

  void hit();

  /** {@code check} could not reach Redis and answered "miss", so the event is produced anyway. */
  void checkFailedOpen(Exception cause);

  /** {@code markEnqueued} could not write the flag; a redelivery may produce a duplicate. */
  void markFailed(Exception cause);
}
