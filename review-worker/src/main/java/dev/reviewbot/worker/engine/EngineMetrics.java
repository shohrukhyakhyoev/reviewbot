package dev.reviewbot.worker.engine;

import org.apache.kafka.common.TopicPartition;

/**
 * What the engine counts about its own failures; E7 backs it with a real registry. The dispatcher
 * and the drainer wrap every implementation in {@link SafeEngineMetrics}, so a throw is contained.
 */
interface EngineMetrics {

  /** Unreachable while the poller filters closed partitions (E4-3); a hit means that filter broke. */
  void routedToClosedPartition(TopicPartition partition);

  /** The outcome handler threw for this record; the drainer still completes it. */
  void outcomeHandlerFailed(TrackedOffset record, Throwable cause);

  /** {@code markComplete} threw for this record; never retried, its permit is already settled. */
  void markCompleteFailed(TrackedOffset record, Throwable cause);

  /** The drainer found its thread interrupted before this record's handler and cleared it. */
  void strayInterruptCleared(TrackedOffset record);
}
