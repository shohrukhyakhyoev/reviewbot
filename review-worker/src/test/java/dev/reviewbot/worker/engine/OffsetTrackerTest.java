package dev.reviewbot.worker.engine;

import static dev.reviewbot.worker.engine.Committables.offsetsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.Topics;

class OffsetTrackerTest {

  private static final int CAP = 200;
  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);
  private static final TopicPartition P1 = new TopicPartition(Topics.REVIEW_REQUESTED, 1);

  private final Semaphore inFlight = new Semaphore(CAP);
  private final OffsetTracker tracker = new OffsetTracker(inFlight);

  private void complete(TopicPartition tp, long offset) {
    tracker.markComplete(tracker.track(tp, offset));
  }

  @Test
  void unregisteredPartitionHasNoWatermark() {
    assertThat(tracker.watermark(P0)).isEmpty();
  }

  @Test
  void registerSetsWatermarkJustBelowPosition() {
    tracker.register(P0, 50);

    assertThat(tracker.watermark(P0)).hasValue(49);
  }

  @Test
  void registerAtPositionZeroStartsBelowTheFirstOffset() {
    tracker.register(P0, 0);

    assertThat(tracker.watermark(P0)).hasValue(-1);
  }

  @Test
  void reRegisterReplacesTheBaseline() {
    tracker.register(P0, 50);
    tracker.register(P0, 80);

    assertThat(tracker.watermark(P0)).hasValue(79);
  }

  @Test
  void trackStampsTheCurrentGeneration() {
    tracker.register(P0, 50);
    var first = tracker.track(P0, 50);
    tracker.register(P0, 50);
    var second = tracker.track(P0, 50);
    tracker.register(P1, 7);
    var other = tracker.track(P1, 7);

    assertThat(first.generation()).isNotEqualTo(second.generation());
    assertThat(other.generation()).isNotIn(first.generation(), second.generation());
    assertThat(second).isEqualTo(new TrackedOffset(P0, second.generation(), 50));
  }

  @Test
  void trackOnAnUnregisteredPartitionIsABug() {
    assertThatIllegalStateException().isThrownBy(() -> tracker.track(P0, 50));
  }

  @Test
  void trackAtOrBelowTheWatermarkIsABug() {
    tracker.register(P0, 50);
    complete(P0, 50);

    assertThatIllegalStateException().isThrownBy(() -> tracker.track(P0, 50));
    assertThatIllegalStateException().isThrownBy(() -> tracker.track(P0, 3));
  }

  @Test
  void completingAnAdvancedOffsetTwiceFailsWithoutASecondPermit() throws InterruptedException {
    tracker.register(P0, 50);
    var once = tracker.track(P0, 50);
    inFlight.acquire();
    tracker.markComplete(once);

    assertThatIllegalStateException().isThrownBy(() -> tracker.markComplete(once));

    assertThat(inFlight.availablePermits()).isEqualTo(CAP);
    assertThat(tracker.watermark(P0)).hasValue(50);
  }

  @Test
  void completingAParkedOffsetTwiceFailsWithoutASecondPermit() throws InterruptedException {
    tracker.register(P0, 50);
    var parked = tracker.track(P0, 52);
    inFlight.acquire();
    tracker.markComplete(parked);

    assertThatIllegalStateException().isThrownBy(() -> tracker.markComplete(parked));

    assertThat(inFlight.availablePermits()).isEqualTo(CAP);
    complete(P0, 50);
    complete(P0, 51);
    assertThat(tracker.watermark(P0)).hasValue(52);
  }

  @Test
  void nextOffsetAdvancesTheWatermark() {
    tracker.register(P0, 50);

    complete(P0, 50);

    assertThat(tracker.watermark(P0)).hasValue(50);
  }

  @Test
  void offsetAboveAGapParksAndTheWatermarkStays() {
    tracker.register(P0, 50);

    complete(P0, 52);

    assertThat(tracker.watermark(P0)).hasValue(49);
  }

  @Test
  void closingTheGapAbsorbsEveryAdjacentIsland() {
    tracker.register(P0, 10);
    complete(P0, 12);
    complete(P0, 13);
    complete(P0, 11);

    complete(P0, 10);

    assertThat(tracker.watermark(P0)).hasValue(13);
  }

  @Test
  void absorptionStopsAtTheNextGap() {
    tracker.register(P0, 10);
    complete(P0, 12);
    complete(P0, 13);
    complete(P0, 15);

    complete(P0, 11);
    assertThat(tracker.watermark(P0)).hasValue(9);

    complete(P0, 10);
    assertThat(tracker.watermark(P0)).hasValue(13);

    complete(P0, 14);
    assertThat(tracker.watermark(P0)).hasValue(15);
  }

  @Test
  void reRegisterDiscardsStaleIslands() {
    tracker.register(P0, 10);
    complete(P0, 12);

    tracker.register(P0, 10);
    complete(P0, 10);
    complete(P0, 11);

    assertThat(tracker.watermark(P0)).hasValue(11);
  }

  @Test
  void everyCompletionReturnsOnePermitEvenWhenItParks() throws InterruptedException {
    tracker.register(P0, 50);
    inFlight.acquire(3);

    complete(P0, 52);
    assertThat(inFlight.availablePermits()).isEqualTo(CAP - 2);

    complete(P0, 51);
    assertThat(inFlight.availablePermits()).isEqualTo(CAP - 1);

    complete(P0, 50);
    assertThat(inFlight.availablePermits()).isEqualTo(CAP);
    assertThat(tracker.watermark(P0)).hasValue(52);
  }

  @Test
  void completeOnUnknownPartitionOnlyReturnsThePermit() throws InterruptedException {
    inFlight.acquire();

    tracker.markComplete(new TrackedOffset(P1, 1, 5));

    assertThat(inFlight.availablePermits()).isEqualTo(CAP);
    assertThat(tracker.watermark(P1)).isEmpty();
  }

  @Test
  void unregisterRemovesOnlyTheRevokedPartitions() {
    tracker.register(P0, 50);
    tracker.register(P1, 7);

    tracker.unregister(List.of(P0));

    assertThat(tracker.watermark(P0)).isEmpty();
    assertThat(tracker.watermark(P1)).hasValue(6);
  }

  @Test
  void unregisterOfAnUnknownPartitionIsHarmless() {
    tracker.register(P1, 7);

    tracker.unregister(List.of(P0));

    assertThat(tracker.watermark(P1)).hasValue(6);
  }

  @Test
  void completeAfterUnregisterOnlyReturnsThePermit() throws InterruptedException {
    tracker.register(P0, 50);
    inFlight.acquire(2);
    complete(P0, 50);
    var zombie = tracker.track(P0, 51);

    tracker.unregister(List.of(P0));
    tracker.markComplete(zombie);

    assertThat(inFlight.availablePermits()).isEqualTo(CAP);
    assertThat(tracker.watermark(P0)).isEmpty();
  }

  @Test
  void reRegisterAfterUnregisterStartsFromTheNewPosition() {
    tracker.register(P0, 10);
    complete(P0, 12);
    tracker.unregister(List.of(P0));

    tracker.register(P0, 10);
    complete(P0, 10);
    complete(P0, 11);

    assertThat(tracker.watermark(P0)).hasValue(11);
  }

  @Test
  void zombieFromAnEarlierGenerationCannotAdvanceTheReassignedPartition()
      throws InterruptedException {
    tracker.register(P0, 10);
    var zombie10 = tracker.track(P0, 10);
    var zombie11 = tracker.track(P0, 11);
    inFlight.acquire(4);
    tracker.unregister(List.of(P0));
    tracker.register(P0, 10);
    var new10 = tracker.track(P0, 10);
    var new11 = tracker.track(P0, 11);

    tracker.markComplete(zombie11);
    tracker.markComplete(new10);

    assertThat(tracker.watermark(P0)).hasValue(10);
    assertThat(offsetsOf(tracker.committable())).containsExactly(entry(P0, 10L));

    tracker.markComplete(zombie10);
    tracker.markComplete(new11);

    assertThat(tracker.watermark(P0)).hasValue(11);
    assertThat(inFlight.availablePermits()).isEqualTo(CAP);
  }

  @Test
  void freshRegistrationIsNotCommittable() {
    tracker.register(P0, 50);

    assertThat(tracker.committable()).isEmpty();
  }

  @Test
  void anAdvanceIsReportedUntilItsCommitIsConfirmed() {
    tracker.register(P0, 50);
    complete(P0, 50);
    complete(P0, 51);

    var advanced = tracker.committable();
    assertThat(offsetsOf(advanced)).containsExactly(entry(P0, 51L));
    assertThat(tracker.committable()).isEqualTo(advanced);

    tracker.markCommitted(advanced);
    assertThat(tracker.committable()).isEmpty();
  }

  @Test
  void anUnconfirmedCommitIsReportedAgainAtRevocation() {
    tracker.register(P0, 50);
    complete(P0, 50);
    complete(P0, 51);
    tracker.committable();

    assertThat(offsetsOf(tracker.committable(List.of(P0)))).containsExactly(entry(P0, 51L));
  }

  @Test
  void aLateOlderConfirmationNeverUndoesANewerOne() {
    tracker.register(P0, 50);
    complete(P0, 50);
    var older = tracker.committable();
    complete(P0, 51);
    var newer = tracker.committable();

    tracker.markCommitted(newer);
    tracker.markCommitted(older);

    assertThat(tracker.committable()).isEmpty();
  }

  @Test
  void aConfirmationBeyondTheWatermarkIsABug() {
    tracker.register(P0, 50);
    complete(P0, 50);
    var reported = tracker.committable().get(P0);
    var plusOne = new TrackedOffset(P0, reported.generation(), reported.offset() + 1);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> tracker.markCommitted(Map.of(P0, plusOne)));
    assertThat(offsetsOf(tracker.committable())).containsExactly(entry(P0, 50L));
  }

  @Test
  void aConfirmationForAnUnknownPartitionIsIgnored() {
    tracker.register(P1, 7);
    complete(P1, 7);

    tracker.markCommitted(Map.of(P0, new TrackedOffset(P0, 1, 5)));

    assertThat(tracker.watermark(P0)).isEmpty();
    assertThat(offsetsOf(tracker.committable())).containsExactly(entry(P1, 7L));
  }

  @Test
  void aConfirmationFromAnEarlierRegistrationAboveTheNewWatermarkIsIgnored() {
    tracker.register(P0, 50);
    complete(P0, 50);
    complete(P0, 51);
    var fromFirstRegistration = tracker.committable();
    tracker.unregister(List.of(P0));
    tracker.register(P0, 48);
    complete(P0, 48);

    tracker.markCommitted(fromFirstRegistration);

    assertThat(offsetsOf(tracker.committable())).containsExactly(entry(P0, 48L));
  }

  @Test
  void aConfirmationFromAnEarlierRegistrationNeverHidesACommitStillOwed() {
    tracker.register(P0, 50);
    complete(P0, 50);
    var fromFirstRegistration = tracker.committable();
    tracker.unregister(List.of(P0));
    tracker.register(P0, 49);
    complete(P0, 49);
    complete(P0, 50);

    tracker.markCommitted(fromFirstRegistration);

    assertThat(offsetsOf(tracker.committable())).containsExactly(entry(P0, 50L));
  }

  @Test
  void aParkedIslandAloneIsNotCommittable() {
    tracker.register(P0, 50);
    complete(P0, 52);

    assertThat(tracker.committable()).isEmpty();
  }

  @Test
  void aFurtherAdvanceIsReportedAgain() {
    tracker.register(P0, 50);
    complete(P0, 50);
    tracker.markCommitted(tracker.committable());

    complete(P0, 51);

    assertThat(offsetsOf(tracker.committable())).containsExactly(entry(P0, 51L));
  }

  @Test
  void onlyAdvancedPartitionsAreReported() {
    tracker.register(P0, 50);
    tracker.register(P1, 7);
    complete(P1, 7);

    assertThat(offsetsOf(tracker.committable())).containsExactly(entry(P1, 7L));
  }

  @Test
  void zombieCompletionNeverSurfacesInCommittable() throws InterruptedException {
    tracker.register(P0, 50);
    var zombie = tracker.track(P0, 50);
    inFlight.acquire();
    tracker.unregister(List.of(P0));

    tracker.markComplete(zombie);

    assertThat(inFlight.availablePermits()).isEqualTo(CAP);
    assertThat(tracker.committable()).isEmpty();
  }

  @Test
  void committableSubsetReportsOnlyTheNamedAdvancedPartitions() {
    tracker.register(P0, 50);
    tracker.register(P1, 7);
    complete(P0, 50);
    complete(P1, 7);

    assertThat(offsetsOf(tracker.committable(List.of(P0)))).containsExactly(entry(P0, 50L));
    assertThat(offsetsOf(tracker.committable())).containsOnly(entry(P0, 50L), entry(P1, 7L));
  }

  @Test
  void committableSubsetSkipsUnknownPartitions() {
    tracker.register(P1, 7);
    complete(P1, 7);

    assertThat(tracker.committable(List.of(P0))).isEmpty();
  }

  @Test
  void registerLeavesOtherPartitionsAlone() {
    tracker.register(P0, 50);
    tracker.register(P1, 7);

    assertThat(tracker.watermark(P0)).hasValue(49);
    assertThat(tracker.watermark(P1)).hasValue(6);
  }
}
