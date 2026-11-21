package dev.reviewbot.worker.engine;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps any {@link EngineMetrics} so a failing backend (E7's registry) can never cost a record its
 * completion or a PR its drainer (I4, I5). A contained failure is logged, so broken reporting is
 * visible.
 */
final class SafeEngineMetrics implements EngineMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(SafeEngineMetrics.class);

  private final EngineMetrics delegate;

  private SafeEngineMetrics(EngineMetrics delegate) {
    this.delegate = delegate;
  }

  static EngineMetrics wrap(EngineMetrics metrics) {
    return metrics instanceof SafeEngineMetrics ? metrics : new SafeEngineMetrics(metrics);
  }

  @Override
  public void routedToClosedPartition(TopicPartition partition) {
    contain(
        "routedToClosedPartition",
        partition.toString(),
        () -> delegate.routedToClosedPartition(partition));
  }

  @Override
  public void outcomeHandlerFailed(TrackedOffset record, Throwable cause) {
    contain(
        "outcomeHandlerFailed",
        lost(record, cause),
        () -> delegate.outcomeHandlerFailed(record, cause));
  }

  @Override
  public void markCompleteFailed(TrackedOffset record, Throwable cause) {
    contain(
        "markCompleteFailed", lost(record, cause), () -> delegate.markCompleteFailed(record, cause));
  }

  @Override
  public void strayInterruptCleared(TrackedOffset record) {
    contain("strayInterruptCleared", record.toString(), () -> delegate.strayInterruptCleared(record));
  }

  private static String lost(TrackedOffset record, Throwable cause) {
    return record + " cause=" + cause.getClass().getName();
  }

  // Coordinates and class names only, never a message: neither exception's text is ours to log.
  private static void contain(String event, String lost, Runnable report) {
    try {
      report.run();
    } catch (Throwable metricsFailure) {
      LOG.warn(
          "engine metrics failed, event not recorded event={} lost={} metricsCause={}",
          event,
          lost,
          metricsFailure.getClass().getName());
    }
  }
}
