package dev.reviewbot.worker.engine;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.common.TopicPartition;

final class RecordingEngineMetrics implements EngineMetrics {

  final List<TopicPartition> closedPartitionRoutes = new CopyOnWriteArrayList<>();
  final List<Throwable> outcomeHandlerFailures = new CopyOnWriteArrayList<>();
  final List<Throwable> markCompleteFailures = new CopyOnWriteArrayList<>();
  final List<TrackedOffset> clearedInterrupts = new CopyOnWriteArrayList<>();
  final List<Collection<TopicPartition>> commitFailures = new CopyOnWriteArrayList<>();
  final List<Throwable> pollerDeaths = new CopyOnWriteArrayList<>();
  final List<Integer> expiredShutdownGraces = new CopyOnWriteArrayList<>();
  final List<Exception> closeFailures = new CopyOnWriteArrayList<>();
  final List<String> pauseStates = new CopyOnWriteArrayList<>();

  @Override
  public void routedToClosedPartition(TopicPartition partition) {
    closedPartitionRoutes.add(partition);
  }

  @Override
  public void outcomeHandlerFailed(TrackedOffset record, Throwable cause) {
    outcomeHandlerFailures.add(cause);
  }

  @Override
  public void markCompleteFailed(TrackedOffset record, Throwable cause) {
    markCompleteFailures.add(cause);
  }

  @Override
  public void strayInterruptCleared(TrackedOffset record) {
    clearedInterrupts.add(record);
  }

  @Override
  public void pauseState(PauseReason reason, boolean active) {
    pauseStates.add(reason + ":" + active);
  }

  @Override
  public void commitFailed(Collection<TopicPartition> partitions, Exception cause) {
    commitFailures.add(partitions);
  }

  @Override
  public void shutdownGraceExpired(int stillInFlight) {
    expiredShutdownGraces.add(stillInFlight);
  }

  @Override
  public void consumerCloseFailed(Exception cause) {
    closeFailures.add(cause);
  }

  @Override
  public void pollerDied(Throwable cause) {
    pollerDeaths.add(cause);
  }
}
