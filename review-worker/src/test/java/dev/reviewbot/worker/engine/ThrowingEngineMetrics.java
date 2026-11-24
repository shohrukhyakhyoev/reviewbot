package dev.reviewbot.worker.engine;

import java.util.Collection;

import org.apache.kafka.common.TopicPartition;

/** A metrics backend that is down: every call throws. */
final class ThrowingEngineMetrics implements EngineMetrics {

  @Override
  public void routedToClosedPartition(TopicPartition partition) {
    throw backendDown();
  }

  @Override
  public void outcomeHandlerFailed(TrackedOffset record, Throwable cause) {
    throw backendDown();
  }

  @Override
  public void markCompleteFailed(TrackedOffset record, Throwable cause) {
    throw backendDown();
  }

  @Override
  public void strayInterruptCleared(TrackedOffset record) {
    throw backendDown();
  }

  @Override
  public void pauseState(PauseReason reason, boolean active) {
    throw backendDown();
  }

  @Override
  public void commitFailed(Collection<TopicPartition> partitions, Exception cause) {
    throw backendDown();
  }

  @Override
  public void pollerDied(Throwable cause) {
    throw backendDown();
  }

  private static IllegalStateException backendDown() {
    return new IllegalStateException("metrics backend down: secret-host:9090");
  }
}
