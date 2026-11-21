package dev.reviewbot.worker.engine;

import org.apache.kafka.common.TopicPartition;

/** A metrics backend that is down: every call throws. */
final class ThrowingEngineMetrics implements EngineMetrics {

  @Override
  public void routedToClosedPartition(TopicPartition partition) {
    throw new IllegalStateException("metrics backend down: secret-host:9090");
  }

  @Override
  public void outcomeHandlerFailed(TrackedOffset record, Throwable cause) {
    throw new IllegalStateException("metrics backend down: secret-host:9090");
  }

  @Override
  public void strayInterruptCleared(TrackedOffset record) {
    throw new IllegalStateException("metrics backend down: secret-host:9090");
  }

  @Override
  public void markCompleteFailed(TrackedOffset record, Throwable cause) {
    throw new IllegalStateException("metrics backend down: secret-host:9090");
  }
}
