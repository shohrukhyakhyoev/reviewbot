package dev.reviewbot.worker.engine;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.worker.engine.CoalescePlan.Job;
import dev.reviewbot.worker.engine.ExecutionLog.Execution;
import dev.reviewbot.worker.engine.ExecutionLog.RecordId;

/**
 * Stands in for E5's pipeline: a seeded per-record latency, an optional injected failure, a gate
 * that holds chosen jobs, and an entry in the shared execution log for every run.
 */
final class StubJobExecutor implements JobExecutor {

  private final String instance;
  private final long seed;
  private final Map<Integer, Long> startOffsets;
  private final int maxLatencyMillis;
  private final int failOneIn;
  private final ExecutionLog log;
  private final CountDownLatch gate = new CountDownLatch(1);
  private final Set<RecordId> started = ConcurrentHashMap.newKeySet();
  private final Set<RecordId> parked = ConcurrentHashMap.newKeySet();
  private volatile Predicate<RecordId> held = id -> false;
  private volatile boolean dead;
  private volatile Duration slowLatency;

  StubJobExecutor(
      String instance,
      long seed,
      Map<Integer, Long> startOffsets,
      int maxLatencyMillis,
      int failOneIn,
      ExecutionLog log) {
    this.instance = instance;
    this.seed = seed;
    this.startOffsets = startOffsets;
    this.maxLatencyMillis = maxLatencyMillis;
    this.failOneIn = failOneIn;
    this.log = log;
  }

  @Override
  public Outcome execute(Job job) {
    var id = RecordId.of(job.record());
    if (dead) {
      // A killed process runs nothing: park unlogged until the test tears down.
      awaitGate();
      return new Outcome.Success();
    }
    started.add(id);
    if (held.test(id)) {
      parked.add(id);
      awaitGate();
      parked.remove(id);
    }
    return work(job, id);
  }

  // The logged interval starts after any hold: a parked job has not begun its work yet.
  private Outcome work(Job job, RecordId id) {
    long start = System.nanoTime();
    var random = randomFor(id);
    try {
      sleep(latency(random));
      if (failOneIn > 0 && random.nextInt(failOneIn) == 0) {
        throw new IllegalStateException("injected stub failure at " + id);
      }
      return new Outcome.Success();
    } finally {
      log.executed(executionEndingNow(job, id, start));
    }
  }

  /** Jobs starting from now take {@code latency} plus up to half of it, until {@link #speedUp}. */
  void slowDown(Duration latency) {
    slowLatency = latency;
  }

  void speedUp() {
    slowLatency = null;
  }

  private Duration latency(SplittableRandom random) {
    var slow = slowLatency;
    return slow == null
        ? Duration.ofMillis(random.nextInt(maxLatencyMillis + 1))
        : slow.plusMillis(random.nextLong(slow.toMillis() / 2 + 1));
  }

  void hold(Predicate<RecordId> which) {
    held = which;
  }

  void openGate() {
    gate.countDown();
  }

  /** From now on nothing new runs, as if the process were gone. */
  void kill() {
    dead = true;
  }

  boolean hasStarted(RecordId id) {
    return started.contains(id);
  }

  Set<RecordId> parked() {
    return Set.copyOf(parked);
  }

  // Keyed by the offset within this test, not the absolute one: earlier tests shift absolute
  // offsets, and a logged seed must replay the same latencies and failures.
  private SplittableRandom randomFor(RecordId id) {
    long offsetInTest = id.offset() - startOffsets.get(id.partition());
    return new SplittableRandom(seed ^ (id.partition() * 1_000_003L + offsetInTest));
  }

  private Execution executionEndingNow(Job job, RecordId id, long startNanos) {
    return new Execution(
        instance,
        job.record().key(),
        id,
        Thread.currentThread().threadId(),
        startNanos,
        System.nanoTime());
  }

  private void awaitGate() {
    try {
      gate.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleep(Duration latency) {
    try {
      Thread.sleep(latency);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
