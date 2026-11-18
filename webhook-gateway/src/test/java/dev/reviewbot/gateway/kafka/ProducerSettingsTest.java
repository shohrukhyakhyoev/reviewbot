package dev.reviewbot.gateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;

class ProducerSettingsTest {

  // Exact match: a setting added without updating this golden (e.g. retries) fails here too.
  @Test
  void pinsEverySpec42ValueAndTheSendBlockCapAndNothingElse() {
    assertThat(ProducerSettings.forGateway("broker:9092", Duration.ofSeconds(5)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "bootstrap.servers", "broker:9092",
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer",
                "acks", "all",
                "enable.idempotence", true,
                "max.in.flight.requests.per.connection", 5,
                "delivery.timeout.ms", 120_000,
                "compression.type", "lz4",
                "max.block.ms", 5_000L));
  }

  // The constructor validates the combination (idempotence needs acks=all and in-flight <= 5)
  // without contacting a broker.
  @Test
  void aRealProducerAcceptsTheConfiguration() {
    assertThatCode(
            () -> {
              try (KafkaProducer<String, byte[]> producer =
                  new KafkaProducer<>(ProducerSettings.forGateway("localhost:1", Duration.ofSeconds(5)))) {
                producer.close(Duration.ZERO);
              }
            })
        .doesNotThrowAnyException();
  }
}
