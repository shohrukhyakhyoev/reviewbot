package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

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

class LoggingEngineMetricsTest {

  // The message stands in for record content that must never reach the log.
  private final IllegalStateException cause = new IllegalStateException("diff of src/Secret.java");
  private final TrackedOffset record =
      new TrackedOffset(new TopicPartition(Topics.REVIEW_REQUESTED, 4), 2, 1234);

  private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingEngineMetrics.class);
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
  private final LoggingEngineMetrics metrics = new LoggingEngineMetrics();

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
  void aRouteToAClosedPartitionIsAnErrorNamingThePartition() {
    metrics.routedToClosedPartition(new TopicPartition(Topics.REVIEW_REQUESTED, 3));

    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getFormattedMessage()).contains(Topics.REVIEW_REQUESTED + "-3");
            });
  }

  @Test
  void anOutcomeHandlerFailureIsAnErrorNamingTheRecordAndThrowSite() {
    metrics.outcomeHandlerFailed(record, cause);

    assertErrorNamingTheRecordAndThrowSiteButNotTheMessage();
  }

  @Test
  void aMarkCompleteFailureIsAnErrorNamingTheRecordAndThrowSite() {
    metrics.markCompleteFailed(record, cause);

    assertErrorNamingTheRecordAndThrowSiteButNotTheMessage();
  }

  @Test
  void aClearedInterruptIsAWarningNamingTheRecord() {
    metrics.strayInterruptCleared(record);

    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("offset=1234");
            });
  }

  private void assertErrorNamingTheRecordAndThrowSiteButNotTheMessage() {
    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getFormattedMessage())
                  .contains("IllegalStateException")
                  .contains(Topics.REVIEW_REQUESTED + "-4")
                  .contains("offset=1234")
                  .contains(LoggingEngineMetricsTest.class.getName())
                  .doesNotContain("Secret.java");
              assertThat(event.getThrowableProxy()).isNull();
            });
  }
}
