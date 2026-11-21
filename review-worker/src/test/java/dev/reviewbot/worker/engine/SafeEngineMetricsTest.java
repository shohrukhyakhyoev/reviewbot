package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import dev.reviewbot.shared.config.Topics;

class SafeEngineMetricsTest {

  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);

  private final TrackedOffset record = new TrackedOffset(P0, 1, 7);
  private final IllegalStateException cause = new IllegalStateException("handler bug");
  private final Logger logger = (Logger) LoggerFactory.getLogger(SafeEngineMetrics.class);
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  @BeforeEach
  void capture() {
    logged.start();
    logger.addAppender(logged);
  }

  @AfterEach
  void release() {
    logger.detachAppender(logged);
  }

  @Test
  void aWorkingBackendReceivesEveryEventUnchanged() {
    var recording = new RecordingEngineMetrics();
    var metrics = SafeEngineMetrics.wrap(recording);

    metrics.routedToClosedPartition(P0);
    metrics.outcomeHandlerFailed(record, cause);
    metrics.markCompleteFailed(record, cause);
    metrics.strayInterruptCleared(record);

    assertThat(recording.clearedInterrupts).containsExactly(record);
    assertThat(recording.closedPartitionRoutes).containsExactly(P0);
    assertThat(recording.outcomeHandlerFailures).containsExactly(cause);
    assertThat(recording.markCompleteFailures).containsExactly(cause);
    assertThat(logged.list).isEmpty();
  }

  // The backend's message stands in for text the engine must not log.
  @Test
  void aThrowingBackendIsContainedAndLeavesAWarningPerEvent() {
    var metrics = SafeEngineMetrics.wrap(new ThrowingEngineMetrics());

    assertThatCode(
            () -> {
              metrics.routedToClosedPartition(P0);
              metrics.outcomeHandlerFailed(record, cause);
              metrics.markCompleteFailed(record, cause);
              metrics.strayInterruptCleared(record);
            })
        .doesNotThrowAnyException();

    assertThat(logged.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "engine metrics failed, event not recorded event=routedToClosedPartition"
                + " lost=" + P0 + " metricsCause=java.lang.IllegalStateException",
            "engine metrics failed, event not recorded event=outcomeHandlerFailed lost=" + record
                + " cause=java.lang.IllegalStateException metricsCause=java.lang.IllegalStateException",
            "engine metrics failed, event not recorded event=markCompleteFailed lost=" + record
                + " cause=java.lang.IllegalStateException metricsCause=java.lang.IllegalStateException",
            "engine metrics failed, event not recorded event=strayInterruptCleared lost=" + record
                + " metricsCause=java.lang.IllegalStateException");
    assertThat(logged.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains("secret-host"));
    assertThat(logged.list).allMatch(event -> event.getLevel() == Level.WARN);
  }

  @Test
  void wrappingTwiceKeepsOneLayer() {
    var once = SafeEngineMetrics.wrap(new RecordingEngineMetrics());

    assertThat(SafeEngineMetrics.wrap(once)).isSameAs(once);
  }
}
