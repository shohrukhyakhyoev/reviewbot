package dev.reviewbot.worker.engine;

import static java.util.Comparator.comparingLong;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import dev.reviewbot.shared.failure.Outcome;

/** What every engine instance in one test executed and completed; nanoTime is one clock per JVM. */
final class ExecutionLog {

  record RecordId(int partition, long offset) {

    static RecordId of(ConsumerRecord<?, ?> record) {
      return new RecordId(record.partition(), record.offset());
    }

    @Override
    public String toString() {
      return partition + "@" + offset;
    }
  }

  record Execution(
      String instance,
      String prKey,
      RecordId record,
      long threadId,
      long startNanos,
      long endNanos) {}

  record Completion(String instance, RecordId record, Outcome outcome) {}

  private final List<Execution> executions = new CopyOnWriteArrayList<>();
  private final List<Completion> completions = new CopyOnWriteArrayList<>();

  void executed(Execution execution) {
    executions.add(execution);
  }

  OutcomeHandler handlerFor(String instance) {
    return (record, outcome) ->
        completions.add(new Completion(instance, RecordId.of(record), outcome));
  }

  List<Execution> executions() {
    return List.copyOf(executions);
  }

  List<Completion> completions() {
    return List.copyOf(completions);
  }

  Set<RecordId> completed() {
    return completions.stream().map(Completion::record).collect(toSet());
  }

  long completionsBy(String instance) {
    return completions.stream().filter(c -> c.instance().equals(instance)).count();
  }

  Map<RecordId, Long> completionCounts() {
    return completions.stream().collect(groupingBy(Completion::record, counting()));
  }

  /**
   * I2 across instances: each execution of a PR that started before an earlier one of the same PR
   * ended, paired with the earlier run that ends last. Re-executing an offset later is allowed
   * (at-least-once); running beside another of the same PR is not.
   */
  static List<String> samePrOverlaps(List<Execution> executions) {
    var violations = new ArrayList<String>();
    byPrInStartOrder(executions)
        .forEach(
            (pr, runs) -> {
              Execution latestEnding = runs.getFirst();
              for (Execution next : runs.subList(1, runs.size())) {
                if (latestEnding.endNanos() > next.startNanos()) {
                  violations.add(overlapMessage(pr, latestEnding, next));
                }
                if (next.endNanos() > latestEnding.endNanos()) {
                  latestEnding = next;
                }
              }
            });
    return violations;
  }

  // How long the two ran side by side: about a revocationGrace points to a zombie, a few ms to a
  // real I2 break.
  private static String overlapMessage(String pr, Execution earlier, Execution later) {
    return String.format(
        Locale.ROOT,
        "%s: %s (%s) ran %.3f ms beside %s (%s)",
        pr,
        later.record(),
        later.instance(),
        (Math.min(earlier.endNanos(), later.endNanos()) - later.startNanos()) / 1e6,
        earlier.record(),
        earlier.instance());
  }

  /** Per-PR order for one instance with no rebalance: offsets rise in execution order. */
  static List<String> offsetOrderViolations(List<Execution> executions) {
    var violations = new ArrayList<String>();
    byPrInStartOrder(executions)
        .forEach(
            (pr, runs) -> {
              for (int i = 1; i < runs.size(); i++) {
                long previous = runs.get(i - 1).record().offset();
                long next = runs.get(i).record().offset();
                if (next <= previous) {
                  violations.add(pr + ": offset " + next + " ran after " + previous);
                }
              }
            });
    return violations;
  }

  private static Map<String, List<Execution>> byPrInStartOrder(List<Execution> executions) {
    return executions.stream()
        .sorted(comparingLong(Execution::startNanos))
        .collect(groupingBy(Execution::prKey, LinkedHashMap::new, toList()));
  }
}
