package dev.reviewbot.worker.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.worker.engine.CoalescePlan.Job;

class StubsTest {

  // The value stands in for envelope content, which must never reach the log.
  private final ConsumerRecord<String, byte[]> record =
      new ConsumerRecord<>(
          Topics.REVIEW_REQUESTED,
          2,
          41,
          "12345:acme/app:17",
          "secret diff".getBytes(StandardCharsets.UTF_8));

  @Test
  void theStubExecutorReportsSuccessAndLogsCoordinatesOnly() {
    var logged = capture(LoggingJobExecutor.class);

    Outcome outcome = new LoggingJobExecutor().execute(new Job(record, null));

    assertThat(outcome).isEqualTo(new Outcome.Success());
    assertThat(logged.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .isEqualTo("stub review partition=2 offset=41 key=12345:acme/app:17");
  }

  @Test
  void theStubHandlerLogsTheOutcomeTypeOnly() {
    var logged = capture(LoggingOutcomeHandler.class);

    new LoggingOutcomeHandler().handle(record, new Outcome.Success());

    assertThat(logged.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .isEqualTo("stub outcome partition=2 offset=41 outcome=Success");
  }

  private static ListAppender<ILoggingEvent> capture(Class<?> source) {
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(source)).addAppender(appender);
    return appender;
  }
}
