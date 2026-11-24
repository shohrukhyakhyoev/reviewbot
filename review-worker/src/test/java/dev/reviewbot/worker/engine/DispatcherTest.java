package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.keys.PrKey;

class DispatcherTest {

  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);
  private static final PrKey PR_7 = new PrKey(12345, "acme/app", 7);
  private static final PrKey PR_9 = new PrKey(12345, "acme/app", 9);

  private record Spawn(PrKey key, PrQueue queue) {}

  private final List<Spawn> spawns = new CopyOnWriteArrayList<>();
  private final List<Runnable> started = new CopyOnWriteArrayList<>();
  private final RecordingEngineMetrics metrics = new RecordingEngineMetrics();

  private final DrainerFactory recordingFactory =
      (key, queue, retirer) -> {
        spawns.add(new Spawn(key, queue));
        return () -> {};
      };

  private final List<TrackedRecord> badKeyRecords = new CopyOnWriteArrayList<>();
  private final BadKeyTasks badKeyTasks =
      tracked -> {
        badKeyRecords.add(tracked);
        return () -> {};
      };

  private Dispatcher dispatcher(Executor executor) {
    return new Dispatcher(executor, recordingFactory, badKeyTasks, metrics);
  }

  private Dispatcher dispatcher() {
    return dispatcher(started::add);
  }

  private static TrackedRecord tracked(PrKey key, long offset) {
    return tracked(key.render(), offset);
  }

  private static TrackedRecord tracked(String rawKey, long offset) {
    var record =
        new ConsumerRecord<String, byte[]>(Topics.REVIEW_REQUESTED, 0, offset, rawKey, new byte[0]);
    return new TrackedRecord(record, new TrackedOffset(P0, 1, offset));
  }

  private static List<Long> offsets(List<TrackedRecord> batch) {
    return batch.stream().map(tr -> tr.trackedOffset().offset()).toList();
  }

  @Test
  void firstRecordForAPrStartsOneDrainerOnAQueueHoldingIt() {
    var dispatcher = dispatcher();

    dispatcher.route(tracked(PR_7, 1));

    assertThat(spawns).hasSize(1);
    assertThat(spawns.getFirst().key()).isEqualTo(PR_7);
    assertThat(started).hasSize(1);
    assertThat(offsets(spawns.getFirst().queue().drainAll())).containsExactly(1L);
    assertThat(dispatcher.activeKeys()).isEqualTo(1);
  }

  @Test
  void laterRecordsForTheSamePrAppendInArrivalOrderWithoutANewDrainer() {
    var dispatcher = dispatcher();

    dispatcher.route(tracked(PR_7, 5));
    dispatcher.route(tracked(PR_7, 2));
    dispatcher.route(tracked(PR_7, 9));

    assertThat(spawns).hasSize(1);
    assertThat(offsets(spawns.getFirst().queue().drainAll())).containsExactly(5L, 2L, 9L);
  }

  @Test
  void differentPrsGetSeparateDrainersAndQueues() {
    var dispatcher = dispatcher();

    dispatcher.route(tracked(PR_7, 1));
    dispatcher.route(tracked(PR_9, 2));

    assertThat(spawns).extracting(Spawn::key).containsExactly(PR_7, PR_9);
    assertThat(spawns.get(0).queue()).isNotSameAs(spawns.get(1).queue());
    assertThat(dispatcher.activeKeys()).isEqualTo(2);
  }

  @Test
  void retiringAnEmptyQueueRemovesItAndTheNextRecordStartsAFreshDrainer() {
    var dispatcher = dispatcher();
    dispatcher.route(tracked(PR_7, 1));
    PrQueue first = spawns.getFirst().queue();
    first.drainAll();

    assertThat(dispatcher.tryRetire(PR_7)).isTrue();
    assertThat(dispatcher.activeKeys()).isZero();

    dispatcher.route(tracked(PR_7, 2));
    assertThat(spawns).hasSize(2);
    assertThat(spawns.get(1).queue()).isNotSameAs(first);
  }

  @Test
  void retiringAQueueThatGotARecordIsRefusedAndKeepsItRouted() {
    var dispatcher = dispatcher();
    dispatcher.route(tracked(PR_7, 1));
    PrQueue queue = spawns.getFirst().queue();
    queue.drainAll();
    dispatcher.route(tracked(PR_7, 2));

    assertThat(dispatcher.tryRetire(PR_7)).isFalse();

    assertThat(dispatcher.activeKeys()).isEqualTo(1);
    assertThat(offsets(queue.drainAll())).containsExactly(2L);
    assertThat(spawns).hasSize(1);
  }

  @Test
  void retiringAPrWithNoQueueTellsTheDrainerToExit() {
    var dispatcher = dispatcher();

    assertThat(dispatcher.tryRetire(PR_9)).isTrue();
    assertThat(dispatcher.activeKeys()).isZero();
  }

  @Test
  void aRecordForAClosedPartitionIsReportedButStillRouted() {
    var dispatcher = dispatcher();
    dispatcher.markClosed(List.of(P0));

    dispatcher.route(tracked(PR_7, 1));

    assertThat(metrics.closedPartitionRoutes).containsExactly(P0);
    assertThat(offsets(spawns.getFirst().queue().drainAll())).containsExactly(1L);
  }

  @Test
  void aThrowingMetricsBackendStillLetsAClosedPartitionRecordThrough() {
    var dispatcher = new Dispatcher(
            started::add, recordingFactory, badKeyTasks, new ThrowingEngineMetrics());
    dispatcher.markClosed(List.of(P0));

    dispatcher.route(tracked(PR_7, 1));

    assertThat(offsets(spawns.getFirst().queue().drainAll())).containsExactly(1L);
  }

  @Test
  void aReopenedPartitionRoutesWithoutReport() {
    var dispatcher = dispatcher();
    dispatcher.markClosed(List.of(P0));
    dispatcher.reopen(List.of(P0));

    dispatcher.route(tracked(PR_7, 1));

    assertThat(metrics.closedPartitionRoutes).isEmpty();
    assertThat(spawns).hasSize(1);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"not-a-pr-key", "1:acme/app:"})
  void aKeyThatNamesNoPrStartsItsPoisonTaskWithoutAQueueOrDrainer(String key) {
    var dispatcher = dispatcher();
    var bad = tracked(key, 1);

    dispatcher.route(bad);

    assertThat(badKeyRecords).containsExactly(bad);
    assertThat(started).hasSize(1);
    assertThat(spawns).isEmpty();
    assertThat(dispatcher.activeKeys()).isZero();
  }

  @Test
  void aRejectedPoisonTaskPropagatesSoTheRecordIsNotCompleted() {
    var dispatcher =
        dispatcher(
            task -> {
              throw new RejectedExecutionException("shut down");
            });

    assertThatThrownBy(() -> dispatcher.route(tracked("not-a-pr-key", 1)))
        .isInstanceOf(RejectedExecutionException.class);
  }

  @Test
  void aRejectedDrainerStartLeavesNoQueueBehind() {
    var dispatcher =
        dispatcher(
            task -> {
              throw new RejectedExecutionException("shut down");
            });

    assertThatThrownBy(() -> dispatcher.route(tracked(PR_7, 1)))
        .isInstanceOf(RejectedExecutionException.class);

    assertThat(dispatcher.activeKeys()).isZero();
  }

  @Test
  void aConcurrentRouteStormOnOnePrStartsExactlyOneDrainer() throws InterruptedException {
    int routers = 64;
    int perRouter = 500;
    var dispatcher = dispatcher();
    var go = new CountDownLatch(1);
    var threads = new ArrayList<Thread>();
    for (int r = 0; r < routers; r++) {
      long base = (long) r * perRouter;
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    awaitQuietly(go);
                    for (long i = 0; i < perRouter; i++) {
                      dispatcher.route(tracked(PR_7, base + i));
                    }
                  }));
    }
    go.countDown();
    for (Thread t : threads) {
      t.join();
    }

    assertThat(spawns).hasSize(1);
    assertThat(spawns.getFirst().queue().drainAll()).hasSize(routers * perRouter);
  }

  @Test
  void aRouteRacingRetirementLandsInALiveQueueEitherWay() throws InterruptedException {
    int retiredFirst = 0;
    int routedFirst = 0;
    var random = new Random(7);
    for (int round = 0; round < 5_000; round++) {
      List<Spawn> roundSpawns = new ArrayList<>();
      var dispatcher =
          new Dispatcher(
              task -> {},
              (key, queue, retirer) -> {
                roundSpawns.add(new Spawn(key, queue));
                return () -> {};
              },
              badKeyTasks,
              metrics);
      dispatcher.route(tracked(PR_7, 0));
      PrQueue original = roundSpawns.getFirst().queue();
      original.drainAll();

      var ready = new CountDownLatch(1);
      var go = new AtomicBoolean();
      int pollerSpins = random.nextInt(200);
      var poller =
          Thread.ofPlatform()
              .start(
                  () -> {
                    ready.countDown();
                    spinUntil(go, pollerSpins);
                    dispatcher.route(tracked(PR_7, 1));
                  });
      ready.await();
      go.set(true);
      spinUntil(go, random.nextInt(200));
      boolean retired = dispatcher.tryRetire(PR_7);
      poller.join();

      if (retired) {
        retiredFirst++;
        assertThat(roundSpawns).hasSize(2);
        assertThat(original.isEmpty()).isTrue();
        assertThat(offsets(roundSpawns.get(1).queue().drainAll())).containsExactly(1L);
      } else {
        routedFirst++;
        assertThat(roundSpawns).hasSize(1);
        assertThat(offsets(original.drainAll())).containsExactly(1L);
      }
      assertThat(dispatcher.activeKeys()).isEqualTo(1);
    }

    // The scheduler, not tryRetire, decides whether both orders occurred: abort, never fail, if not.
    assumeTrue(
        retiredFirst > 0 && routedFirst > 0,
        "only one interleaving ran (retired first " + retiredFirst + ", routed first " + routedFirst + ")");
  }

  // Pins I10 without luck: the latch holds tryRetire inside its emptiness check while route() fires.
  // Correct code holds the key's map bin there, so route() must wait and then start a fresh drainer.
  @Test
  void aRouteFiredWhileTryRetireChecksEmptinessEndsInALiveQueue() throws InterruptedException {
    var checking = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var armed = new AtomicBoolean();
    Supplier<PrQueue> pausingQueues =
        () ->
            new PrQueue() {
              @Override
              boolean isEmpty() {
                boolean empty = super.isEmpty();
                if (armed.compareAndSet(true, false)) {
                  checking.countDown();
                  awaitQuietly(proceed);
                }
                return empty;
              }
            };
    var dispatcher = new Dispatcher(started::add, recordingFactory, badKeyTasks, metrics, pausingQueues);
    dispatcher.route(tracked(PR_7, 0));
    PrQueue original = spawns.getFirst().queue();
    original.drainAll();
    armed.set(true);

    var retired = new AtomicBoolean();
    var drainer = Thread.ofPlatform().start(() -> retired.set(dispatcher.tryRetire(PR_7)));
    assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
    var poller = Thread.ofPlatform().start(() -> dispatcher.route(tracked(PR_7, 1)));
    awaitBlockedOrDone(poller);
    proceed.countDown();
    drainer.join();
    poller.join();

    assertThat(retired).isTrue();
    assertThat(original.isEmpty()).as("nothing stranded in the retired queue").isTrue();
    assertThat(spawns).hasSize(2);
    assertThat(offsets(spawns.get(1).queue().drainAll())).containsExactly(1L);
    assertThat(dispatcher.activeKeys()).isEqualTo(1);
  }

  private static void awaitBlockedOrDone(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.getState() != Thread.State.BLOCKED
        && thread.getState() != Thread.State.TERMINATED) {
      assertThat(System.nanoTime()).as("poller reached the map").isLessThan(deadline);
      Thread.sleep(1);
    }
  }

  @Test
  void drainersRunOnTheInjectedExecutor() throws InterruptedException {
    var ran = new CountDownLatch(1);
    try (var virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatcher =
          new Dispatcher(
              virtualThreads, (key, queue, retirer) -> ran::countDown, badKeyTasks, metrics);

      dispatcher.route(tracked(PR_7, 1));

      assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Busy-waits for the flag, then a few more spins, so either racer can be first by a hair. */
  private static void spinUntil(AtomicBoolean flag, int extraSpins) {
    while (!flag.get()) {
      Thread.onSpinWait();
    }
    for (int i = 0; i < extraSpins; i++) {
      Thread.onSpinWait();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
