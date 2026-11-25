package dev.reviewbot.worker.engine;

import static dev.reviewbot.worker.engine.Committables.offsetsOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.failure.FailureClassifier;
import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.shared.keys.PrKey;

class DrainerTest {

  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);
  private static final int CAP = 200;
  private static final Outcome SUCCESS = new Outcome.Success();

  record Completion(long offset, Outcome outcome) {}

  private final Semaphore permits = new Semaphore(CAP);
  private final OffsetTracker tracker = new OffsetTracker(permits);
  private final List<Completion> completions = new CopyOnWriteArrayList<>();
  private final List<Long> executed = new CopyOnWriteArrayList<>();
  private final AtomicInteger spawns = new AtomicInteger();
  private final RecordingEngineMetrics metrics = new RecordingEngineMetrics();

  private final OutcomeHandler recordingHandler =
      (record, outcome) -> completions.add(new Completion(record.offset(), outcome));

  // Every test's first offset is 1; a position of 0 would leave a gap the watermark never passes.
  @BeforeEach
  void registerPartition() {
    tracker.register(P0, 1);
  }

  private Dispatcher dispatcher(Executor threads, JobExecutor jobs) {
    return dispatcher(threads, jobs, recordingHandler);
  }

  private Dispatcher dispatcher(Executor threads, JobExecutor jobs, OutcomeHandler handler) {
    return dispatcher(threads, jobs, handler, metrics);
  }

  private Dispatcher dispatcher(
      Executor threads, JobExecutor jobs, OutcomeHandler handler, EngineMetrics engineMetrics) {
    DrainerFactory real = Drainer.factory(jobs, handler, tracker, engineMetrics);
    DrainerFactory counting =
        (key, queue, retirer) -> {
          spawns.incrementAndGet();
          return real.create(key, queue, retirer);
        };
    return new Dispatcher(threads, counting, tracked -> () -> {}, engineMetrics);
  }

  private JobExecutor succeeding() {
    return job -> {
      executed.add(job.record().offset());
      return SUCCESS;
    };
  }

  /** What the poller does per record: take a permit, stamp the offset, route. */
  private void dispatch(Dispatcher dispatcher, ConsumerRecord<String, byte[]> record) {
    assertThat(permits.tryAcquire()).isTrue();
    dispatcher.route(new TrackedRecord(record, tracker.track(P0, record.offset())));
  }

  private void awaitQuiescence(Dispatcher dispatcher, int expectedCompletions)
      throws InterruptedException {
    awaitDrained(dispatcher, expectedCompletions);
    assertThat(permits.availablePermits()).isEqualTo(CAP);
  }

  /** Every expected completion handled and every drainer retired; permits are the caller's call. */
  private void awaitDrained(Dispatcher dispatcher, int expectedCompletions)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (completions.size() < expectedCompletions || dispatcher.activeKeys() > 0) {
      assertThat(System.nanoTime())
          .as(
              "quiescence: %d of %d completed, %d PRs still queued",
              completions.size(), expectedCompletions, dispatcher.activeKeys())
          .isLessThan(deadline);
      Thread.sleep(1);
    }
    assertThat(completions).hasSize(expectedCompletions);
  }

  /** Holds drainer starts until {@link #release}, so a test can queue a whole batch first. */
  private static final class HeldThreads implements Executor {
    private final List<Runnable> held = new CopyOnWriteArrayList<>();

    @Override
    public void execute(Runnable task) {
      held.add(task);
    }

    void release() {
      held.forEach(Thread::startVirtualThread);
      held.clear();
    }
  }

  @Test
  void aSingleRecordIsExecutedCompletedAndTheDrainerRetires() throws InterruptedException {
    var dispatcher = dispatcher(Thread::startVirtualThread, succeeding());

    dispatch(dispatcher, CoalescerTest.valid(1));

    awaitQuiescence(dispatcher, 1);
    assertThat(executed).containsExactly(1L);
    assertThat(completions).containsExactly(new Completion(1, SUCCESS));
    assertThat(spawns).hasValue(1);
  }

  @Test
  void aBurstRunsOnlyTheNewestAndSupersedesTheRestInArrivalOrderFirst()
      throws InterruptedException {
    var threads = new HeldThreads();
    var dispatcher = dispatcher(threads, succeeding());
    for (long offset : new long[] {5, 2, 9, 3}) {
      dispatch(dispatcher, CoalescerTest.valid(offset));
    }

    threads.release();

    awaitQuiescence(dispatcher, 4);
    assertThat(executed).containsExactly(9L);
    assertThat(completions)
        .containsExactly(
            new Completion(5, CoalescePlan.SUPERSEDED_OUTCOME),
            new Completion(2, CoalescePlan.SUPERSEDED_OUTCOME),
            new Completion(3, CoalescePlan.SUPERSEDED_OUTCOME),
            new Completion(9, SUCCESS));
  }

  @Test
  void poisonIsCompletedWithItsOwnOutcomeAndNeverExecuted() throws InterruptedException {
    var threads = new HeldThreads();
    var dispatcher = dispatcher(threads, succeeding());
    dispatch(dispatcher, CoalescerTest.poison(1));
    dispatch(dispatcher, CoalescerTest.valid(2));

    threads.release();

    awaitQuiescence(dispatcher, 2);
    assertThat(executed).containsExactly(2L);
    assertThat(completions.getFirst().offset()).isEqualTo(1L);
    assertThat(completions.getFirst().outcome()).isInstanceOf(Outcome.Terminal.class);
    assertThat(completions.getLast()).isEqualTo(new Completion(2, SUCCESS));
  }

  @Test
  void recordsArrivingDuringAJobAreDrainedNextBySameDrainer() throws InterruptedException {
    var firstJobStarted = new CountDownLatch(1);
    var releaseFirstJob = new CountDownLatch(1);
    JobExecutor blockingFirst =
        job -> {
          executed.add(job.record().offset());
          if (job.record().offset() == 1) {
            firstJobStarted.countDown();
            awaitQuietly(releaseFirstJob);
          }
          return SUCCESS;
        };
    var dispatcher = dispatcher(Thread::startVirtualThread, blockingFirst);

    dispatch(dispatcher, CoalescerTest.valid(1));
    assertThat(firstJobStarted.await(5, TimeUnit.SECONDS)).isTrue();
    dispatch(dispatcher, CoalescerTest.valid(2));
    dispatch(dispatcher, CoalescerTest.valid(3));
    releaseFirstJob.countDown();

    awaitQuiescence(dispatcher, 3);
    assertThat(executed).containsExactly(1L, 3L);
    assertThat(spawns).hasValue(1);
  }

  @Test
  void aRetiredPrGetsAFreshDrainerForItsNextRecord() throws InterruptedException {
    var dispatcher = dispatcher(Thread::startVirtualThread, succeeding());

    dispatch(dispatcher, CoalescerTest.valid(1));
    awaitQuiescence(dispatcher, 1);
    dispatch(dispatcher, CoalescerTest.valid(2));
    awaitQuiescence(dispatcher, 2);

    assertThat(executed).containsExactly(1L, 2L);
    assertThat(spawns).hasValue(2);
  }

  // An Error, not an Exception: a catch-all narrower than Throwable would let it kill the drainer.
  @Test
  void anErrorFromAJobIsClassifiedLikeAnyOtherFailure() throws InterruptedException {
    var overflow = new StackOverflowError();
    JobExecutor overflowing =
        job -> {
          if (job.record().offset() == 1) {
            throw overflow;
          }
          return SUCCESS;
        };
    var dispatcher = dispatcher(Thread::startVirtualThread, overflowing);

    dispatch(dispatcher, CoalescerTest.valid(1));
    awaitQuiescence(dispatcher, 1);
    dispatch(dispatcher, CoalescerTest.valid(2));
    awaitQuiescence(dispatcher, 2);

    assertThat(completions)
        .containsExactly(
            new Completion(1, FailureClassifier.classify(overflow)), new Completion(2, SUCCESS));
  }

  @Test
  void aThrowingJobIsClassifiedCompletedOnceAndTheDrainerCarriesOn() throws InterruptedException {
    var boom = new IllegalStateException("pipeline bug");
    var firstJobStarted = new CountDownLatch(1);
    var releaseFirstJob = new CountDownLatch(1);
    JobExecutor throwingFirst =
        job -> {
          executed.add(job.record().offset());
          if (job.record().offset() == 1) {
            firstJobStarted.countDown();
            awaitQuietly(releaseFirstJob);
            throw boom;
          }
          return SUCCESS;
        };
    var dispatcher = dispatcher(Thread::startVirtualThread, throwingFirst);

    dispatch(dispatcher, CoalescerTest.valid(1));
    assertThat(firstJobStarted.await(5, TimeUnit.SECONDS)).isTrue();
    dispatch(dispatcher, CoalescerTest.valid(2));
    releaseFirstJob.countDown();

    awaitQuiescence(dispatcher, 2);
    assertThat(completions)
        .containsExactly(
            new Completion(1, FailureClassifier.classify(boom)), new Completion(2, SUCCESS));
    assertThat(spawns).hasValue(1);
    assertThat(offsetsOf(tracker.committable())).containsEntry(P0, 2L);
  }

  @Test
  void aThrowingHandlerStillCompletesTheRecordOnceAndTheDrainerCarriesOn()
      throws InterruptedException {
    var boom = new IllegalStateException("handler bug");
    var threads = new HeldThreads();
    OutcomeHandler throwingOnFirst =
        (record, outcome) -> {
          if (record.offset() == 1) {
            throw boom;
          }
          recordingHandler.handle(record, outcome);
        };
    var dispatcher = dispatcher(threads, succeeding(), throwingOnFirst);
    dispatch(dispatcher, CoalescerTest.poison(1));
    dispatch(dispatcher, CoalescerTest.valid(2));

    threads.release();

    awaitQuiescence(dispatcher, 1);
    assertThat(metrics.outcomeHandlerFailures).containsExactly(boom);
    assertThat(metrics.markCompleteFailures).isEmpty();
    assertThat(executed).containsExactly(2L);
    assertThat(offsetsOf(tracker.committable())).containsEntry(P0, 2L);
  }

  // A zombie: its partition is registered again (new generation) while its job runs (I7). The
  // tracker releases a zombie's permit on every call, so a second markComplete would over-release.
  @Test
  void aZombieWhoseHandlerThrowsReleasesItsPermitExactlyOnce() throws InterruptedException {
    var jobStarted = new CountDownLatch(1);
    var releaseJob = new CountDownLatch(1);
    JobExecutor blocking =
        job -> {
          jobStarted.countDown();
          awaitQuietly(releaseJob);
          return SUCCESS;
        };
    OutcomeHandler throwing =
        (record, outcome) -> {
          throw new IllegalStateException("handler bug");
        };
    var dispatcher = dispatcher(Thread::startVirtualThread, blocking, throwing);

    dispatch(dispatcher, CoalescerTest.valid(1));
    assertThat(jobStarted.await(5, TimeUnit.SECONDS)).isTrue();
    tracker.register(P0, 1);
    releaseJob.countDown();

    awaitQuiescence(dispatcher, 0);
    assertThat(metrics.outcomeHandlerFailures).hasSize(1);
    assertThat(metrics.markCompleteFailures).isEmpty();
    assertThat(tracker.committable()).isEmpty();
  }

  // E7 replaces the metrics port; a backend that throws must not cost a record its completion.
  @Test
  void aThrowingMetricsBackendCannotSkipACompletionOrStopTheDrainer() throws InterruptedException {
    EngineMetrics broken = new ThrowingEngineMetrics();
    OutcomeHandler throwing =
        (record, outcome) -> {
          throw new IllegalStateException("handler bug");
        };
    var threads = new HeldThreads();
    var dispatcher = dispatcher(threads, succeeding(), throwing, broken);
    dispatch(dispatcher, CoalescerTest.poison(1));
    dispatch(dispatcher, CoalescerTest.valid(2));

    threads.release();

    awaitQuiescence(dispatcher, 0);
    assertThat(executed).containsExactly(2L);
    assertThat(offsetsOf(tracker.committable())).containsEntry(P0, 2L);
  }

  // A poller bug dispatches offset 1 twice with one receipt; the tracker refuses the second
  // markComplete. The drainer must report it once, never retry it, and still finish the batch.
  @Test
  void aThrowingMarkCompleteIsReportedOnceAndTheRestOfTheBatchStillCompletes()
      throws InterruptedException {
    var threads = new HeldThreads();
    var dispatcher = dispatcher(threads, succeeding());
    assertThat(permits.tryAcquire(2)).isTrue();
    TrackedOffset receipt = tracker.track(P0, 1);
    dispatcher.route(new TrackedRecord(CoalescerTest.valid(1), receipt));
    dispatcher.route(new TrackedRecord(CoalescerTest.valid(1), receipt));
    dispatch(dispatcher, CoalescerTest.valid(2));

    threads.release();

    awaitDrained(dispatcher, 3);
    assertThat(metrics.markCompleteFailures)
        .singleElement()
        .isInstanceOf(IllegalStateException.class);
    assertThat(executed).containsExactly(2L);
    assertThat(offsetsOf(tracker.committable())).containsEntry(P0, 2L);
    assertThat(permits.availablePermits()).as("the refused duplicate keeps one").isEqualTo(CAP - 1);
  }

  // A job that leaves its thread interrupted must not make the handler's produce fail (I9).
  @Test
  void anInterruptLeftByAJobIsClearedBeforeTheHandlerRuns() throws InterruptedException {
    var boom = new IllegalStateException(new InterruptedException());
    var handlerSawInterrupt = new CopyOnWriteArrayList<Boolean>();
    JobExecutor interruptingFirst =
        job -> {
          if (job.record().offset() == 1) {
            Thread.currentThread().interrupt();
            throw boom;
          }
          return SUCCESS;
        };
    OutcomeHandler checking =
        (record, outcome) -> {
          handlerSawInterrupt.add(Thread.currentThread().isInterrupted());
          recordingHandler.handle(record, outcome);
        };
    var threads = new HeldThreads();
    var dispatcher = dispatcher(threads, interruptingFirst, checking);
    dispatch(dispatcher, CoalescerTest.valid(1));

    threads.release();

    awaitQuiescence(dispatcher, 1);
    assertThat(handlerSawInterrupt).containsExactly(false);
    assertThat(metrics.clearedInterrupts).extracting(TrackedOffset::offset).containsExactly(1L);
    assertThat(completions)
        .containsExactly(new Completion(1, FailureClassifier.classify(boom)));
  }

  // The common shape: code catches InterruptedException, restores the flag and returns normally.
  // Every step's handler must still start clear, whichever earlier step left the flag set.
  @Test
  void noHandlerStartsInterruptedOnAnyStepOfTheBatch() throws InterruptedException {
    var transientOnInterrupt =
        new Outcome.Transient(FailureClass.UNEXPECTED, new InterruptedException());
    JobExecutor restoresAndReturns =
        job -> {
          Thread.currentThread().interrupt();
          return transientOnInterrupt;
        };
    var handlerSawInterrupt = new CopyOnWriteArrayList<Boolean>();
    OutcomeHandler restoresAfterPoison =
        (record, outcome) -> {
          handlerSawInterrupt.add(Thread.currentThread().isInterrupted());
          recordingHandler.handle(record, outcome);
          if (record.offset() == 1) {
            Thread.currentThread().interrupt();
          }
        };
    var threads = new HeldThreads();
    var dispatcher = dispatcher(threads, restoresAndReturns, restoresAfterPoison);
    dispatch(dispatcher, CoalescerTest.poison(1));
    dispatch(dispatcher, CoalescerTest.valid(2));
    dispatch(dispatcher, CoalescerTest.valid(3));

    threads.release();

    awaitQuiescence(dispatcher, 3);
    assertThat(handlerSawInterrupt).containsExactly(false, false, false);
    assertThat(completions)
        .extracting(Completion::offset)
        .containsExactly(1L, 2L, 3L);
    assertThat(completions.getLast().outcome()).isEqualTo(transientOnInterrupt);
    assertThat(metrics.clearedInterrupts)
        .extracting(TrackedOffset::offset)
        .containsExactly(2L, 3L);
  }

  // The plan names a record the batch never held: no receipt for it, so nothing may complete first.
  @Test
  void aCoalescerThatReturnsAForeignRecordFailsTheWholeBatchBeforeAnyCompletion()
      throws InterruptedException {
    var foreign = CoalescerTest.valid(99);
    Function<List<ConsumerRecord<String, byte[]>>, CoalescePlan> slipping =
        records -> new CoalescePlan(List.of(), List.of(records.getFirst(), foreign), List.of());
    DrainerFactory real =
        Drainer.factory(succeeding(), recordingHandler, tracker, metrics, slipping);
    var threads = new HeldThreads();
    var dispatcher = new Dispatcher(threads, real, tracked -> () -> {}, metrics);
    dispatch(dispatcher, CoalescerTest.valid(1));
    dispatch(dispatcher, CoalescerTest.valid(2));

    threads.release();

    awaitQuiescence(dispatcher, 2);
    assertThat(completions).extracting(Completion::offset).containsExactly(1L, 2L);
    assertThat(completions)
        .extracting(Completion::outcome)
        .allSatisfy(
            outcome ->
                assertThat(outcome)
                    .isInstanceOfSatisfying(
                        Outcome.Transient.class,
                        failed ->
                            assertThat(failed.exception()).isInstanceOf(NullPointerException.class)));
    assertThat(executed).isEmpty();
  }

  // Coalescer.parse catches only envelope failures; this record fails there with something else.
  @Test
  void aBatchThatCannotBeCoalescedIsCompletedRecordByRecordAndTheDrainerSurvives()
      throws InterruptedException {
    var boom = new IllegalStateException("unexpected while coalescing");
    var threads = new HeldThreads();
    var dispatcher = dispatcher(threads, succeeding());
    dispatch(dispatcher, CoalescerTest.valid(1));
    dispatch(dispatcher, unreadable(2, boom));

    threads.release();

    awaitQuiescence(dispatcher, 2);
    Outcome classified = FailureClassifier.classify(boom);
    assertThat(completions)
        .containsExactly(new Completion(1, classified), new Completion(2, classified));
    assertThat(executed).isEmpty();
    assertThat(offsetsOf(tracker.committable())).containsEntry(P0, 2L);
  }

  private static ConsumerRecord<String, byte[]> unreadable(long offset, RuntimeException failure) {
    return new ConsumerRecord<String, byte[]>(
        Topics.REVIEW_REQUESTED, 0, offset, "12345:acme/app:17", new byte[0]) {
      @Override
      public byte[] value() {
        throw failure;
      }
    };
  }

  // The scripted retirer plays the poller: its record lands between the empty drain and retirement.
  @Test
  void aRecordLandingJustBeforeRetirementMakesTheDrainerLoopInsteadOfExiting() {
    var queue = new PrQueue();
    assertThat(permits.tryAcquire()).isTrue();
    var late = new TrackedRecord(CoalescerTest.valid(1), tracker.track(P0, 1));
    var retireAsks = new AtomicInteger();
    Retirer routeRacesRetirement =
        key -> {
          if (retireAsks.incrementAndGet() == 1) {
            queue.add(late);
            return false;
          }
          return queue.isEmpty();
        };

    Drainer.factory(succeeding(), recordingHandler, tracker, metrics)
        .create(PrKey.parse("12345:acme/app:17"), queue, routeRacesRetirement)
        .run();

    assertThat(retireAsks).hasValue(2);
    assertThat(completions).containsExactly(new Completion(1, SUCCESS));
    assertThat(permits.availablePermits()).isEqualTo(CAP);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
