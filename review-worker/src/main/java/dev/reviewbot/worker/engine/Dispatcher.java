package dev.reviewbot.worker.engine;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.apache.kafka.common.TopicPartition;

import dev.reviewbot.shared.keys.PrKey;

/**
 * Routes each record to its PR's queue, starting a drainer for a PR that has none (§8.2). Pure
 * routing: never touches the semaphore or the tracker.
 */
final class Dispatcher {

  private final ConcurrentHashMap<PrKey, PrQueue> queues = new ConcurrentHashMap<>();
  private final Set<TopicPartition> closed = ConcurrentHashMap.newKeySet();
  private final Executor drainerThreads;
  private final DrainerFactory drainers;
  private final EngineMetrics metrics;
  private final Supplier<PrQueue> newQueue;

  Dispatcher(Executor drainerThreads, DrainerFactory drainers, EngineMetrics metrics) {
    this(drainerThreads, drainers, metrics, PrQueue::new);
  }

  // drainerThreads must start each drainer on another thread: execute runs inside compute, and a
  // drainer run in place would re-enter compute on the same key through tryRetire.
  Dispatcher(
      Executor drainerThreads,
      DrainerFactory drainers,
      EngineMetrics metrics,
      Supplier<PrQueue> newQueue) {
    this.drainerThreads = drainerThreads;
    this.drainers = drainers;
    this.metrics = SafeEngineMetrics.wrap(metrics);
    this.newQueue = newQueue;
  }

  void route(TrackedRecord tracked) {
    // Parsed before compute: a bad key must throw before any side effect inside the lambda.
    PrKey key = PrKey.parse(tracked.record().key());
    TopicPartition partition = tracked.trackedOffset().partition();
    if (closed.contains(partition)) {
      // Still enqueued: the record holds a permit, and dropping it would leak that permit (I4).
      metrics.routedToClosedPartition(partition);
    }
    queues.compute(
        key,
        (k, queue) -> {
          if (queue == null) {
            PrQueue fresh = newQueue.get();
            fresh.add(tracked);
            drainerThreads.execute(drainers.create(k, fresh, this::tryRetire));
            return fresh;
          }
          queue.add(tracked);
          return queue;
        });
  }

  void markClosed(Collection<TopicPartition> partitions) {
    closed.addAll(partitions);
  }

  void reopen(Collection<TopicPartition> partitions) {
    closed.removeAll(partitions);
  }

  /** Called only by the key's own drainer after an empty drain; true means it must exit (I10). */
  boolean tryRetire(PrKey key) {
    PrQueue remaining =
        queues.compute(key, (k, queue) -> (queue == null || queue.isEmpty()) ? null : queue);
    return remaining == null;
  }

  int activeKeys() {
    return queues.size();
  }
}
