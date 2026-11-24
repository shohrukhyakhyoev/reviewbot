package dev.reviewbot.worker.kafka;

import static java.util.Map.entry;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.shared.keys.RawRecordKey;

/** The review worker's consumer configuration (§4.3); the numbers come from E1-5's properties. */
public final class ConsumerSettings {

  public static final String GROUP_ID = "review-worker";

  private static final String KEY_ENCODING_CONFIG = "key.deserializer.encoding";

  private ConsumerSettings() {}

  public static Map<String, Object> forReviewWorker(
      String bootstrapServers, ReviewbotProperties.Consumer consumer) {
    return Map.ofEntries(
        entry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
        entry(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID),
        entry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()),
        entry(KEY_ENCODING_CONFIG, RawRecordKey.CHARSET.name()),
        entry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName()),
        entry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
        entry(
            ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            CooperativeStickyAssignor.class.getName()),
        entry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, intMillis(consumer.sessionTimeout())),
        entry(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, intMillis(consumer.heartbeatInterval())),
        entry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, intMillis(consumer.maxPollInterval())),
        entry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, consumer.maxPollRecords()),
        entry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
  }

  private static int intMillis(Duration duration) {
    return Math.toIntExact(duration.toMillis());
  }
}
