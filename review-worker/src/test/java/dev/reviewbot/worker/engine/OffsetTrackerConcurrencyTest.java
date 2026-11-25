package dev.reviewbot.worker.engine;

import static dev.reviewbot.worker.engine.Committables.offsetsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.Topics;

class OffsetTrackerConcurrencyTest {

  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);
  private static final TopicPartition P1 = new TopicPartition(Topics.REVIEW_REQUESTED, 1);
  private static final int PER_PARTITION = 20_000;
  private static final int DRAINERS = 32;

  @Test
  void concurrentCompletionsWhilePollingReachTheFullPrefixAndConservePermits()
      throws InterruptedException, ExecutionException {
    var inFlight = new Semaphore(0);
    var tracker = new OffsetTracker(inFlight);
    tracker.register(P0, 0);
    tracker.register(P1, 0);

    var work = new ArrayList<Runnable>();
    for (long o = 0; o < PER_PARTITION; o++) {
      long offset = o;
      var onP0 = tracker.track(P0, offset);
      var onP1 = tracker.track(P1, offset);
      work.add(() -> tracker.markComplete(onP0));
      work.add(() -> tracker.markComplete(onP1));
    }
    Collections.shuffle(work, new Random(42));

    var start = new CountDownLatch(1);
    var drainersDone = new AtomicBoolean();
    Map<TopicPartition, Long> lastSeen = new ConcurrentHashMap<>();
    List<String> pollerViolations = Collections.synchronizedList(new ArrayList<>());
    var pollerFailure = new AtomicReference<Throwable>();
    var poller =
        Thread.ofPlatform()
            .uncaughtExceptionHandler((t, e) -> pollerFailure.set(e))
            .start(
                () -> {
                  while (!drainersDone.get()) {
                    var snapshot = tracker.committable();
                    snapshot.forEach(
                        (tp, mark) -> {
                          long wm = mark.offset();
                          Long previous = lastSeen.put(tp, wm);
                          if (previous != null && wm <= previous) {
                            pollerViolations.add(tp + ": " + previous + " then " + wm);
                          }
                        });
                    tracker.markCommitted(snapshot);
                  }
                });

    var drainers = new ArrayList<Future<?>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int slice = work.size() / DRAINERS;
      for (int d = 0; d < DRAINERS; d++) {
        var mine = work.subList(d * slice, d == DRAINERS - 1 ? work.size() : (d + 1) * slice);
        drainers.add(
            executor.submit(
                () -> {
                  start.await();
                  mine.forEach(Runnable::run);
                  return null;
                }));
      }
      start.countDown();
    }
    drainersDone.set(true);
    for (var drainer : drainers) {
      drainer.get();
    }
    poller.join(TimeUnit.SECONDS.toMillis(10));
    assertThat(poller.isAlive()).as("poller finished").isFalse();
    assertThat(pollerFailure.get()).as("poller failure").isNull();
    offsetsOf(tracker.committable()).forEach(lastSeen::put);

    assertThat(pollerViolations).isEmpty();
    assertThat(lastSeen)
        .containsOnly(entry(P0, (long) PER_PARTITION - 1), entry(P1, (long) PER_PARTITION - 1));
    assertThat(tracker.watermark(P0)).hasValue(PER_PARTITION - 1);
    assertThat(tracker.watermark(P1)).hasValue(PER_PARTITION - 1);
    assertThat(inFlight.availablePermits()).isEqualTo(2 * PER_PARTITION);
  }
}
