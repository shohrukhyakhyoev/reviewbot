package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.failure.Outcome;

class BadKeyTasksTest {

  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);
  private static final int PERMITS = 10;

  private final Semaphore inFlight = new Semaphore(PERMITS);
  private final OffsetTracker tracker = new OffsetTracker(inFlight);
  private final RecordingEngineMetrics metrics = new RecordingEngineMetrics();
  private TrackedRecord badKey;

  @BeforeEach
  void dispatchOneBadKeyRecord() {
    tracker.register(P0, 0);
    inFlight.acquireUninterruptibly();
    var record =
        new ConsumerRecord<String, byte[]>(P0.topic(), 0, 0, "not-a-pr-key", new byte[0]);
    badKey = new TrackedRecord(record, tracker.track(P0, 0));
  }

  @Test
  void theTaskReportsPoisonToTheHandlerThenCompletesTheRecord() {
    List<Outcome> handled = new ArrayList<>();
    List<Integer> freePermitsDuringHandler = new ArrayList<>();
    var tasks =
        BadKeyTasks.completingAsPoison(
            (record, outcome) -> {
              handled.add(outcome);
              freePermitsDuringHandler.add(inFlight.availablePermits());
            },
            tracker,
            metrics);

    tasks.create(badKey).run();

    assertThat(handled)
        .singleElement()
        .isInstanceOfSatisfying(
            Outcome.Terminal.class,
            terminal ->
                assertThat(terminal.failureClass())
                    .isEqualTo(FailureClass.POISON_DESERIALIZATION));
    assertThat(freePermitsDuringHandler).containsExactly(PERMITS - 1);
    assertThat(tracker.watermark(P0)).hasValue(0);
    assertThat(inFlight.availablePermits()).isEqualTo(PERMITS);
  }

  @Test
  void aThrowingHandlerStillCompletesTheRecord() {
    var handlerFailure = new IllegalStateException("handler bug");
    var tasks =
        BadKeyTasks.completingAsPoison(
            (record, outcome) -> {
              throw handlerFailure;
            },
            tracker,
            metrics);

    tasks.create(badKey).run();

    assertThat(metrics.outcomeHandlerFailures).containsExactly(handlerFailure);
    assertThat(tracker.watermark(P0)).hasValue(0);
    assertThat(inFlight.availablePermits()).isEqualTo(PERMITS);
  }
}
