package dev.reviewbot.gateway.kafka;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** The gateway producer's configuration: §4.2's values are fixed by the spec, not tunable. */
public final class ProducerSettings {

  private ProducerSettings() {}

  /**
   * {@code produceDeadline} caps {@code max.block.ms}: {@code send()} blocks the request thread
   * while topic metadata is unknown, 60 s by default, before the ack wait even starts.
   */
  public static Map<String, Object> forGateway(String bootstrapServers, Duration produceDeadline) {
    return Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
        ProducerConfig.ACKS_CONFIG, "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
        ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000,
        ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4",
        ProducerConfig.MAX_BLOCK_MS_CONFIG, produceDeadline.toMillis());
  }
}
