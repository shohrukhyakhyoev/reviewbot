package dev.reviewbot.worker.engine;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;
import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.failure.Outcome;

/**
 * Step 0's verdict on one drained batch: every input record sits in exactly one slot (I5).
 *
 * <p>A superseded record is finished at once with {@link #SUPERSEDED_OUTCOME} and {@code
 * markComplete}: no DB row, and never a dedup marker, which means reviewed-and-posted only (P1-1).
 */
public record CoalescePlan(
    List<Poison> poison, List<ConsumerRecord<String, byte[]>> superseded, List<Job> jobs) {

  public static final Outcome.Skip SUPERSEDED_OUTCOME = new Outcome.Skip(FailureClass.SUPERSEDED);

  public CoalescePlan {
    poison = List.copyOf(poison);
    superseded = List.copyOf(superseded);
    jobs = List.copyOf(jobs);
  }

  /** What one record becomes once its bytes are read: a job candidate or poison. */
  sealed interface Parsed permits Job, Poison {}

  /** The raw record is kept because the RAW DLQ capture needs its key and headers (E1-3). */
  public record Poison(ConsumerRecord<String, byte[]> record, Outcome outcome) implements Parsed {}

  public record Job(ConsumerRecord<String, byte[]> record, ReviewRequestedEnvelope envelope)
      implements Parsed {

    public EventType type() {
      return envelope.type();
    }
  }
}
