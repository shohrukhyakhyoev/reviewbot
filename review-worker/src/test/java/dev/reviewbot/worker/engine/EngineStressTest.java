package dev.reviewbot.worker.engine;

import static dev.reviewbot.worker.engine.Committables.offsetsOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.shared.keys.PrKey;

class EngineStressTest {

  private static final int ROUTERS = 8;
  private static final int PER_ROUTER = 3_000;
  private static final int PRS = 40;
  private static final int CAP = 200;

  private final Semaphore permits = new Semaphore(CAP);
  private final OffsetTracker tracker = new OffsetTracker(permits);
  private final RecordingEngineMetrics metrics = new RecordingEngineMetrics();
  private final Set<String> completed = ConcurrentHashMap.newKeySet();
  private final AtomicInteger duplicateCompletions = new AtomicInteger();
  private final Map<String, AtomicInteger> runningPerPr = new ConcurrentHashMap<>();
  private final AtomicInteger overlaps = new AtomicInteger();

  private final OutcomeHandler handler =
      (record, outcome) -> {
        if (!completed.add(new TopicPartition(record.topic(), record.partition()) + "@" + record.offset())) {
          duplicateCompletions.incrementAndGet();
        }
      };

  private final JobExecutor overlapDetectingJobs =
      job -> {
        var running = runningPerPr.computeIfAbsent(job.record().key(), k -> new AtomicInteger());
        if (running.incrementAndGet() > 1) {
          overlaps.incrementAndGet();
        }
        try {
          Thread.sleep(ThreadLocalRandom.current().nextInt(3));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          running.decrementAndGet();
        }
        return new Outcome.Success();
      };

  // The envelope's own PR fields are irrelevant here: routing reads only the record key.
  private static ConsumerRecord<String, byte[]> record(int partition, long offset, Random random) {
    String key = new PrKey(12345, "acme/app", 1 + random.nextInt(PRS)).render();
    byte[] value =
        random.nextInt(20) == 0
            ? CoalescerTest.poison(offset).value()
            : CoalescerTest.valid(offset).value();
    return new ConsumerRecord<>(Topics.REVIEW_REQUESTED, partition, offset, key, value);
  }

  @Test
  void manyRoutersOverManyPrsCompleteEverythingOnceWithOneJobPerPrAtATime() throws Exception {
    try (var drainerThreads = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatcher =
          new Dispatcher(
              drainerThreads,
              Drainer.factory(overlapDetectingJobs, handler, tracker, metrics),
              BadKeyTasks.completingAsPoison(handler, tracker, metrics),
              metrics);
      List<TopicPartition> partitions = new ArrayList<>();
      for (int p = 0; p < ROUTERS; p++) {
        var tp = new TopicPartition(Topics.REVIEW_REQUESTED, p);
        tracker.register(tp, 0);
        partitions.add(tp);
      }

      List<Thread> routers = new ArrayList<>();
      for (int p = 0; p < ROUTERS; p++) {
        int partition = p;
        routers.add(
            Thread.ofPlatform()
                .start(
                    () -> {
                      var random = new Random(partition);
                      for (long offset = 0; offset < PER_ROUTER; offset++) {
                        permits.acquireUninterruptibly();
                        var record = record(partition, offset, random);
                        dispatcher.route(
                            new TrackedRecord(
                                record, tracker.track(partitions.get(partition), offset)));
                      }
                    }));
      }
      for (Thread router : routers) {
        router.join();
      }

      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (completed.size() < ROUTERS * PER_ROUTER || dispatcher.activeKeys() > 0) {
        assertThat(System.nanoTime())
            .as(
                "quiescence: %d of %d completed, %d PRs still queued",
                completed.size(), ROUTERS * PER_ROUTER, dispatcher.activeKeys())
            .isLessThan(deadline);
        Thread.sleep(5);
      }

      assertThat(completed).hasSize(ROUTERS * PER_ROUTER);
      assertThat(duplicateCompletions).hasValue(0);
      assertThat(overlaps).as("two jobs of one PR at once (I2)").hasValue(0);
      assertThat(permits.availablePermits()).isEqualTo(CAP);
      assertThat(dispatcher.activeKeys()).isZero();
      assertThat(metrics.outcomeHandlerFailures).isEmpty();
      assertThat(metrics.markCompleteFailures).isEmpty();
      for (TopicPartition tp : partitions) {
        assertThat(offsetsOf(tracker.committable())).containsEntry(tp, (long) PER_ROUTER - 1);
      }
    }
  }
}
