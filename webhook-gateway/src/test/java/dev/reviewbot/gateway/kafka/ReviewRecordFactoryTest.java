package dev.reviewbot.gateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.envelope.EnvelopeSerde;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;
import dev.reviewbot.shared.headers.HeaderCodec;

class ReviewRecordFactoryTest {

  private static final Instant RECEIVED_AT = Instant.parse("2026-11-18T10:15:30.123Z");
  private static final ReviewRequestedEnvelope ENVELOPE =
      new ReviewRequestedEnvelope(
          ReviewRequestedEnvelope.SCHEMA_VERSION,
          "6f1c2a8e-0d3b-4e7a-9c5f-1b2d3e4f5a6b",
          "72d3162e-cc78-11e3-81ab-4c9367dc0958",
          EventType.REVIEW_REQUESTED,
          Instant.parse("2026-11-18T10:15:00Z"),
          42L,
          "acme/app",
          7,
          "0123456789abcdef0123456789abcdef01234567",
          false,
          false);

  private final ReviewRecordFactory factory = new ReviewRecordFactory(() -> "trace-1");

  @Test
  void goesToTheReviewTopicKeyedByThePr() {
    ProducerRecord<String, byte[]> record = factory.build(ENVELOPE, RECEIVED_AT);

    assertThat(record.topic()).isEqualTo("pr.review.requested");
    assertThat(record.key()).isEqualTo("42:acme/app:7");
    assertThat(record.partition()).isNull();
  }

  @Test
  void theValueIsTheEnvelopeAsTheConsumerReadsIt() {
    ProducerRecord<String, byte[]> record = factory.build(ENVELOPE, RECEIVED_AT);

    assertThat(EnvelopeSerde.fromBytes(record.value())).isEqualTo(ENVELOPE);
  }

  @Test
  void carriesExactlyTheReceivedAtAndTraceIdHeaders() {
    ProducerRecord<String, byte[]> record = factory.build(ENVELOPE, RECEIVED_AT);

    assertThat(record.headers().toArray()).extracting(Header::key)
        .containsExactly("first_received_at", "trace_id");
    assertThat(HeaderCodec.FIRST_RECEIVED_AT.decode(lastValue(record, "first_received_at")))
        .isEqualTo(RECEIVED_AT);
    assertThat(HeaderCodec.TRACE_ID.decode(lastValue(record, "trace_id"))).isEqualTo("trace-1");
  }

  @Test
  void theDefaultTraceIdIsFreshW3cShapedHex() {
    ReviewRecordFactory real = new ReviewRecordFactory();

    String first = HeaderCodec.TRACE_ID.decode(lastValue(real.build(ENVELOPE, RECEIVED_AT), "trace_id"));
    String second = HeaderCodec.TRACE_ID.decode(lastValue(real.build(ENVELOPE, RECEIVED_AT), "trace_id"));

    assertThat(first).matches("[0-9a-f]{32}");
    assertThat(second).matches("[0-9a-f]{32}").isNotEqualTo(first);
  }

  private static byte[] lastValue(ProducerRecord<String, byte[]> record, String name) {
    return record.headers().lastHeader(name).value();
  }
}
