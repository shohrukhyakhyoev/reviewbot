package dev.reviewbot.worker.engine;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stand-in until E7 adds a metrics registry: one log line per event, no record content. */
final class LoggingEngineMetrics implements EngineMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingEngineMetrics.class);
  private static final int FRAMES = 5;

  @Override
  public void routedToClosedPartition(TopicPartition partition) {
    LOG.error("record routed for closed partition {} (poller filter broken)", partition);
  }

  @Override
  public void outcomeHandlerFailed(TrackedOffset record, Throwable cause) {
    LOG.error(
        "outcome handler failed, record completed anyway record={} cause={} at={}",
        record,
        cause.getClass().getName(),
        throwSite(cause));
  }

  @Override
  public void markCompleteFailed(TrackedOffset record, Throwable cause) {
    LOG.error(
        "markComplete failed, not retried record={} cause={} at={}",
        record,
        cause.getClass().getName(),
        throwSite(cause));
  }

  @Override
  public void strayInterruptCleared(TrackedOffset record) {
    LOG.warn("interrupt cleared before the outcome handler, record still completed record={}", record);
  }

  // Frames, never the message: a message can carry diff or payload text, a code location cannot.
  private static String throwSite(Throwable cause) {
    return Arrays.stream(cause.getStackTrace())
        .limit(FRAMES)
        .map(StackTraceElement::toString)
        .collect(Collectors.joining(" < "));
  }
}
