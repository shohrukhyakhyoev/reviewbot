package dev.reviewbot.shared.dlq;

import java.util.Objects;

/** Where the failed record was read from. */
public record Origin(String topic, int partition, long offset) {

  public Origin {
    Objects.requireNonNull(topic, "topic");
    if (partition < 0 || offset < 0) {
      throw new IllegalArgumentException("partition and offset must be >= 0");
    }
  }
}
