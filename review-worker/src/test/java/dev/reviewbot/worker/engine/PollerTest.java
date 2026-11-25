package dev.reviewbot.worker.engine;

import static dev.reviewbot.worker.engine.Committables.offsetsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.keys.PrKey;

class PollerTest {

  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);
  private static final TopicPartition P1 = new TopicPartition(Topics.REVIEW_REQUESTED, 1);
  private static final int IN_FLIGHT_CAP = 200;
  private static final int MAX_POLL_RECORDS = 100;
  private static final int RESUME_THRESHOLD = 100;
  private static final int RESUME_THRESHOLD_ABOVE_BATCH = 150;
  private static final String PR_42 = new PrKey(1, "acme/app", 42).render();
  private static final Duration REVOCATION_GRACE = Duration.ofSeconds(3);
  private static final Duration KAFKA_CALL_TIMEOUT = Duration.ofSeconds(10);

  private final CountingConsumer consumer = new CountingConsumer();
  private final Semaphore inFlight = new Semaphore(IN_FLIGHT_CAP);
  private final OffsetTracker tracker = new OffsetTracker(inFlight);
  private final RecordingEngineMetrics metrics = new RecordingEngineMetrics();
  private int deathsReported;
  private final List<Duration> graceSleeps = new ArrayList<>();
  private Runnable duringGrace = () -> {};
  private final Sleeper sleeper =
      duration -> {
        graceSleeps.add(duration);
        duringGrace.run();
      };
  private final List<PrQueue> queuesWithADrainer = new ArrayList<>();
  private final List<TrackedRecord> badKeyRecords = new ArrayList<>();
  private final List<Integer> freePermitsWhenADrainerStarts = new ArrayList<>();
  private Executor drainerThreads =
      drainer -> freePermitsWhenADrainerStarts.add(inFlight.availablePermits());
  private final Dispatcher dispatcher =
      new Dispatcher(
          drainer -> drainerThreads.execute(drainer),
          (key, queue, retirer) -> {
            queuesWithADrainer.add(queue);
            return () -> {};
          },
          tracked -> {
            badKeyRecords.add(tracked);
            return () -> {};
          },
          metrics);

  @Test
  void anIdleWorkerNeverPauses() {
    var poller = pollerOwning(P0, P1);

    poller.runOnce();
    poller.runOnce();

    assertThat(consumer.pauseCalls).isZero();
    assertThat(metrics.pauseStates).isEmpty();
  }

  @Test
  void exactlyOneBatchOfFreePermitsStillPolls() {
    var poller = pollerOwning(P0, P1);
    setFreePermits(MAX_POLL_RECORDS);

    poller.runOnce();

    assertThat(consumer.pauseCalls).isZero();
  }

  @Test
  void fewerFreePermitsThanOneBatchPausesOnceNotEveryCycle() {
    var poller = pollerOwning(P0, P1);
    setFreePermits(MAX_POLL_RECORDS - 1);

    poller.runOnce();
    poller.runOnce();
    poller.runOnce();

    assertThat(consumer.pauseCalls).isOne();
    assertThat(consumer.paused()).containsExactlyInAnyOrder(P0, P1);
    assertThat(metrics.pauseStates).containsExactly("BACKPRESSURE:true");
  }

  @Test
  void resumesOnceWhenFreePermitsReachTheThreshold() {
    var poller = pollerOwning(P0, P1);
    setFreePermits(MAX_POLL_RECORDS - 1);
    poller.runOnce();

    setFreePermits(RESUME_THRESHOLD);
    poller.runOnce();
    poller.runOnce();

    assertThat(consumer.resumeCalls).isOne();
    assertThat(consumer.paused()).isEmpty();
    assertThat(metrics.pauseStates).containsExactly("BACKPRESSURE:true", "BACKPRESSURE:false");
  }

  @Test
  void insideTheHysteresisBandAPausedPollerStaysPaused() {
    var poller = pollerOwning(RESUME_THRESHOLD_ABOVE_BATCH, P0, P1);
    setFreePermits(MAX_POLL_RECORDS - 1);
    poller.runOnce();

    setFreePermits(120);
    poller.runOnce();
    assertThat(consumer.resumeCalls).isZero();

    setFreePermits(RESUME_THRESHOLD_ABOVE_BATCH);
    poller.runOnce();
    assertThat(consumer.resumeCalls).isOne();
  }

  @Test
  void insideTheHysteresisBandARunningPollerKeepsRunning() {
    var poller = pollerOwning(RESUME_THRESHOLD_ABOVE_BATCH, P0, P1);
    setFreePermits(120);

    poller.runOnce();

    assertThat(consumer.pauseCalls).isZero();
  }

  @Test
  void dependencyPauseAlonePausesWithEveryPermitFree() {
    var poller = pollerOwning(P0, P1);
    poller.setDependencyPause(true);

    poller.runOnce();

    assertThat(consumer.paused()).containsExactlyInAnyOrder(P0, P1);
    assertThat(metrics.pauseStates).isEmpty();
  }

  @Test
  void dependencyPauseBlocksResumeUntilCleared() {
    var poller = pollerOwning(P0, P1);
    poller.setDependencyPause(true);
    poller.runOnce();
    poller.runOnce();
    assertThat(consumer.resumeCalls).isZero();

    poller.setDependencyPause(false);
    poller.runOnce();

    assertThat(consumer.resumeCalls).isOne();
    assertThat(consumer.paused()).isEmpty();
  }

  @Test
  void clearingDependencyPauseDoesNotResumeWhilePermitsAreShort() {
    var poller = pollerOwning(P0, P1);
    poller.setDependencyPause(true);
    setFreePermits(50);
    poller.runOnce();

    poller.setDependencyPause(false);
    poller.runOnce();

    assertThat(consumer.resumeCalls).isZero();
    assertThat(consumer.paused()).containsExactlyInAnyOrder(P0, P1);
  }

  @Test
  void anAssignedPartitionIsRegisteredAtTheConsumerPosition() {
    consumer.updateBeginningOffsets(Map.of(P0, 40L));
    var poller = poller(RESUME_THRESHOLD);
    poller.subscribe();

    consumer.rebalance(List.of(P0));

    assertThat(tracker.watermark(P0)).hasValue(39);
  }

  @Test
  void aPartitionGainedWhilePausedStartsPaused() {
    var poller = pollerOwning(P0);
    setFreePermits(MAX_POLL_RECORDS - 1);
    poller.runOnce();

    consumer.rebalance(List.of(P0, P1));

    assertThat(consumer.paused()).containsExactlyInAnyOrder(P0, P1);
  }

  @Test
  void aPartitionGainedWhileRunningStartsUnpaused() {
    var poller = pollerOwning(P0);
    poller.runOnce();

    consumer.rebalance(List.of(P0, P1));

    assertThat(consumer.paused()).isEmpty();
  }

  @Test
  void revocationClosesThenWaitsThenCommitsThenUnregisters() {
    var poller = pollerOwning(P0);
    var receipts = dispatch(poller, P0, 3);
    complete(receipts.get(0), receipts.get(1));
    var steps = new ArrayList<String>();
    duringGrace =
        () -> {
          steps.add(stepState("grace", P0));
          complete(receipts.get(2));
        };
    consumer.beforeCommitSync = () -> steps.add(stepState("commitSync", P0));

    consumer.rebalance(List.of());

    assertThat(steps)
        .containsExactly("grace closed=true registered=true", "commitSync closed=true registered=true");
    assertThat(graceSleeps).containsExactly(REVOCATION_GRACE);
    // Offset 2 finished during the grace: the commit must carry it.
    assertThat(consumer.syncCommits).containsExactly(Map.of(P0, new OffsetAndMetadata(3)));
    assertThat(tracker.watermark(P0)).isEmpty();
  }

  @Test
  void aFailedHandoverCommitIsReportedAndThePartitionIsStillForgotten() {
    var poller = pollerOwning(P0, P1);
    var receipts = dispatch(poller, P0, 2);
    complete(receipts.get(0));
    consumer.rejectNextCommitSync = new TimeoutException("coordinator slow");

    consumer.rebalance(List.of(P1));
    complete(receipts.get(1));
    poller.runOnce();

    assertThat(metrics.commitFailures).containsExactly(Set.of(P0));
    assertThat(tracker.watermark(P0)).isEmpty();
    assertThat(consumer.committed(Set.of(P0))).isEmpty();
    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP);
    assertThat(metrics.pollerDeaths).isEmpty();
  }

  @Test
  void aLateCommitAnswerForAPartitionThatLeftAndCameBackIsIgnored() {
    var poller = pollerOwning(P0, P1);
    var receipts = dispatch(poller, P0, 3);
    complete(receipts.toArray(TrackedOffset[]::new));
    consumer.holdAsyncAnswers = true;
    poller.runOnce();
    consumer.rebalance(List.of(P1));
    consumer.rebalance(List.of(P0, P1));

    consumer.answerHeldCommits();

    assertThat(tracker.watermark(P0)).hasValue(-1);
    assertThat(tracker.committable()).isEmpty();
  }

  @Test
  void assignmentRegistersAtThePositionBeforeReopening() {
    var poller = pollerOwning(P0, P1);
    consumer.rebalance(List.of(P1));
    var closedWhileRegistering = new ArrayList<Boolean>();
    consumer.beforePosition = () -> closedWhileRegistering.add(dispatcher.isClosed(P0));

    consumer.rebalance(List.of(P0, P1));

    assertThat(closedWhileRegistering).containsExactly(true);
    assertThat(dispatcher.isClosed(P0)).isFalse();
    assertThat(tracker.watermark(P0)).hasValue(-1);
  }

  @Test
  void aPartitionNoLongerAssignedIsLeftOutOfTheCommitEvenAsItsWatermarkAdvances() {
    var poller = pollerOwning(P0, P1);
    var receipts = dispatch(poller, P1, 2);
    consumer.revokedAfterFetch = Set.of(P1);

    complete(receipts.get(0));
    poller.runOnce();
    complete(receipts.get(1));
    poller.runOnce();

    assertThat(tracker.watermark(P1)).hasValue(1);
    assertThat(consumer.commitCalls).isZero();
    assertThat(consumer.committed(Set.of(P1))).isEmpty();
    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP);
  }

  @Test
  void theHandoverCommitCarriesOnlyTheRevokedPartitions() {
    var poller = pollerOwning(P0, P1);
    var p0Receipt = dispatch(poller, P0, 1).get(0);
    var p1Receipt = dispatch(poller, P1, 1).get(0);
    complete(p0Receipt, p1Receipt);

    consumer.rebalance(List.of(P1));

    assertThat(consumer.syncCommits).containsExactly(Map.of(P0, new OffsetAndMetadata(1)));
    assertThat(offsetsOf(tracker.committable())).containsExactly(entry(P1, 0L));
  }

  @Test
  void closingRunsNoSecondHandoverAfterTheFinalCommit() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));
    drainerThreads = drainer -> tracker.markComplete(tracker.track(P0, 0));
    leaveTheWatermarkForTheFinalCommit();
    consumer.schedulePollTask(poller::stop);

    poller.run();

    assertThat(consumer.syncCommits).containsExactly(Map.of(P0, new OffsetAndMetadata(1)));
    assertThat(graceSleeps).isEmpty();
  }

  @Test
  void aZombieCompletionReturnsItsPermitAndIsNeverCommitted() {
    var poller = pollerOwning(P0, P1);
    var receipts = dispatch(poller, P0, 1);
    consumer.rebalance(List.of(P1));

    complete(receipts.get(0));
    poller.runOnce();

    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP);
    assertThat(consumer.commitCalls).isZero();
    assertThat(consumer.committed(Set.of(P0))).isEmpty();
  }

  @Test
  void anOwnedRecordIsRoutedWithItsReceiptAndHoldsOnePermit() {
    var poller = pollerOwning(P0);
    consumer.addRecord(record(P0, 0, PR_42));

    poller.runOnce();

    assertThat(queuesWithADrainer)
        .singleElement()
        .satisfies(
            queue ->
                assertThat(queue.drainAll())
                    .singleElement()
                    .satisfies(
                        routed ->
                            assertThat(routed.trackedOffset()).isEqualTo(tracker.track(P0, 0))));
    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP - 1);
    assertThat(metrics.closedPartitionRoutes).isEmpty();
  }

  @Test
  void thePermitIsTakenBeforeTheRecordIsRouted() {
    var poller = pollerOwning(P0);
    consumer.addRecord(record(P0, 0, PR_42));

    poller.runOnce();

    assertThat(freePermitsWhenADrainerStarts).containsExactly(IN_FLIGHT_CAP - 1);
  }

  @Test
  void aRecordOfAPartitionRevokedAfterTheFetchIsSkippedWithoutAPermit() {
    var poller = pollerOwning(P0, P1);
    consumer.addRecord(record(P1, 0, PR_42));
    consumer.addRecord(record(P0, 0, PR_42));
    consumer.revokedAfterFetch = Set.of(P1);

    poller.runOnce();

    assertThat(queuesWithADrainer)
        .singleElement()
        .satisfies(
            queue ->
                assertThat(queue.drainAll())
                    .extracting(routed -> routed.trackedOffset().partition())
                    .containsExactly(P0));
    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP - 1);
    assertThat(tracker.watermark(P1)).hasValue(-1);
    assertThat(metrics.closedPartitionRoutes).isEmpty();
  }

  @Test
  void aRecordWithAnUnparseableKeyGoesToItsPoisonTaskAndTheBatchGoesOn() {
    var poller = pollerOwning(P0);
    consumer.addRecord(record(P0, 0, "not-a-pr-key"));
    consumer.addRecord(record(P0, 1, PR_42));

    poller.runOnce();

    assertThat(badKeyRecords)
        .extracting(tracked -> tracked.trackedOffset().offset())
        .containsExactly(0L);
    assertThat(queuesWithADrainer).hasSize(1);
    assertThat(tracker.watermark(P0)).hasValue(-1);
    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP - 2);
    assertThat(consumer.commitCalls).isZero();
    assertThat(metrics.pollerDeaths).isEmpty();
  }

  @Test
  void aShutDownDrainerExecutorEndsTheLoopWithoutCompletingTheRecord() {
    drainerThreads =
        drainer -> {
          throw new RejectedExecutionException("drainer executor shut down");
        };
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));

    poller.run();

    assertThat(metrics.pollerDeaths)
        .singleElement()
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(tracker.watermark(P0)).hasValue(-1);
    assertThat(consumer.commitCalls).isZero();
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void pauseStateClearsWhenPermitsRecoverDuringADependencyOutage() {
    var poller = pollerOwning(P0, P1);
    setFreePermits(MAX_POLL_RECORDS - 1);
    poller.runOnce();

    poller.setDependencyPause(true);
    setFreePermits(RESUME_THRESHOLD);
    poller.runOnce();

    assertThat(consumer.paused()).containsExactlyInAnyOrder(P0, P1);
    assertThat(metrics.pauseStates).containsExactly("BACKPRESSURE:true", "BACKPRESSURE:false");
  }

  @Test
  void pauseStateNamesBackpressureWhenADependencyClearsWithPermitsStillShort() {
    var poller = pollerOwning(P0, P1);
    poller.setDependencyPause(true);
    poller.runOnce();
    assertThat(metrics.pauseStates).isEmpty();

    setFreePermits(50);
    poller.setDependencyPause(false);
    poller.runOnce();

    assertThat(consumer.paused()).containsExactlyInAnyOrder(P0, P1);
    assertThat(metrics.pauseStates).containsExactly("BACKPRESSURE:true");
  }

  @Test
  void theCommittedOffsetIsTheWatermarkPlusOne() {
    var poller = pollerOwning(P0);
    var receipts = dispatch(poller, P0, 3);

    complete(receipts.get(0), receipts.get(1));
    poller.runOnce();

    assertThat(consumer.committed(Set.of(P0)))
        .containsEntry(P0, new OffsetAndMetadata(2));
  }

  @Test
  void aGapHoldsTheCommitBelowIt() {
    var poller = pollerOwning(P0);
    var receipts = dispatch(poller, P0, 3);

    complete(receipts.get(0), receipts.get(2));
    poller.runOnce();

    assertThat(consumer.committed(Set.of(P0)))
        .containsEntry(P0, new OffsetAndMetadata(1));
  }

  @Test
  void nothingAdvancedMeansNoCommitCall() {
    var poller = pollerOwning(P0);
    dispatch(poller, P0, 2);

    poller.runOnce();

    assertThat(consumer.commitCalls).isZero();
  }

  @Test
  void aConfirmedCommitIsNotSentAgain() {
    var poller = pollerOwning(P0);
    var receipts = dispatch(poller, P0, 1);
    complete(receipts.get(0));
    poller.runOnce();

    poller.runOnce();

    assertThat(consumer.commitCalls).isOne();
  }

  @Test
  void aRejectedCommitIsReportedAndResentNextCycle() {
    var poller = pollerOwning(P0);
    var receipts = dispatch(poller, P0, 1);
    complete(receipts.get(0));
    consumer.rejectNextCommit = new RebalanceInProgressException();

    poller.runOnce();
    assertThat(metrics.commitFailures).containsExactly(Set.of(P0));
    assertThat(consumer.committed(Set.of(P0))).isEmpty();

    poller.runOnce();
    assertThat(consumer.commitCalls).isEqualTo(2);
    assertThat(consumer.committed(Set.of(P0))).containsEntry(P0, new OffsetAndMetadata(1));
  }

  @Test
  void anythingEscapingTheLoopIsReportedAndTriggersTheDeathAction() {
    var poller = pollerAssignedP0OnFirstPoll();
    var brokerGone = new KafkaException("broker gone");
    consumer.setPollException(brokerGone);

    poller.run();

    assertThat(metrics.pollerDeaths).containsExactly(brokerGone);
    assertThat(deathsReported).isOne();
  }

  @Test
  void stopDuringAPollWakesItAndEndsTheRunWithoutADeath() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(poller::stop);

    poller.run();

    assertThat(consumer.wakeups).isOne();
    assertThat(metrics.pollerDeaths).isEmpty();
    assertThat(deathsReported).isZero();
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void aWakeupNobodyAskedForIsIgnoredAndPollingGoesOn() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(consumer::wakeup);
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));
    consumer.schedulePollTask(poller::stop);

    poller.run();

    assertThat(queuesWithADrainer).hasSize(1);
    assertThat(deathsReported).isZero();
  }

  @Test
  void aWakeupLeftFromAStopDuringIntakeIsSpentAndTheFinalCommitStillLands() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));
    drainerThreads =
        drainer -> {
          tracker.markComplete(tracker.track(P0, 0));
          poller.stop();
        };
    leaveTheWatermarkForTheFinalCommit();

    poller.run();

    assertThat(consumer.syncCommits).containsExactly(Map.of(P0, new OffsetAndMetadata(1)));
    assertThat(metrics.commitFailures).containsExactly(Set.of(P0));
    assertThat(deathsReported).isZero();
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void aRetryAfterTheLeftoverWakeupThatAlsoFailsIsReportedNotFatal() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));
    drainerThreads =
        drainer -> {
          tracker.markComplete(tracker.track(P0, 0));
          consumer.rejectNextCommitSync = new TimeoutException("coordinator slow");
          poller.stop();
        };
    leaveTheWatermarkForTheFinalCommit();

    poller.run();

    assertThat(metrics.commitFailures).containsExactly(Set.of(P0), Set.of(P0));
    assertThat(consumer.syncCommits).isEmpty();
    assertThat(deathsReported).isZero();
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void aFailedFinalCommitIsReportedAndTheConsumerStillCloses() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));
    drainerThreads = drainer -> tracker.markComplete(tracker.track(P0, 0));
    leaveTheWatermarkForTheFinalCommit();
    consumer.schedulePollTask(
        () -> {
          consumer.rejectNextCommitSync = new TimeoutException("coordinator slow");
          poller.stop();
        });

    poller.run();

    assertThat(metrics.commitFailures).containsExactly(Set.of(P0), Set.of(P0));
    assertThat(consumer.syncCommits).isEmpty();
    assertThat(deathsReported).isZero();
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void shutdownWaitsForInFlightWorkThenCommitsIt() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));
    consumer.schedulePollTask(poller::stop);
    Thread pollerThread = Thread.currentThread();
    drainerThreads =
        drainer ->
            Thread.ofVirtual()
                .start(
                    () -> {
                      awaitTimedWait(pollerThread);
                      tracker.markComplete(tracker.track(P0, 0));
                    });

    poller.run();

    assertThat(consumer.syncCommits).containsExactly(Map.of(P0, new OffsetAndMetadata(1)));
    assertThat(metrics.expiredShutdownGraces).isEmpty();
    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP);
  }

  // The poller parks only in the shutdown wait, so this completes the record exactly while it waits.
  // Bounded: without that wait the poller never parks, and the test fails on the missing commit.
  private static void awaitTimedWait(Thread thread) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
  }

  @Test
  void shutdownGivesUpAfterTheGraceAndCommitsOnlyWhatFinished() {
    var poller = pollerAssignedP0OnFirstPoll(Duration.ofMillis(100));
    consumer.schedulePollTask(
        () -> {
          consumer.addRecord(record(P0, 0, PR_42));
          consumer.addRecord(record(P0, 1, PR_42));
        });
    consumer.schedulePollTask(poller::stop);
    drainerThreads = drainer -> tracker.markComplete(tracker.track(P0, 0));
    leaveTheWatermarkForTheFinalCommit();

    poller.run();

    assertThat(metrics.expiredShutdownGraces).containsExactly(1);
    assertThat(consumer.syncCommits).containsExactly(Map.of(P0, new OffsetAndMetadata(1)));
    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP - 1);
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void anInterruptDuringTheShutdownWaitIsKeptAndItsFailedCommitAndCloseAreReportedNotFatal() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));
    drainerThreads = drainer -> tracker.markComplete(tracker.track(P0, 0));
    leaveTheWatermarkForTheFinalCommit();
    consumer.schedulePollTask(
        () -> {
          Thread.currentThread().interrupt();
          poller.stop();
        });

    poller.run();

    assertThat(Thread.interrupted()).isTrue();
    assertThat(consumer.syncCommits).isEmpty();
    assertThat(metrics.commitFailures).containsExactly(Set.of(P0), Set.of(P0));
    assertThat(metrics.closeFailures).singleElement().isInstanceOf(InterruptException.class);
    assertThat(inFlight.availablePermits()).isEqualTo(IN_FLIGHT_CAP);
    assertThat(deathsReported).isZero();
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void theFinalCommitAndTheCloseAreBoundedByTheKafkaCallTimeout() {
    var poller = pollerAssignedP0OnFirstPoll();
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 0, PR_42)));
    drainerThreads = drainer -> tracker.markComplete(tracker.track(P0, 0));
    leaveTheWatermarkForTheFinalCommit();
    consumer.schedulePollTask(poller::stop);

    poller.run();

    assertThat(consumer.syncCommitTimeouts).containsExactly(KAFKA_CALL_TIMEOUT);
    assertThat(consumer.closeTimeout).isEqualTo(KAFKA_CALL_TIMEOUT);
  }

  @Test
  void aPositionLookupThatFailsDuringAssignmentEndsThePoller() {
    var poller = pollerAssignedP0OnFirstPoll();
    var positionTimedOut = new TimeoutException("position");
    consumer.positionFailure = positionTimedOut;

    poller.run();

    assertThat(metrics.pollerDeaths).containsExactly(positionTimedOut);
    assertThat(deathsReported).isOne();
  }

  @Test
  void aLostPartitionIsForgottenWithoutAGraceOrACommit() {
    var poller = pollerOwning(P0, P1);
    var receipts = dispatch(poller, P0, 1);
    complete(receipts.get(0));

    consumer.listener.onPartitionsLost(List.of(P0));

    assertThat(graceSleeps).isEmpty();
    assertThat(consumer.syncCommits).isEmpty();
    assertThat(metrics.commitFailures).isEmpty();
    assertThat(tracker.watermark(P0)).isEmpty();
    assertThat(dispatcher.isClosed(P0)).isTrue();
  }

  @Test
  void aCloseThatFailsIsReportedAndIsNotAPollerDeath() {
    var poller = pollerAssignedP0OnFirstPoll();
    var closeTimedOut = new TimeoutException("close");
    consumer.closeFailure = closeTimedOut;
    consumer.schedulePollTask(poller::stop);

    poller.run();

    assertThat(metrics.closeFailures).containsExactly(closeTimedOut);
    assertThat(deathsReported).isZero();
  }

  @Test
  void aTrackerBugInIntakeEndsTheLoopInsteadOfLooping() {
    consumer.updateBeginningOffsets(Map.of(P0, 5L));
    var poller = poller(RESUME_THRESHOLD);
    consumer.schedulePollTask(() -> consumer.rebalance(List.of(P0)));
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 5, PR_42)));
    consumer.schedulePollTask(() -> tracker.unregister(List.of(P0)));
    consumer.schedulePollTask(() -> consumer.addRecord(record(P0, 6, PR_42)));

    poller.run();

    assertThat(metrics.pollerDeaths).singleElement().isInstanceOf(IllegalStateException.class);
    assertThat(deathsReported).isOne();
  }

  private List<TrackedOffset> dispatch(Poller poller, TopicPartition tp, int count) {
    for (int offset = 0; offset < count; offset++) {
      consumer.addRecord(record(tp, offset, PR_42));
    }
    poller.runOnce();
    return queuesWithADrainer.stream()
        .flatMap(queue -> queue.drainAll().stream())
        .map(TrackedRecord::trackedOffset)
        .toList();
  }

  private String stepState(String step, TopicPartition tp) {
    return step
        + " closed="
        + dispatcher.isClosed(tp)
        + " registered="
        + tracker.watermark(tp).isPresent();
  }

  private void complete(TrackedOffset... receipts) {
    for (TrackedOffset receipt : receipts) {
      tracker.markComplete(receipt);
    }
  }

  private static ConsumerRecord<String, byte[]> record(TopicPartition tp, long offset, String key) {
    return new ConsumerRecord<>(tp.topic(), tp.partition(), offset, key, new byte[0]);
  }

  private Poller pollerOwning(TopicPartition... partitions) {
    return pollerOwning(RESUME_THRESHOLD, partitions);
  }

  private Poller pollerOwning(int resumeThreshold, TopicPartition... partitions) {
    everyPartitionStartsAtZero();
    var poller = poller(resumeThreshold);
    poller.subscribe();
    consumer.rebalance(List.of(partitions));
    return poller;
  }

  private Poller pollerAssignedP0OnFirstPoll() {
    return pollerAssignedP0OnFirstPoll(Duration.ofSeconds(30));
  }

  // For run(): it subscribes itself, so the assignment has to arrive inside a poll.
  private Poller pollerAssignedP0OnFirstPoll(Duration shutdownGrace) {
    everyPartitionStartsAtZero();
    var poller = poller(RESUME_THRESHOLD, shutdownGrace);
    consumer.schedulePollTask(() -> consumer.rebalance(List.of(P0)));
    return poller;
  }

  private void leaveTheWatermarkForTheFinalCommit() {
    consumer.rejectNextCommit = new RebalanceInProgressException();
  }

  private void everyPartitionStartsAtZero() {
    consumer.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L));
  }

  private Poller poller(int resumeThreshold) {
    return poller(resumeThreshold, Duration.ofSeconds(30));
  }

  private Poller poller(int resumeThreshold, Duration shutdownGrace) {
    var settings =
        new ReviewbotProperties.Consumer(
            Duration.ofSeconds(45),
            Duration.ofSeconds(3),
            Duration.ofSeconds(300),
            Duration.ofMillis(200),
            MAX_POLL_RECORDS,
            IN_FLIGHT_CAP,
            resumeThreshold,
            shutdownGrace,
            REVOCATION_GRACE,
            KAFKA_CALL_TIMEOUT);
    return new Poller(
        consumer,
        Topics.REVIEW_REQUESTED,
        inFlight,
        tracker,
        dispatcher,
        metrics,
        settings,
        sleeper,
        () -> deathsReported++);
  }

  private void setFreePermits(int free) {
    int surplus = inFlight.availablePermits() - free;
    if (surplus >= 0) {
      inFlight.acquireUninterruptibly(surplus);
    } else {
      inFlight.release(-surplus);
    }
  }

  /**
   * MockConsumer with call counts ("once per transition", not just the end state), injectable
   * failures, and the real client's wakeup, interrupt and close-revokes-everything behavior.
   */
  private static final class CountingConsumer extends MockConsumer<String, byte[]> {

    int pauseCalls;
    int resumeCalls;
    int commitCalls;
    Set<TopicPartition> revokedAfterFetch = Set.of();
    KafkaException rejectNextCommit;
    final List<Map<TopicPartition, OffsetAndMetadata>> syncCommits = new ArrayList<>();
    Runnable beforeCommitSync = () -> {};
    KafkaException rejectNextCommitSync;
    boolean holdAsyncAnswers;
    Runnable beforePosition = () -> {};
    int wakeups;
    final List<Duration> syncCommitTimeouts = new ArrayList<>();
    Duration closeTimeout;
    KafkaException positionFailure;
    KafkaException closeFailure;
    ConsumerRebalanceListener listener;
    private boolean wakeupPending;
    private final List<Runnable> heldAsyncAnswers = new ArrayList<>();

    CountingConsumer() {
      super(OffsetResetStrategy.EARLIEST);
    }

    @Override
    public synchronized void pause(Collection<TopicPartition> partitions) {
      pauseCalls++;
      super.pause(partitions);
    }

    @Override
    public synchronized void commitAsync(
        Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback) {
      commitCalls++;
      if (rejectNextCommit != null) {
        KafkaException rejection = rejectNextCommit;
        rejectNextCommit = null;
        callback.onComplete(offsets, rejection);
        return;
      }
      if (holdAsyncAnswers) {
        heldAsyncAnswers.add(() -> super.commitAsync(offsets, callback));
        return;
      }
      super.commitAsync(offsets, callback);
    }

    @Override
    public synchronized long position(TopicPartition partition) {
      beforePosition.run();
      if (positionFailure != null) {
        throw positionFailure;
      }
      return super.position(partition);
    }

    @Override
    public synchronized void subscribe(
        Collection<String> topics, ConsumerRebalanceListener rebalanceListener) {
      listener = rebalanceListener;
      super.subscribe(topics, rebalanceListener);
    }

    @Override
    public synchronized void wakeup() {
      wakeups++;
      wakeupPending = true;
      super.wakeup();
    }

    @Override
    public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
      try {
        return super.poll(timeout);
      } catch (WakeupException spent) {
        wakeupPending = false;
        throw spent;
      }
    }

    synchronized void answerHeldCommits() {
      var held = List.copyOf(heldAsyncAnswers);
      heldAsyncAnswers.clear();
      held.forEach(Runnable::run);
    }

    @Override
    public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
      throwIfInterrupted();
      if (wakeupPending) {
        wakeupPending = false;
        throw new WakeupException();
      }
      beforeCommitSync.run();
      if (rejectNextCommitSync != null) {
        KafkaException rejection = rejectNextCommitSync;
        rejectNextCommitSync = null;
        throw rejection;
      }
      syncCommits.add(Map.copyOf(offsets));
      super.commitSync(offsets);
    }

    @Override
    public synchronized void commitSync(
        Map<TopicPartition, OffsetAndMetadata> offsets, Duration timeout) {
      syncCommitTimeouts.add(timeout);
      commitSync(offsets);
    }

    @Override
    public synchronized void close(Duration timeout) {
      closeTimeout = timeout;
      if (listener != null && !super.assignment().isEmpty()) {
        listener.onPartitionsRevoked(Set.copyOf(super.assignment()));
      }
      super.close(timeout);
      throwIfInterrupted();
      if (closeFailure != null) {
        throw closeFailure;
      }
    }

    // A real KafkaConsumer throws InterruptException from blocking calls while the flag is set.
    private static void throwIfInterrupted() {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptException("interrupted");
      }
    }

    @Override
    public synchronized Set<TopicPartition> assignment() {
      var stillAssigned = new HashSet<>(super.assignment());
      stillAssigned.removeAll(revokedAfterFetch);
      return stillAssigned;
    }

    @Override
    public synchronized void resume(Collection<TopicPartition> partitions) {
      resumeCalls++;
      super.resume(partitions);
    }
  }
}
