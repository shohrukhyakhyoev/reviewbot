package dev.reviewbot.worker.engine;

import java.util.Collection;

import org.apache.kafka.common.TopicPartition;

/**
 * What the engine counts about itself; E7 backs it with a real registry. Every engine component
 * wraps its implementation in {@link SafeEngineMetrics}, so a throw is contained.
 */
interface EngineMetrics {

  /** Unreachable while the poller filters closed partitions (E4-3); a hit means that filter broke. */
  void routedToClosedPartition(TopicPartition partition);

  /** The outcome handler threw for this record; it is still completed. */
  void outcomeHandlerFailed(TrackedOffset record, Throwable cause);

  /** {@code markComplete} threw for this record; never retried, its permit is already settled. */
  void markCompleteFailed(TrackedOffset record, Throwable cause);

  /** The completing thread was interrupted before this record's handler; the flag was cleared. */
  void strayInterruptCleared(TrackedOffset record);

  /** Sets the {@code pause_state} gauge for one reason: is it holding intake closed right now? */
  void pauseState(PauseReason reason, boolean active);

  /** Kafka rejected a commit: an async one is resent next cycle, a sync one leaves the redo to the next owner. */
  void commitFailed(Collection<TopicPartition> partitions, Exception cause);

  /** Shutdown stopped waiting with records still running; the next owner redoes them. */
  void shutdownGraceExpired(int stillInFlight);

  /** The consumer did not leave the group cleanly; its partitions move after the session timeout. */
  void consumerCloseFailed(Exception cause);

  /** Something escaped the poll loop; the worker consumes nothing until it is restarted. */
  void pollerDied(Throwable cause);
}
