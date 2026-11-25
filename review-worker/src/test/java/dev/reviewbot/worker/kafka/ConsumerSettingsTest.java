package dev.reviewbot.worker.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.keys.PrKey;
import dev.reviewbot.shared.keys.RawRecordKey;

class ConsumerSettingsTest {

  private static final ReviewbotProperties.Consumer SECTION_12 =
      new ReviewbotProperties.Consumer(
          Duration.ofSeconds(45),
          Duration.ofSeconds(3),
          Duration.ofSeconds(300),
          Duration.ofMillis(200),
          100,
          200,
          100,
          Duration.ofSeconds(30),
          Duration.ofSeconds(3),
          Duration.ofSeconds(10));

  @Test
  void everySection43ValueIsPinned() {
    var settings = ConsumerSettings.forReviewWorker("broker:9092", SECTION_12);

    assertThat(settings)
        .containsOnly(
            entry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092"),
            entry(ConsumerConfig.GROUP_ID_CONFIG, "review-worker"),
            entry(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer"),
            entry("key.deserializer.encoding", "ISO-8859-1"),
            entry(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
            entry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            entry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor"),
            entry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000),
            entry(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000),
            entry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000),
            entry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100),
            entry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
  }

  @Test
  void everyPossibleKeyByteSurvivesDecoding() {
    byte[] everyByte = new byte[256];
    for (int value = 0; value < everyByte.length; value++) {
      everyByte[value] = (byte) value;
    }

    String decoded = decodeKey(everyByte);

    assertThat(RawRecordKey.toBytes(decoded)).isEqualTo(everyByte);
  }

  @Test
  void aValidPrKeyDecodesUnchanged() {
    String key = new PrKey(12345, "acme/app", 17).render();

    assertThat(decodeKey(key.getBytes(StandardCharsets.UTF_8))).isEqualTo(key);
  }

  private static String decodeKey(byte[] key) {
    try (var keys = new StringDeserializer()) {
      keys.configure(ConsumerSettings.forReviewWorker("broker:9092", SECTION_12), true);
      return keys.deserialize(Topics.REVIEW_REQUESTED, key);
    }
  }

  // Building a real client is the only check that Kafka's own config parser accepts every type.
  @Test
  void kafkaAcceptsTheSettings() {
    try (var consumer =
        new KafkaConsumer<String, byte[]>(
            ConsumerSettings.forReviewWorker("localhost:9092", SECTION_12))) {
      assertThat(consumer.assignment()).isEmpty();
    }
  }
}
