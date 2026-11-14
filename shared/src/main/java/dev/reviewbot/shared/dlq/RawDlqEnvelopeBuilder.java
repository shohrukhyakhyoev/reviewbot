package dev.reviewbot.shared.dlq;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.headers.HeaderCodec;

/** Builds the poison (RAW) §10.2 DLQ record for a message that never became an envelope. */
public final class RawDlqEnvelopeBuilder {

  public static final int SCHEMA_VERSION = 1;
  private static final String KIND = "RAW";
  private static final String KEY_PREFIX = "poison:";

  private final byte[] key;
  private final byte[] value;
  private final Map<String, byte[]> headers;
  private final int partition;
  private final long offset;

  private DlqException exception;
  private Instant dlqAt;

  private RawDlqEnvelopeBuilder(
      byte[] key, byte[] value, Map<String, byte[]> headers, int partition, long offset) {
    this.key = key;
    this.value = value;
    this.headers = headers;
    this.partition = partition;
    this.offset = offset;
  }

  // Plain parts, not a ConsumerRecord: shared has no kafka-clients. Key, value and headers are
  // captured now because the original ages out in 7 days (O10). Duplicate header names collapse.
  public static RawDlqEnvelopeBuilder forPoison(
      byte[] key, byte[] value, Map<String, byte[]> headers, int partition, long offset) {
    Objects.requireNonNull(headers, "headers");
    if (partition < 0 || offset < 0) {
      throw new IllegalArgumentException("partition and offset must be >= 0");
    }
    return new RawDlqEnvelopeBuilder(key, value, new LinkedHashMap<>(headers), partition, offset);
  }

  /** The DLQ key for a record with no PR key. */
  public static String sentinelKey(int partition, long offset) {
    return KEY_PREFIX + partition + ":" + offset;
  }

  public RawDlqEnvelopeBuilder exception(DlqException exception) {
    this.exception = exception;
    return this;
  }

  public RawDlqEnvelopeBuilder dlqAt(Instant dlqAt) {
    this.dlqAt = dlqAt;
    return this;
  }

  public DlqRecord build() {
    Objects.requireNonNull(exception, "exception");
    Objects.requireNonNull(dlqAt, "dlqAt");

    byte[] body =
        DlqJson.write(
            g -> {
              g.writeStringField("kind", KIND);
              g.writeNumberField("schema_version", SCHEMA_VERSION);
              DlqJson.nullableString(g, "raw_payload_b64", base64(value));
              DlqJson.nullableString(g, "original_key_b64", base64(key));
              g.writeObjectFieldStart("original_headers");
              for (Map.Entry<String, byte[]> header : headers.entrySet()) {
                DlqJson.nullableString(g, header.getKey(), base64(header.getValue()));
              }
              g.writeEndObject();
              g.writeObjectFieldStart("origin");
              g.writeNumberField("partition", partition);
              g.writeNumberField("offset", offset);
              g.writeEndObject();
              DlqJson.writeException(g, exception);
              g.writeStringField("dlq_at", dlqAt.toString());
            });

    // No installation_id header: a poison record has no identity.
    Map<String, byte[]> dlqHeaders = new LinkedHashMap<>();
    dlqHeaders.put(
        HeaderCodec.FAILURE_CLASS.name(),
        HeaderCodec.FAILURE_CLASS.encode(FailureClass.POISON_DESERIALIZATION.wire()));
    return new DlqRecord(sentinelKey(partition, offset), dlqHeaders, body);
  }

  private static String base64(byte[] bytes) {
    return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
  }
}
