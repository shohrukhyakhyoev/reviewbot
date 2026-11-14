package dev.reviewbot.shared.dlq;

import java.util.Map;
import java.util.Objects;

/** A complete DLQ message, ready to produce: Kafka key, headers (name to encoded value) and JSON body. */
public record DlqRecord(String key, Map<String, byte[]> headers, byte[] body) {

  public DlqRecord {
    Objects.requireNonNull(key, "key");
    headers = Map.copyOf(headers);
    Objects.requireNonNull(body, "body");
  }
}
