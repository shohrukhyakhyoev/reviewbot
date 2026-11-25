package dev.reviewbot.worker.engine;

import static java.util.stream.Collectors.toMap;

import java.util.Map;

import org.apache.kafka.common.TopicPartition;

final class Committables {

  private Committables() {}

  static Map<TopicPartition, Long> offsetsOf(Map<TopicPartition, TrackedOffset> committable) {
    return committable.entrySet().stream()
        .collect(toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
  }
}
