package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.reviewbot.worker.engine.ExecutionLog.Execution;
import dev.reviewbot.worker.engine.ExecutionLog.RecordId;

class ExecutionLogTest {

  private static final long MS = 1_000_000;

  private static Execution run(String pr, long offset, String instance, long startMs, long endMs) {
    return new Execution(instance, pr, new RecordId(0, offset), 1, startMs * MS, endMs * MS);
  }

  @Test
  void samePrWorkThatOverlapsInTimeIsReportedAcrossInstancesWithTheOverlapLength() {
    var log = List.of(run("pr-7", 15, "A", 1000, 1090), run("pr-7", 21, "B", 1080, 1120));

    assertThat(ExecutionLog.samePrOverlaps(log))
        .containsExactly("pr-7: 0@21 (B) ran 10.000 ms beside 0@15 (A)");
  }

  // Touching intervals (end == next start) are serial, and other PRs may run at the same time.
  @Test
  void touchingRunsAndParallelRunsOfDifferentPrsAreNotOverlaps() {
    var log =
        List.of(
            run("pr-9", 40, "A", 500, 600),
            run("pr-9", 44, "A", 600, 650),
            run("pr-3", 41, "A", 520, 640));

    assertThat(ExecutionLog.samePrOverlaps(log)).isEmpty();
  }

  @Test
  void aLongRunIsReportedAgainstEveryLaterRunItCovers() {
    var log =
        List.of(
            run("pr-5", 1, "A", 0, 1000),
            run("pr-5", 2, "B", 100, 200),
            run("pr-5", 3, "B", 300, 400));

    assertThat(ExecutionLog.samePrOverlaps(log))
        .containsExactly(
            "pr-5: 0@2 (B) ran 100.000 ms beside 0@1 (A)",
            "pr-5: 0@3 (B) ran 100.000 ms beside 0@1 (A)");
  }

  @Test
  void offsetsRisingPerPrAreInOrderEvenWhenPrsInterleave() {
    var log =
        List.of(
            run("pr-1", 10, "A", 0, 10),
            run("pr-2", 4, "A", 5, 15),
            run("pr-1", 12, "A", 20, 30),
            run("pr-2", 9, "A", 25, 35));

    assertThat(ExecutionLog.offsetOrderViolations(log)).isEmpty();
  }

  // Equal offsets count too: the same offset running twice on one instance is a redelivery the
  // no-rebalance scenario must not have.
  @Test
  void aLowerOrRepeatedOffsetRunningLaterForThePrIsReported() {
    var log =
        List.of(
            run("pr-1", 12, "A", 0, 10),
            run("pr-1", 10, "A", 20, 30),
            run("pr-1", 10, "A", 40, 50));

    assertThat(ExecutionLog.offsetOrderViolations(log))
        .containsExactly("pr-1: offset 10 ran after 12", "pr-1: offset 10 ran after 10");
  }
}
