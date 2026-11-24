package dev.reviewbot.worker.stub;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.worker.engine.OutcomeHandler;

/** Stand-in until E5/E6 act on outcomes (DB, retry, DLQ): logs the outcome type only. */
public final class LoggingOutcomeHandler implements OutcomeHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingOutcomeHandler.class);

  @Override
  public void handle(ConsumerRecord<String, byte[]> record, Outcome outcome) {
    LOG.info(
        "stub outcome partition={} offset={} outcome={}",
        record.partition(),
        record.offset(),
        outcome.getClass().getSimpleName());
  }
}
