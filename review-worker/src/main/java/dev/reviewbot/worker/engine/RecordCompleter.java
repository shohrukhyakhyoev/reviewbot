package dev.reviewbot.worker.engine;

import dev.reviewbot.shared.failure.Outcome;

/** The only place a record leaves the engine: one {@code markComplete}, handler or not. */
final class RecordCompleter {

  private final OutcomeHandler outcomes;
  private final OffsetTracker tracker;
  private final EngineMetrics metrics;

  RecordCompleter(OutcomeHandler outcomes, OffsetTracker tracker, EngineMetrics metrics) {
    this.outcomes = outcomes;
    this.tracker = tracker;
    this.metrics = SafeEngineMetrics.wrap(metrics);
  }

  void complete(TrackedRecord done, Outcome outcome) {
    // E4-2 policy, not spec: §12 only says shutdown stops intake and waits; E4-3/E4-4 own what
    // happens after the wait (ADR candidate). Until then a stray interrupt is cleared, so the
    // handler's retry or DLQ produce cannot fail on it (I9), and reported, so it is never silent.
    if (Thread.interrupted()) {
      metrics.strayInterruptCleared(done.trackedOffset());
    }
    try {
      outcomes.handle(done.record(), outcome);
    } catch (Throwable handlerFailure) {
      metrics.outcomeHandlerFailed(done.trackedOffset(), handlerFailure);
    } finally {
      markComplete(done);
    }
  }

  // Never retried: it runs once per record, and a throwing markComplete has already settled the
  // permit in its finally (E4-0).
  private void markComplete(TrackedRecord done) {
    try {
      tracker.markComplete(done.trackedOffset());
    } catch (Throwable completionFailure) {
      metrics.markCompleteFailed(done.trackedOffset(), completionFailure);
    }
  }
}
