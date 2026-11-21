package dev.reviewbot.worker.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import dev.reviewbot.shared.failure.FailureClassifier;
import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.shared.keys.PrKey;
import dev.reviewbot.worker.engine.CoalescePlan.Job;
import dev.reviewbot.worker.engine.CoalescePlan.Poison;

/** One PR's drainer (§8.3): drains, coalesces, executes and completes until it can retire. */
final class Drainer implements Runnable {

  private final PrKey key;
  private final PrQueue queue;
  private final Retirer retirer;
  private final JobExecutor jobs;
  private final OutcomeHandler outcomes;
  private final Function<List<ConsumerRecord<String, byte[]>>, CoalescePlan> coalescer;
  private final OffsetTracker tracker;
  private final EngineMetrics metrics;

  private Drainer(
      PrKey key,
      PrQueue queue,
      Retirer retirer,
      JobExecutor jobs,
      OutcomeHandler outcomes,
      Function<List<ConsumerRecord<String, byte[]>>, CoalescePlan> coalescer,
      OffsetTracker tracker,
      EngineMetrics metrics) {
    this.key = key;
    this.queue = queue;
    this.retirer = retirer;
    this.jobs = jobs;
    this.outcomes = outcomes;
    this.coalescer = coalescer;
    this.tracker = tracker;
    this.metrics = metrics;
  }

  static DrainerFactory factory(
      JobExecutor jobs, OutcomeHandler outcomes, OffsetTracker tracker, EngineMetrics metrics) {
    return factory(jobs, outcomes, tracker, metrics, Coalescer::coalesce);
  }

  static DrainerFactory factory(
      JobExecutor jobs,
      OutcomeHandler outcomes,
      OffsetTracker tracker,
      EngineMetrics metrics,
      Function<List<ConsumerRecord<String, byte[]>>, CoalescePlan> coalescer) {
    EngineMetrics safeMetrics = SafeEngineMetrics.wrap(metrics);
    return (key, queue, retirer) ->
        new Drainer(key, queue, retirer, jobs, outcomes, coalescer, tracker, safeMetrics);
  }

  @Override
  public void run() {
    while (true) {
      List<TrackedRecord> batch = queue.drainAll();
      if (batch.isEmpty()) {
        if (retirer.tryRetire(key)) {
          return;
        }
        continue;
      }
      process(batch);
    }
  }

  private void process(List<TrackedRecord> batch) {
    List<Step> steps;
    try {
      steps = plan(batch);
    } catch (Throwable planFailure) {
      // Nothing in the batch is complete yet, so the failure becomes every record's one outcome.
      Outcome outcome = FailureClassifier.classify(planFailure);
      batch.forEach(tracked -> complete(tracked, outcome));
      return;
    }
    for (Step step : steps) {
      complete(step.tracked(), step.outcome().get());
    }
  }

  /** One record's completion, its outcome computed only when the step runs (jobs execute then). */
  private record Step(TrackedRecord tracked, Supplier<Outcome> outcome) {}

  // Every receipt is looked up before anything completes, so a coalescer slip fails the whole
  // batch cleanly instead of halfway through it.
  private List<Step> plan(List<TrackedRecord> batch) {
    CoalescePlan plan = coalesce(batch);
    IdentityHashMap<ConsumerRecord<String, byte[]>, TrackedRecord> trackedByRecord =
        byRecord(batch);
    List<Step> steps = new ArrayList<>();
    for (Poison bad : plan.poison()) {
      steps.add(new Step(trackedFor(trackedByRecord, bad.record()), bad::outcome));
    }
    for (ConsumerRecord<String, byte[]> stale : plan.superseded()) {
      steps.add(
          new Step(trackedFor(trackedByRecord, stale), () -> CoalescePlan.SUPERSEDED_OUTCOME));
    }
    for (Job job : plan.jobs()) {
      steps.add(new Step(trackedFor(trackedByRecord, job.record()), () -> execute(job)));
    }
    return steps;
  }

  private static TrackedRecord trackedFor(
      IdentityHashMap<ConsumerRecord<String, byte[]>, TrackedRecord> trackedByRecord,
      ConsumerRecord<String, byte[]> record) {
    return Objects.requireNonNull(
        trackedByRecord.get(record), "coalescer returned a record it was not given");
  }

  private CoalescePlan coalesce(List<TrackedRecord> batch) {
    return coalescer.apply(batch.stream().map(TrackedRecord::record).toList());
  }

  /** The coalescer hands back the very record objects it was given, so identity finds each one. */
  private static IdentityHashMap<ConsumerRecord<String, byte[]>, TrackedRecord> byRecord(
      List<TrackedRecord> batch) {
    var index = new IdentityHashMap<ConsumerRecord<String, byte[]>, TrackedRecord>();
    batch.forEach(tracked -> index.put(tracked.record(), tracked));
    return index;
  }

  // Throwable, not Exception: an Error such as StackOverflowError must not kill the loop and strand
  // the PR's queue without a drainer (I5). OOM exits the JVM first (-XX:+ExitOnOutOfMemoryError).
  private Outcome execute(Job job) {
    try {
      return jobs.execute(job);
    } catch (Throwable jobFailure) {
      return FailureClassifier.classify(jobFailure);
    }
  }

  /** The only place a record leaves the engine: one {@code markComplete}, handler or not. */
  private void complete(TrackedRecord done, Outcome outcome) {
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
