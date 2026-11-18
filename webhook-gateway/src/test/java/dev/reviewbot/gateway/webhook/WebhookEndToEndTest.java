package dev.reviewbot.gateway.webhook;

import static dev.reviewbot.gateway.webhook.TestSignatures.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.envelope.EnvelopeSerde;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;
import dev.reviewbot.shared.headers.HeaderCodec;
import dev.reviewbot.shared.keys.RedisKeys;

/**
 * The whole gateway over real Kafka and Redis, through both filters. Outage tests run last and
 * pause a container (connection kept, no reply), which is what exercises the timeouts.
 */
@Testcontainers
@SpringBootTest(properties = "reviewbot.gateway.webhook-secret=" + WebhookEndToEndTest.SECRET)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebhookEndToEndTest {

  static final String SECRET = "e2e-secret";
  private static final byte[] OPENED = Fixtures.bytes("pull_request_opened");

  @Container
  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
          // Production provisions topics explicitly (E2-4); auto-create would hide a missing one.
          .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Autowired private MockMvc mvc;
  @Autowired private RedissonClient redisson;

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    registry.add("reviewbot.gateway.kafka-bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add(
        "reviewbot.gateway.redis-url",
        () -> "redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
  }

  @BeforeAll
  static void createTopic() throws Exception {
    try (Admin admin =
        Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(
                      Topics.REVIEW_REQUESTED, Topics.REVIEW_REQUESTED_PARTITIONS, (short) 1)))
          .all()
          .get();
    }
  }

  @Test
  @Order(1)
  void aSignedWebhookBecomesOneRecordWithKeyEnvelopeAndHeaders() throws Exception {
    // The first request after startup measured ~1.5 s: Redisson's lazy pool, the producer's first
    // metadata fetch and MVC init. The criterion is about a normal request, so warm up first.
    assertThat(deliver(newDeliveryId())).isEqualTo(200);
    String deliveryId = newDeliveryId();
    Instant before = Instant.now();

    Duration took = timed(() -> assertThat(deliver(deliveryId)).isEqualTo(200));

    assertThat(took).isLessThan(Duration.ofSeconds(1));
    List<ConsumerRecord<String, byte[]>> records = recordsFor(deliveryId);
    assertThat(records).hasSize(1);
    ConsumerRecord<String, byte[]> record = records.get(0);
    assertThat(record.key()).isEqualTo("12345:acme/app:17");

    ReviewRequestedEnvelope envelope = EnvelopeSerde.fromBytes(record.value());
    assertThat(envelope.deliveryId()).isEqualTo(deliveryId);
    assertThat(envelope.type()).isEqualTo(EventType.REVIEW_REQUESTED);
    assertThat(envelope.installationId()).isEqualTo(12345L);
    assertThat(envelope.repoFullName()).isEqualTo("acme/app");
    assertThat(envelope.prNumber()).isEqualTo(17);
    assertThat(envelope.headSha()).isEqualTo("9f2c1b7e4d3a8c6b5e0f1a2d3c4b5a69788f0e1d");

    Instant receivedAt =
        HeaderCodec.FIRST_RECEIVED_AT.decode(header(record, HeaderCodec.FIRST_RECEIVED_AT.name()));
    assertThat(receivedAt).isBetween(before.minusMillis(1), Instant.now());
    assertThat(HeaderCodec.TRACE_ID.decode(header(record, HeaderCodec.TRACE_ID.name())))
        .matches("[0-9a-f]{32}");
    assertThat(markerExists(deliveryId)).isTrue();
  }

  @Test
  @Order(2)
  void aRedeliveredIdIsAcknowledgedWithoutASecondRecord() throws Exception {
    String deliveryId = newDeliveryId();

    assertThat(deliver(deliveryId)).isEqualTo(200);
    assertThat(deliver(deliveryId)).isEqualTo(200);

    assertThat(recordsFor(deliveryId)).hasSize(1);
  }

  // §6.1 4d + C-1: 503 within the 5 s budget and no marker, so the redelivery still has a path.
  // The 503'd record may still land after the broker returns: the accepted, absorbed duplicate.
  @Test
  @Order(3)
  void kafkaUnreachableIs503InAboutFiveSecondsAndTheSameIdSucceedsLater() throws Exception {
    String deliveryId = newDeliveryId();

    pause(KAFKA);
    Duration took;
    try {
      took = timed(() -> assertThat(deliver(deliveryId)).isEqualTo(503));
      assertThat(markerExists(deliveryId)).isFalse();
    } finally {
      unpause(KAFKA);
    }

    assertThat(took).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(8));
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(deliver(deliveryId)).isEqualTo(200));
    assertThat(markerExists(deliveryId)).isTrue();
    assertThat(recordsFor(deliveryId)).isNotEmpty();
  }

  // Fail-open (§6.1 4a) plus decision C: one Redis timeout (about 2 s), not two, and no marker.
  @Test
  @Order(4)
  void redisUnreachableStillProducesWithOneTimeoutAndNoMarker() throws Exception {
    String deliveryId = newDeliveryId();

    pause(REDIS);
    Duration took;
    try {
      took = timed(() -> assertThat(deliver(deliveryId)).isEqualTo(200));
    } finally {
      unpause(REDIS);
    }

    assertThat(took).isLessThan(Duration.ofMillis(3_500));
    assertThat(recordsFor(deliveryId)).hasSize(1);
    await()
        .atMost(Duration.ofSeconds(10))
        .ignoreExceptions()
        .untilAsserted(() -> assertThat(markerExists(deliveryId)).isFalse());
  }

  private int deliver(String deliveryId) throws Exception {
    return mvc.perform(
            post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", deliveryId)
                .header("X-Hub-Signature-256", sign(SECRET, OPENED))
                .content(OPENED))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private boolean markerExists(String deliveryId) {
    return redisson.getBucket(RedisKeys.delivery(deliveryId), StringCodec.INSTANCE).isExists();
  }

  /** Reads the whole topic up to its current end, then keeps this delivery's records. */
  private static List<ConsumerRecord<String, byte[]>> recordsFor(String deliveryId) {
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    ByteArrayDeserializer.class.getName(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(Topics.REVIEW_REQUESTED).stream()
              .map(info -> new TopicPartition(info.topic(), info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

      List<ConsumerRecord<String, byte[]>> all = new ArrayList<>();
      long giveUpAt = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
        assertThat(System.nanoTime()).as("topic read timed out").isLessThan(giveUpAt);
        consumer.poll(Duration.ofMillis(200)).forEach(all::add);
      }
      return all.stream()
          .filter(r -> EnvelopeSerde.fromBytes(r.value()).deliveryId().equals(deliveryId))
          .toList();
    }
  }

  private static byte[] header(ConsumerRecord<String, byte[]> record, String name) {
    return record.headers().lastHeader(name).value();
  }

  private static String newDeliveryId() {
    return UUID.randomUUID().toString();
  }

  private static void pause(GenericContainer<?> container) {
    container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
  }

  private static void unpause(GenericContainer<?> container) {
    container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
  }

  private static Duration timed(ThrowingRunnable action) throws Exception {
    long start = System.nanoTime();
    action.run();
    return Duration.ofNanos(System.nanoTime() - start);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
