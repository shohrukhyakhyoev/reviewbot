package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.envelope.EnvelopeSerde;
import dev.reviewbot.shared.envelope.EnvelopeValidationException;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;
import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.worker.engine.CoalescePlan.Job;
import dev.reviewbot.worker.engine.CoalescePlan.Poison;

class CoalescerTest {

  private static final String KEY = "12345:acme/app:17";
  private static final Instant T0 = Instant.parse("2026-07-05T10:00:00Z");

  static ConsumerRecord<String, byte[]> valid(long offset, String headSha, Instant occurredAt) {
    var envelope =
        new ReviewRequestedEnvelope(
            1,
            "event-" + offset,
            "delivery-" + offset,
            EventType.REVIEW_REQUESTED,
            occurredAt,
            12345L,
            "acme/app",
            17,
            headSha,
            false,
            false);
    return record(offset, EnvelopeSerde.toBytes(envelope));
  }

  static ConsumerRecord<String, byte[]> valid(long offset) {
    return valid(offset, "sha-" + offset, T0.plusSeconds(offset));
  }

  static ConsumerRecord<String, byte[]> poison(long offset) {
    return record(offset, "not json".getBytes(StandardCharsets.UTF_8));
  }

  static ConsumerRecord<String, byte[]> record(long offset, byte[] value) {
    return new ConsumerRecord<>(Topics.REVIEW_REQUESTED, 0, offset, KEY, value);
  }

  static List<ConsumerRecord<String, byte[]>> jobRecords(CoalescePlan plan) {
    return plan.jobs().stream().map(Job::record).toList();
  }

  static List<ConsumerRecord<String, byte[]>> poisonRecords(CoalescePlan plan) {
    return plan.poison().stream().map(Poison::record).toList();
  }

  @Test
  void emptyBatchGivesAnEmptyPlan() {
    var plan = Coalescer.coalesce(List.of());

    assertThat(plan.poison()).isEmpty();
    assertThat(plan.superseded()).isEmpty();
    assertThat(plan.jobs()).isEmpty();
  }

  @Test
  void singleValidRecordIsTheJobWithItsDecodedEnvelope() {
    var only = valid(7, "abc123", T0);

    var plan = Coalescer.coalesce(List.of(only));

    assertThat(plan.jobs()).singleElement().satisfies(job -> {
      assertThat(job.record()).isSameAs(only);
      assertThat(job.envelope().headSha()).isEqualTo("abc123");
    });
    assertThat(plan.superseded()).isEmpty();
    assertThat(plan.poison()).isEmpty();
  }

  @Test
  void burstForOnePrKeepsTheHighestOffsetAndSupersedesTheRest() {
    var r7 = valid(7);
    var r9 = valid(9);
    var r12 = valid(12);

    var plan = Coalescer.coalesce(List.of(r7, r9, r12));

    assertThat(jobRecords(plan)).containsExactly(r12);
    assertThat(plan.superseded()).containsExactlyInAnyOrder(r7, r9);
  }

  @Test
  void inputListOrderDoesNotChooseTheJob() {
    var r7 = valid(7);
    var r9 = valid(9);
    var r12 = valid(12);

    var plan = Coalescer.coalesce(List.of(r12, r7, r9));

    assertThat(jobRecords(plan)).containsExactly(r12);
  }

  // Offset 9 claims the later occurred_at (a fast gateway clock); arrival order still wins.
  @Test
  void highestOffsetWinsEvenWithAnOlderTimestamp() {
    var r9 = valid(9, "sha-9", T0.plusSeconds(60));
    var r12 = valid(12, "sha-12", T0);

    var plan = Coalescer.coalesce(List.of(r9, r12));

    assertThat(jobRecords(plan)).containsExactly(r12);
    assertThat(plan.superseded()).containsExactly(r9);
  }

  @Test
  void poisonIsIsolatedAndTheRemainderCoalesces() {
    var r7 = valid(7);
    var r9 = valid(9);
    var r12 = valid(12);
    var bad13 = poison(13);

    var plan = Coalescer.coalesce(List.of(r7, r9, r12, bad13));

    assertThat(poisonRecords(plan)).containsExactly(bad13);
    assertThat(jobRecords(plan)).containsExactly(r12);
    assertThat(plan.superseded()).containsExactlyInAnyOrder(r7, r9);
  }

  // One per way a value can fail: not JSON, null value (tombstone), empty bytes, a required
  // field missing after a clean bind.
  @Test
  void poisonOnlyBatchIsAllPoisonWithTheRecordAndItsCause() {
    var notJson = poison(1);
    var tombstone = record(2, null);
    var empty = record(3, new byte[0]);
    var noHeadSha = valid(4, null, T0);

    var plan = Coalescer.coalesce(List.of(notJson, tombstone, empty, noHeadSha));

    assertThat(poisonRecords(plan)).containsExactly(notJson, tombstone, empty, noHeadSha);
    assertThat(plan.poison())
        .allSatisfy(
            p ->
                assertThat(p.outcome())
                    .isInstanceOfSatisfying(
                        Outcome.Terminal.class,
                        terminal -> {
                          assertThat(terminal.failureClass())
                              .isEqualTo(FailureClass.POISON_DESERIALIZATION);
                          assertThat(terminal.exception())
                              .isInstanceOf(EnvelopeValidationException.class);
                        }));
    assertThat(plan.jobs()).isEmpty();
    assertThat(plan.superseded()).isEmpty();
  }
}
