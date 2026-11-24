package dev.reviewbot.worker.engine;

import dev.reviewbot.shared.envelope.EnvelopeValidationException;
import dev.reviewbot.shared.failure.FailureClassifier;
import dev.reviewbot.shared.failure.Outcome;

/** Builds the task for a record whose key names no PR, so no per-PR drainer can own it. */
@FunctionalInterface
interface BadKeyTasks {

  Runnable create(TrackedRecord record);

  static BadKeyTasks completingAsPoison(
      OutcomeHandler outcomes, OffsetTracker tracker, EngineMetrics metrics) {
    var completer = new RecordCompleter(outcomes, tracker, metrics);
    Outcome poison =
        FailureClassifier.classify(new EnvelopeValidationException("key is not a valid PR key"));
    return record -> () -> completer.complete(record, poison);
  }
}
