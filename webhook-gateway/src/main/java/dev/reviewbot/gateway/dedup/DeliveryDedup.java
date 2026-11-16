package dev.reviewbot.gateway.dedup;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;

import dev.reviewbot.shared.keys.RedisKeys;

/**
 * The {@code delivery:{guid}} flag, meaning "durably enqueued" (§6.1 4a/4c). Redis is only a cache
 * here, so neither half may fail the webhook. E3-3 owns the order around the produce.
 */
public final class DeliveryDedup {

  private static final String FLAG = "1";

  private final RedissonClient redisson;
  private final DedupMetrics metrics;

  public DeliveryDedup(RedissonClient redisson) {
    this(redisson, new LoggingDedupMetrics());
  }

  DeliveryDedup(RedissonClient redisson, DedupMetrics metrics) {
    this.redisson = redisson;
    this.metrics = metrics;
  }

  /**
   * True when this delivery was already enqueued; a Redis failure counts as "not enqueued". Throws
   * {@code IllegalArgumentException} for a blank id or one containing ':'.
   */
  public boolean check(String deliveryId) {
    boolean exists;
    try {
      exists = flag(deliveryId).isExists();
    } catch (RedisException e) {
      metrics.checkFailedOpen(e);
      return false;
    }
    if (exists) {
      metrics.hit();
    }
    return exists;
  }

  /**
   * Best-effort {@code SET NX EX}: swallows Redis failures. Throws {@code IllegalArgumentException}
   * for a blank id or one containing ':'. Call it only after the produce was acked (C-1).
   */
  public void markEnqueued(String deliveryId) {
    try {
      flag(deliveryId).setIfAbsent(FLAG, RedisKeys.DELIVERY_TTL);
    } catch (RedisException e) {
      metrics.markFailed(e);
    }
  }

  private RBucket<String> flag(String deliveryId) {
    return redisson.getBucket(RedisKeys.delivery(deliveryId), StringCodec.INSTANCE);
  }
}
