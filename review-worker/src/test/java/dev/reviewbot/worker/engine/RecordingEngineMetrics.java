package dev.reviewbot.worker.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.common.TopicPartition;

final class RecordingEngineMetrics implements EngineMetrics {

  final List<TopicPartition> closedPartitionRoutes = new CopyOnWriteArrayList<>();
  final List<Throwable> outcomeHandlerFailures = new CopyOnWriteArrayList<>();
  final List<Throwable> markCompleteFailures = new CopyOnWriteArrayList<>();
  final List<TrackedOffset> clearedInterrupts = new CopyOnWriteArrayList<>();

  @Override
  public void routedToClosedPartition(TopicPartition partition) {
    closedPartitionRoutes.add(partition);
  }

  @Override
  public void outcomeHandlerFailed(TrackedOffset record, Throwable cause) {
    outcomeHandlerFailures.add(cause);
  }

  @Override
  public void strayInterruptCleared(TrackedOffset record) {
    clearedInterrupts.add(record);
  }

  @Override
  public void markCompleteFailed(TrackedOffset record, Throwable cause) {
    markCompleteFailures.add(cause);
  }
}
