package dev.reviewbot.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.reviewbot.gateway.dedup.DeliveryDedup;
import dev.reviewbot.gateway.dedup.DeliveryDedup.CheckResult;
import dev.reviewbot.gateway.kafka.ReviewRecordFactory;
import dev.reviewbot.gateway.webhook.WebhookDecision.IgnoreReason;
import dev.reviewbot.gateway.webhook.WebhookEnqueuer.Result;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;

class WebhookEnqueuerTest {

  private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
  private static final Instant RECEIVED_AT = Instant.parse("2026-11-18T10:15:30Z");
  private static final Duration DEADLINE = Duration.ofMillis(200);
  private static final ReviewRequestedEnvelope ENVELOPE =
      new ReviewRequestedEnvelope(
          ReviewRequestedEnvelope.SCHEMA_VERSION,
          "6f1c2a8e-0d3b-4e7a-9c5f-1b2d3e4f5a6b",
          DELIVERY_ID,
          EventType.REVIEW_REQUESTED,
          Instant.parse("2026-11-18T10:15:00Z"),
          42L,
          "acme/app",
          7,
          "0123456789abcdef0123456789abcdef01234567",
          false,
          false);

  private final DeliveryDedup dedup = mock(DeliveryDedup.class);

  @SuppressWarnings("unchecked")
  private final Producer<String, byte[]> producer = mock(Producer.class);

  private final RecordingMetrics metrics = new RecordingMetrics();
  private final AtomicLong fakeNanos = new AtomicLong();

  private final WebhookEnqueuer enqueuer =
      new WebhookEnqueuer(
          dedup, producer, new ReviewRecordFactory(), DEADLINE, metrics, System::nanoTime);

  @BeforeEach
  void redisAnswersAMissByDefault() {
    when(dedup.check(DELIVERY_ID)).thenReturn(CheckResult.MISS);
  }

  @AfterEach
  void clearInterruptFlag() {
    Thread.interrupted();
  }

  @Test
  void anAlreadyEnqueuedDeliveryIsNotProducedAgain() {
    when(dedup.check(DELIVERY_ID)).thenReturn(CheckResult.HIT);

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.DUPLICATE);
    verify(producer, never()).send(any());
    verify(dedup, never()).markEnqueued(any());
  }

  @Test
  void anAckedProduceIsMarkedThenEnqueued() {
    ackWith(CompletableFuture.completedFuture(metadata()));

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.ENQUEUED);
    verify(producer).send(any());
    verify(dedup).markEnqueued(DELIVERY_ID);
  }

  // Decision C: Redis just failed to answer, so a mark would most likely burn another 2 s timeout
  // for nothing. Without the marker a redelivery is produced again; worker dedup absorbs it.
  @Test
  void aFailedOpenCheckStillProducesButSkipsTheMarker() {
    when(dedup.check(DELIVERY_ID)).thenReturn(CheckResult.FAILED_OPEN);
    ackWith(CompletableFuture.completedFuture(metadata()));

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.ENQUEUED);
    verify(producer).send(any());
    verify(dedup, never()).markEnqueued(any());
  }

  // How a broker refusal (min.insync.replicas) or Kafka's own delivery timeout reaches the caller.
  @Test
  void aFailedProduceIsUnavailableAndLeavesNoMarker() {
    ackWith(CompletableFuture.failedFuture(new NotEnoughReplicasException("isr below 2")));

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.UNAVAILABLE);
    verify(dedup, never()).markEnqueued(any());
    assertThat(metrics.causes).containsExactly(NotEnoughReplicasException.class);
  }

  @Test
  void kafkasOwnTimeoutIsUnavailableToo() {
    ackWith(
        CompletableFuture.failedFuture(
            new org.apache.kafka.common.errors.TimeoutException("metadata not available")));

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.UNAVAILABLE);
    verify(dedup, never()).markEnqueued(any());
  }

  @Test
  void noAckWithinTheDeadlineIsUnavailableAndBounded() {
    ackWith(new CompletableFuture<>());

    long start = System.nanoTime();
    Result result = enqueuer.enqueue(ENVELOPE, RECEIVED_AT);
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(result).isEqualTo(Result.UNAVAILABLE);
    assertThat(took).isGreaterThanOrEqualTo(DEADLINE).isLessThan(DEADLINE.multipliedBy(5));
    verify(dedup, never()).markEnqueued(any());
    assertThat(metrics.causes).containsExactly(TimeoutException.class);
  }

  // send() blocking on metadata (Kafka down) spends the same budget, so the ack wait gets only
  // the rest. The fake clock makes send "take" 800 ms of a 1 s budget, with no real sleeping.
  @Test
  void timeBlockedInSendCountsAgainstTheSameDeadline() throws Exception {
    Future<RecordMetadata> ack = neverAcked();
    sendTakes(Duration.ofMillis(800), ack);

    assertThat(withFakeClock(Duration.ofSeconds(1)).enqueue(ENVELOPE, RECEIVED_AT))
        .isEqualTo(Result.UNAVAILABLE);
    verify(ack).get(Duration.ofMillis(200).toNanos(), TimeUnit.NANOSECONDS);
    verify(dedup, never()).markEnqueued(any());
  }

  // A zero or negative wait makes Future.get time out at once: no fresh budget after send.
  @Test
  void aSendThatUsedTheWholeBudgetLeavesNothingToWaitFor() throws Exception {
    Future<RecordMetadata> ack = neverAcked();
    sendTakes(Duration.ofMillis(1_100), ack);

    assertThat(withFakeClock(Duration.ofSeconds(1)).enqueue(ENVELOPE, RECEIVED_AT))
        .isEqualTo(Result.UNAVAILABLE);
    verify(ack).get(Duration.ofMillis(-100).toNanos(), TimeUnit.NANOSECONDS);
  }

  // send() can throw instead of returning a future, e.g. a producer closed during shutdown.
  // That is still a produce failure: 503 like the others, not a 500.
  @Test
  void aSendThatThrowsIsUnavailableAndLeavesNoMarker() {
    when(producer.send(any())).thenThrow(new IllegalStateException("producer closed"));

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.UNAVAILABLE);
    verify(dedup, never()).markEnqueued(any());
    assertThat(metrics.causes).containsExactly(IllegalStateException.class);
  }

  // Kafka's own InterruptException comes from send() while it blocks on metadata; it re-sets the
  // flag itself, so this pins only that we answer as for an interrupt in the ack wait.
  @Test
  void anInterruptDuringSendIsUnavailableAndKeepsTheInterruptFlag() {
    when(producer.send(any()))
        .thenAnswer(
            call -> {
              throw new InterruptException(new InterruptedException());
            });

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.UNAVAILABLE);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    verify(dedup, never()).markEnqueued(any());
  }

  // C-1: the late ack is the accepted duplicate path, never a marker.
  @Test
  void anAckArrivingAfterTheDeadlineNeverSetsTheMarker() {
    CompletableFuture<RecordMetadata> late = new CompletableFuture<>();
    ackWith(late);

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.UNAVAILABLE);
    late.complete(metadata());

    verify(dedup, never()).markEnqueued(any());
  }

  @Test
  void anInterruptWhileWaitingIsUnavailableAndKeepsTheInterruptFlag() {
    ackWith(new CompletableFuture<>());
    Thread.currentThread().interrupt();

    assertThat(enqueuer.enqueue(ENVELOPE, RECEIVED_AT)).isEqualTo(Result.UNAVAILABLE);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    verify(dedup, never()).markEnqueued(any());
  }

  @SuppressWarnings("unchecked")
  private void ackWith(CompletableFuture<RecordMetadata> ack) {
    when(producer.send(any(ProducerRecord.class))).thenReturn(ack);
  }

  private static RecordMetadata metadata() {
    return new RecordMetadata(new TopicPartition("pr.review.requested", 0), 0L, 0, 0L, 0, 0);
  }

  private WebhookEnqueuer withFakeClock(Duration deadline) {
    return new WebhookEnqueuer(
        dedup, producer, new ReviewRecordFactory(), deadline, metrics, fakeNanos::get);
  }

  private void sendTakes(Duration blocked, Future<RecordMetadata> ack) {
    when(producer.send(any()))
        .thenAnswer(
            call -> {
              fakeNanos.addAndGet(blocked.toNanos());
              return ack;
            });
  }

  @SuppressWarnings("unchecked")
  private static Future<RecordMetadata> neverAcked() throws Exception {
    Future<RecordMetadata> ack = mock(Future.class);
    when(ack.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException());
    return ack;
  }

  private static final class RecordingMetrics implements WebhookMetrics {
    final List<Class<?>> causes = new ArrayList<>();

    @Override
    public void accepted() {}

    @Override
    public void ignored(IgnoreReason reason) {}

    @Override
    public void malformed(String deliveryId, String field) {}

    @Override
    public void answered(int status, Duration latency) {}

    @Override
    public void produceFailed(Throwable cause) {
      causes.add(cause.getClass());
    }
  }
}
