package dev.reviewbot.gateway.kafka;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.envelope.EnvelopeSerde;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;
import dev.reviewbot.shared.headers.HeaderCodec;
import dev.reviewbot.shared.keys.PrKey;

/** One envelope as a {@code pr.review.requested} record: §3.1 key and value, §3.2 headers. */
public final class ReviewRecordFactory {

  private final Supplier<String> traceIds;

  public ReviewRecordFactory() {
    this(ReviewRecordFactory::newTraceId);
  }

  ReviewRecordFactory(Supplier<String> traceIds) {
    this.traceIds = traceIds;
  }

  public ProducerRecord<String, byte[]> build(ReviewRequestedEnvelope envelope, Instant receivedAt) {
    String key =
        new PrKey(envelope.installationId(), envelope.repoFullName(), envelope.prNumber()).render();
    return new ProducerRecord<>(
        Topics.REVIEW_REQUESTED,
        null,
        key,
        EnvelopeSerde.toBytes(envelope),
        List.of(
            new RecordHeader(
                HeaderCodec.FIRST_RECEIVED_AT.name(), HeaderCodec.FIRST_RECEIVED_AT.encode(receivedAt)),
            new RecordHeader(
                HeaderCodec.TRACE_ID.name(), HeaderCodec.TRACE_ID.encode(traceIds.get()))));
  }

  // No OpenTelemetry in v1's gateway yet; this has the W3C trace-id shape (32 lowercase hex) so a
  // tracer added later can adopt it.
  private static String newTraceId() {
    UUID random = UUID.randomUUID();
    HexFormat hex = HexFormat.of();
    return hex.toHexDigits(random.getMostSignificantBits())
        + hex.toHexDigits(random.getLeastSignificantBits());
  }
}
