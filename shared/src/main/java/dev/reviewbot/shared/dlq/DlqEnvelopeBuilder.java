package dev.reviewbot.shared.dlq;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dev.reviewbot.shared.envelope.EnvelopeSerde;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;
import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.failure.FailureKind;
import dev.reviewbot.shared.headers.HeaderCodec;
import dev.reviewbot.shared.keys.PrKey;

/** Builds the standard §10.2 DLQ record for a parsed event; poison goes to {@link RawDlqEnvelopeBuilder}. */
public final class DlqEnvelopeBuilder {

  private final ReviewRequestedEnvelope event;
  private final String originalEvent;

  private FailureClass failureClass;
  private Integer errorAttempts;
  private Integer deferAttempts;
  private DlqException exception;
  private Instant firstReceivedAt;
  private Instant dlqAt;
  private Origin origin;
  private String traceId;

  private DlqEnvelopeBuilder(ReviewRequestedEnvelope event, String originalEvent) {
    this.event = event;
    this.originalEvent = originalEvent;
  }

  /** Keeps the original bytes: re-serializing the parsed envelope would drop unknown fields (§3.3). */
  public static DlqEnvelopeBuilder forEvent(byte[] originalEvent) {
    ReviewRequestedEnvelope event = EnvelopeSerde.fromBytes(originalEvent);
    return new DlqEnvelopeBuilder(event, DlqJson.requireSplicableJson(originalEvent));
  }

  public DlqEnvelopeBuilder failureClass(FailureClass failureClass) {
    if (failureClass.kind() != FailureKind.TERMINAL) {
      throw new IllegalArgumentException(
          failureClass + " is not terminal; only terminal classes are dead-lettered");
    }
    if (failureClass.equals(FailureClass.POISON_DESERIALIZATION)) {
      throw new IllegalArgumentException("a parsed event is not poison; use RawDlqEnvelopeBuilder");
    }
    this.failureClass = failureClass;
    return this;
  }

  public DlqEnvelopeBuilder attempts(int errorAttempts, int deferAttempts) {
    if (errorAttempts < 0 || deferAttempts < 0) {
      throw new IllegalArgumentException("attempt counters must be >= 0");
    }
    this.errorAttempts = errorAttempts;
    this.deferAttempts = deferAttempts;
    return this;
  }

  public DlqEnvelopeBuilder exception(DlqException exception) {
    this.exception = exception;
    return this;
  }

  public DlqEnvelopeBuilder firstReceivedAt(Instant firstReceivedAt) {
    this.firstReceivedAt = firstReceivedAt;
    return this;
  }

  public DlqEnvelopeBuilder dlqAt(Instant dlqAt) {
    this.dlqAt = dlqAt;
    return this;
  }

  public DlqEnvelopeBuilder origin(Origin origin) {
    this.origin = origin;
    return this;
  }

  public DlqEnvelopeBuilder traceId(String traceId) {
    this.traceId = traceId;
    return this;
  }

  public DlqRecord build() {
    Objects.requireNonNull(failureClass, "failureClass");
    Objects.requireNonNull(errorAttempts, "attempts");
    Objects.requireNonNull(exception, "exception");
    Objects.requireNonNull(firstReceivedAt, "firstReceivedAt");
    Objects.requireNonNull(dlqAt, "dlqAt");
    Objects.requireNonNull(origin, "origin");

    PrKey pr = new PrKey(event.installationId(), event.repoFullName(), event.prNumber());

    byte[] body =
        DlqJson.write(
            g -> {
              g.writeObjectFieldStart("metadata");
              g.writeStringField("failure_class", failureClass.wire());
              g.writeNumberField("installation_id", pr.installationId());
              g.writeStringField("repo_full_name", pr.repoFullName());
              g.writeNumberField("pr_number", pr.prNumber());
              g.writeStringField("head_sha", event.headSha());
              g.writeNumberField("error_attempts", errorAttempts);
              g.writeNumberField("defer_attempts", deferAttempts);
              DlqJson.writeException(g, exception);
              g.writeStringField("first_received_at", firstReceivedAt.toString());
              g.writeStringField("dlq_at", dlqAt.toString());
              g.writeObjectFieldStart("origin");
              g.writeStringField("topic", origin.topic());
              g.writeNumberField("partition", origin.partition());
              g.writeNumberField("offset", origin.offset());
              g.writeEndObject();
              DlqJson.nullableString(g, "trace_id", traceId);
              g.writeEndObject();
              g.writeFieldName("original_event");
              g.writeRawValue(originalEvent);
            });

    Map<String, byte[]> headers = new LinkedHashMap<>();
    headers.put(
        HeaderCodec.FAILURE_CLASS.name(), HeaderCodec.FAILURE_CLASS.encode(failureClass.wire()));
    headers.put(
        HeaderCodec.INSTALLATION_ID.name(), HeaderCodec.INSTALLATION_ID.encode(pr.installationId()));
    return new DlqRecord(pr.render(), headers, body);
  }
}
