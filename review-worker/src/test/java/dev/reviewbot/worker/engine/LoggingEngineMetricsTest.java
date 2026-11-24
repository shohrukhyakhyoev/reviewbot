package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.KafkaException;
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

  @Test
  void pauseStateIsAnInfoLineNamingTheReasonAndValue() {
    metrics.pauseState(PauseReason.BACKPRESSURE, true);
    metrics.pauseState(PauseReason.BACKPRESSURE, false);

    assertThat(logged.list)
        .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
        .containsExactly(
            tuple(Level.INFO, "pause_state reason=backpressure active=true"),
            tuple(Level.INFO, "pause_state reason=backpressure active=false"));
  }

  @Test
  void aFailedCommitIsAWarningNamingThePartitionsButNotTheMessage() {
    var partition = new TopicPartition(Topics.REVIEW_REQUESTED, 2);

    metrics.commitFailed(List.of(partition), new CommitFailedException("broker said secret"));

    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains(partition.toString())
                  .contains(CommitFailedException.class.getName())
                  .doesNotContain("secret");
            });
  }

  @Test
  void aDeadPollerIsAnErrorNamingTheCauseAndThrowSiteButNotTheMessage() {
    metrics.pollerDied(cause);

    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getFormattedMessage())
                  .contains(IllegalStateException.class.getName())
                  .contains(LoggingEngineMetricsTest.class.getName())
                  .doesNotContain("Secret.java");
            });
  }

  @Test
  void aDeadPollerLogsEachCauseAndItsLocationButNoMessage() {
    metrics.pollerDied(new KafkaException("wrapper secret", cause));

    assertThat(logged.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains(KafkaException.class.getName())
        .contains(" | caused by " + IllegalStateException.class.getName() + " at ")
        .doesNotContain("wrapper secret")
        .doesNotContain("Secret.java");
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
