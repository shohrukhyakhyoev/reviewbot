package dev.reviewbot.worker.engine;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stand-in until E7 adds a metrics registry: one log line per event, no record content. */
final class LoggingEngineMetrics implements EngineMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingEngineMetrics.class);
  private static final int FRAMES = 5;
  private static final int MAX_CAUSES = 5;

  @Override
  public void routedToClosedPartition(TopicPartition partition) {
    LOG.error("record routed for closed partition {} (poller filter broken)", partition);
  }

  @Override
  public void outcomeHandlerFailed(TrackedOffset record, Throwable cause) {
    logFailure("outcome handler failed, record completed anyway", record, cause);
  }

  @Override
  public void markCompleteFailed(TrackedOffset record, Throwable cause) {
    logFailure("markComplete failed, not retried", record, cause);
  }

  @Override
  public void strayInterruptCleared(TrackedOffset record) {
    LOG.warn("interrupt cleared before the outcome handler, record still completed record={}", record);
  }

  @Override
  public void pauseState(PauseReason reason, boolean active) {
    LOG.info("pause_state reason={} active={}", reason.name().toLowerCase(Locale.ROOT), active);
  }

  @Override
  public void commitFailed(Collection<TopicPartition> partitions, Exception cause) {
    LOG.warn("commit failed partitions={} cause={}", partitions, cause.getClass().getName());
  }

  @Override
  public void shutdownGraceExpired(int stillInFlight) {
    LOG.warn("shutdown grace expired, still in flight={}", stillInFlight);
  }

  @Override
  public void consumerCloseFailed(Exception cause) {
    LOG.warn(
        "consumer close failed, partitions move after the session timeout cause={}",
        cause.getClass().getName());
  }

  @Override
  public void pollerDied(Throwable cause) {
    LOG.error("poller died, worker stops consuming causes={}", causeChain(cause));
  }

  private static String causeChain(Throwable cause) {
    return Stream.iterate(cause, Objects::nonNull, Throwable::getCause)
        .limit(MAX_CAUSES)
        .map(link -> link.getClass().getName() + " at " + throwSite(link))
        .collect(Collectors.joining(" | caused by "));
  }

  // Class name and frames, never the message: a message can carry diff or payload text, a code
  // location cannot.
  private static void logFailure(String what, TrackedOffset record, Throwable cause) {
    LOG.error(
        "{} record={} cause={} at={}", what, record, cause.getClass().getName(), throwSite(cause));
  }

  private static String throwSite(Throwable cause) {
    return Arrays.stream(cause.getStackTrace())
        .limit(FRAMES)
        .map(StackTraceElement::toString)
        .collect(Collectors.joining(" < "));
  }
}
