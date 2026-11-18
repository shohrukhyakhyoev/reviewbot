package dev.reviewbot.gateway.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.reviewbot.gateway.config.GatewayConfig;
import dev.reviewbot.gateway.config.GatewayProperties;
import dev.reviewbot.gateway.dedup.DeliveryDedup.CheckResult;
import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.shared.keys.RedisKeys;

@Testcontainers
class DeliveryDedupTest {

  /** Measured about 2.1 s per call against the 2 s timeout (§12); the rest is CI slack. */
  private static final Duration BUDGET = Duration.ofMillis(2500);

  @Container private static final GenericContainer<?> REDIS = newRedis();

  private final List<RedissonClient> clients = new ArrayList<>();
  private final RecordingMetrics metrics = new RecordingMetrics();

  @AfterEach
  void closeClients() {
    clients.forEach(RedissonClient::shutdown);
  }

  @Test
  void anUnknownDeliveryIsAMiss() {
    DeliveryDedup dedup = dedupOn(REDIS);

    assertThat(dedup.check("unknown-delivery")).isEqualTo(CheckResult.MISS);
    assertThat(metrics.hits).isZero();
    assertThat(metrics.checkFailedOpen).isZero();
  }

  @Test
  void aMarkedDeliveryIsAHit() {
    DeliveryDedup dedup = dedupOn(REDIS);

    assertThat(dedup.check("marked-delivery")).isEqualTo(CheckResult.MISS);
    assertThat(metrics.hits).isZero();
    assertThat(metrics.checkFailedOpen).isZero();

    dedup.markEnqueued("marked-delivery");

    assertThat(dedup.check("marked-delivery")).isEqualTo(CheckResult.HIT);
    assertThat(metrics.hits).isEqualTo(1);
  }

  @Test
  void aSecondMarkIsANoOp() {
    DeliveryDedup dedup = dedupOn(REDIS);
    RBucket<String> planted = bucket(REDIS, "already-marked");
    planted.set("planted");

    dedup.markEnqueued("already-marked");

    assertThat(planted.get()).isEqualTo("planted");
    assertThat(planted.remainTimeToLive()).isEqualTo(-1);
    assertThat(metrics.markFailed).isZero();
  }

  @Test
  void theMarkExpiresAfter24Hours() {
    DeliveryDedup dedup = dedupOn(REDIS);

    dedup.markEnqueued("expiring-delivery");

    long remainingMillis = bucket(REDIS, "expiring-delivery").remainTimeToLive();
    assertThat(RedisKeys.DELIVERY_TTL).isEqualTo(Duration.ofHours(24));
    assertThat(remainingMillis)
        .isLessThanOrEqualTo(RedisKeys.DELIVERY_TTL.toMillis())
        .isGreaterThan(RedisKeys.DELIVERY_TTL.minusMinutes(1).toMillis());
  }

  @Test
  void checkFailsOpenWhenNothingListens() throws IOException {
    DeliveryDedup dedup = new DeliveryDedup(clientFor(urlOfClosedPort()), metrics);

    assertThat(dedup.check("any-delivery")).isEqualTo(CheckResult.FAILED_OPEN);

    assertThat(metrics.checkFailedOpen).isEqualTo(1);
    assertThat(metrics.hits).isZero();
    assertThat(metrics.lastCause).isInstanceOf(RedisException.class);
  }

  @Test
  void checkFailsOpenWithinTheBudgetWhenRedisDoesNotAnswer() {
    try (GenericContainer<?> redis = startPausedRedis()) {
      DeliveryDedup dedup = dedupOn(redis);

      Duration elapsed = timed(() -> assertThat(dedup.check("any-delivery")).isEqualTo(CheckResult.FAILED_OPEN));

      assertThat(elapsed).isLessThan(BUDGET);
      assertThat(metrics.checkFailedOpen).isEqualTo(1);
      assertThat(metrics.lastCause).isInstanceOf(RedisException.class);
    }
  }

  @Test
  void markEnqueuedSwallowsWhenRedisDoesNotAnswer() {
    try (GenericContainer<?> redis = startPausedRedis()) {
      DeliveryDedup dedup = dedupOn(redis);

      Duration elapsed =
          timed(() -> assertThatCode(() -> dedup.markEnqueued("any-delivery")).doesNotThrowAnyException());

      assertThat(elapsed).isLessThan(BUDGET);
      assertThat(metrics.markFailed).isEqualTo(1);
      assertThat(metrics.lastCause).isInstanceOf(RedisException.class);
    }
  }

  @Test
  void anInvalidDeliveryIdIsNotMistakenForARedisOutage() {
    DeliveryDedup dedup = dedupOn(REDIS);

    assertThatThrownBy(() -> dedup.check(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> dedup.check("a:b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> dedup.markEnqueued("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> dedup.markEnqueued("a:b"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(metrics.checkFailedOpen).isZero();
    assertThat(metrics.markFailed).isZero();
  }

  private static GenericContainer<?> newRedis() {
    return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
  }

  /** A paused container still accepts the TCP connection but never replies: the timeout case. */
  private static GenericContainer<?> startPausedRedis() {
    GenericContainer<?> redis = newRedis();
    redis.start();
    redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
    return redis;
  }

  private static Duration timed(Runnable action) {
    long start = System.nanoTime();
    action.run();
    return Duration.ofNanos(System.nanoTime() - start);
  }

  private static String urlOfClosedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return "redis://localhost:" + socket.getLocalPort();
    }
  }

  /** The client comes from production wiring, so the 2 s budget under test is the real one. */
  private RedissonClient clientFor(String redisUrl) {
    GatewayProperties gateway =
        new GatewayProperties(
            "secret", DataSize.ofMegabytes(25), false, redisUrl, "localhost:9092");
    ReviewbotProperties reviewbot =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("reviewbot", ReviewbotProperties.class);
    RedissonClient client = new GatewayConfig().redissonClient(gateway, reviewbot);
    clients.add(client);
    return client;
  }

  private DeliveryDedup dedupOn(GenericContainer<?> redis) {
    return new DeliveryDedup(clientFor(urlOf(redis)), metrics);
  }

  private RBucket<String> bucket(GenericContainer<?> redis, String deliveryId) {
    return clientFor(urlOf(redis)).getBucket(RedisKeys.delivery(deliveryId), StringCodec.INSTANCE);
  }

  private static String urlOf(GenericContainer<?> redis) {
    return "redis://" + redis.getHost() + ":" + redis.getFirstMappedPort();
  }

  private static final class RecordingMetrics implements DedupMetrics {
    int hits;
    int checkFailedOpen;
    int markFailed;
    Exception lastCause;

    @Override
    public void hit() {
      hits++;
    }

    @Override
    public void checkFailedOpen(Exception cause) {
      checkFailedOpen++;
      lastCause = cause;
    }

    @Override
    public void markFailed(Exception cause) {
      markFailed++;
      lastCause = cause;
    }
  }
}
