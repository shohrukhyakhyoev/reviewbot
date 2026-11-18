package dev.reviewbot.gateway.webhook;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;

import dev.reviewbot.gateway.dedup.DeliveryDedup;
import dev.reviewbot.gateway.dedup.DeliveryDedup.CheckResult;
import dev.reviewbot.gateway.kafka.ReviewRecordFactory;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;

/** Spec §6.1 step 4: dedup-check, produce, then mark, in the C-1 order. HTTP is the caller's. */
public final class WebhookEnqueuer {

  public enum Result {
    ENQUEUED,
    DUPLICATE,
    UNAVAILABLE
  }

  private final DeliveryDedup dedup;
  private final Producer<String, byte[]> producer;
  private final ReviewRecordFactory records;
  private final Duration produceDeadline;
  private final WebhookMetrics metrics;
  private final LongSupplier nanoTime;

  public WebhookEnqueuer(
      DeliveryDedup dedup,
      Producer<String, byte[]> producer,
      ReviewRecordFactory records,
      Duration produceDeadline) {
    this(dedup, producer, records, produceDeadline, new LoggingWebhookMetrics(), System::nanoTime);
  }

  WebhookEnqueuer(
      DeliveryDedup dedup,
      Producer<String, byte[]> producer,
      ReviewRecordFactory records,
      Duration produceDeadline,
      WebhookMetrics metrics,
      LongSupplier nanoTime) {
    this.dedup = dedup;
    this.producer = producer;
    this.records = records;
    this.produceDeadline = produceDeadline;
    this.metrics = metrics;
    this.nanoTime = nanoTime;
  }

  public Result enqueue(ReviewRequestedEnvelope envelope, Instant receivedAt) {
    String deliveryId = envelope.deliveryId();
    CheckResult markerLookup = dedup.check(deliveryId);
    if (markerLookup == CheckResult.HIT) {
      return Result.DUPLICATE;
    }
    if (!producedInTime(records.build(envelope, receivedAt))) {
      return Result.UNAVAILABLE;
    }
    // Only after a clean miss: when the check just failed open, Redis is likely down and a mark
    // would cost a second ~2 s timeout against GitHub's 10 s (decision C, E3-2 follow-up 1).
    if (markerLookup == CheckResult.MISS) {
      dedup.markEnqueued(deliveryId);
    }
    return Result.ENQUEUED;
  }

  /** True only once the broker acked within {@code produceDeadline}, counted from before send. */
  private boolean producedInTime(ProducerRecord<String, byte[]> record) {
    // One budget for send() and the ack wait: send() can block on metadata (max.block.ms) first.
    long deadlineNanos = nanoTime.getAsLong() + produceDeadline.toNanos();
    try {
      Future<RecordMetadata> ack = producer.send(record);
      ack.get(deadlineNanos - nanoTime.getAsLong(), TimeUnit.NANOSECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      metrics.produceFailed(e);
    } catch (ExecutionException e) {
      metrics.produceFailed(e.getCause());
    } catch (TimeoutException | KafkaException | IllegalStateException e) {
      // The last two come from send() itself: Kafka's InterruptException (it re-sets the flag)
      // while blocked on metadata, or a producer already closed at shutdown.
      metrics.produceFailed(e);
    }
    return false;
  }
}
