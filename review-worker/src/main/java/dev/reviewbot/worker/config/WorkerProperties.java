package dev.reviewbot.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Deployment settings of the review worker ({@code reviewbot.worker.*}). */
@ConfigurationProperties("reviewbot.worker")
public record WorkerProperties(
    @DefaultValue("localhost:9092") String kafkaBootstrapServers,
    @DefaultValue("true") boolean engineAutostart) {

  public WorkerProperties {
    if (kafkaBootstrapServers == null || kafkaBootstrapServers.isBlank()) {
      throw new IllegalArgumentException(
          "reviewbot.worker.kafka-bootstrap-servers must be set (env KAFKA_BOOTSTRAP_SERVERS)");
    }
  }
}
