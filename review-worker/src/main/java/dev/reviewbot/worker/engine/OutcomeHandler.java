package dev.reviewbot.worker.engine;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import dev.reviewbot.shared.failure.Outcome;

/**
 * Acts on a record's outcome (§7.2: DB, retry, DLQ). It never sees the tracked offset: the drainer
 * calls {@code markComplete} afterwards, exactly once, whether this returns or throws (I4, I5).
 */
@FunctionalInterface
public interface OutcomeHandler {

  void handle(ConsumerRecord<String, byte[]> record, Outcome outcome);
}
