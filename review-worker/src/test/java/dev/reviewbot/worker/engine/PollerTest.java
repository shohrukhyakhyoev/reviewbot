package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

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

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RebalanceInProgressException;
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

  private final CountingConsumer consumer = new CountingConsumer();
  private final Semaphore inFlight = new Semaphore(IN_FLIGHT_CAP);
  private final OffsetTracker tracker = new OffsetTracker(inFlight);
  private final RecordingEngineMetrics metrics = new RecordingEngineMetrics();
  private int deathsReported;
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
    var poller = poller(RESUME_THRESHOLD);
    everyPartitionStartsAtZero();
    consumer.schedulePollTask(() -> consumer.rebalance(List.of(P0)));
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
    var poller = poller(RESUME_THRESHOLD);
    everyPartitionStartsAtZero();
    consumer.schedulePollTask(() -> consumer.rebalance(List.of(P0)));
    var brokerGone = new KafkaException("broker gone");
    consumer.setPollException(brokerGone);

    poller.run();

    assertThat(metrics.pollerDeaths).containsExactly(brokerGone);
    assertThat(deathsReported).isOne();
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

  private void everyPartitionStartsAtZero() {
    consumer.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L));
  }

  private Poller poller(int resumeThreshold) {
    var settings =
        new ReviewbotProperties.Consumer(
            Duration.ofSeconds(45),
            Duration.ofSeconds(3),
            Duration.ofSeconds(300),
            Duration.ofMillis(200),
            MAX_POLL_RECORDS,
            IN_FLIGHT_CAP,
            resumeThreshold,
            Duration.ofSeconds(30));
    return new Poller(
        consumer,
        Topics.REVIEW_REQUESTED,
        inFlight,
        tracker,
        dispatcher,
        metrics,
        settings,
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

  /** Counts pause/resume calls: the criterion is "once per transition", not just the end state. */
  private static final class CountingConsumer extends MockConsumer<String, byte[]> {

    int pauseCalls;
    int resumeCalls;
    int commitCalls;
    Set<TopicPartition> revokedAfterFetch = Set.of();
    KafkaException rejectNextCommit;

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
      super.commitAsync(offsets, callback);
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
