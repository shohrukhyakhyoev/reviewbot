package dev.reviewbot.worker.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import dev.reviewbot.shared.envelope.EnvelopeSerde;
import dev.reviewbot.shared.envelope.EnvelopeValidationException;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.failure.FailureClassifier;
import dev.reviewbot.worker.engine.CoalescePlan.Job;
import dev.reviewbot.worker.engine.CoalescePlan.Parsed;
import dev.reviewbot.worker.engine.CoalescePlan.Poison;

/** Pipeline step 0 (§7.1): one PR's drained batch in, a {@link CoalescePlan} out. Pure, no I/O. */
public final class Coalescer {

  private static final Comparator<Job> BY_OFFSET =
      Comparator.comparingLong(job -> job.record().offset());

  private record GroupResult(List<Job> jobs, List<ConsumerRecord<String, byte[]>> superseded) {}

  private Coalescer() {}

  public static CoalescePlan coalesce(List<ConsumerRecord<String, byte[]>> batch) {
    List<Poison> poison = new ArrayList<>();
    Map<EventType, List<Job>> validByType = new EnumMap<>(EventType.class);
    for (ConsumerRecord<String, byte[]> record : batch) {
      switch (parse(record)) {
        case Poison bad -> poison.add(bad);
        case Job job -> validByType.computeIfAbsent(job.type(), type -> new ArrayList<>()).add(job);
      }
    }

    List<Job> jobs = new ArrayList<>();
    List<ConsumerRecord<String, byte[]>> superseded = new ArrayList<>();
    for (Map.Entry<EventType, List<Job>> group : validByType.entrySet()) {
      GroupResult result = applyRule(group.getKey(), group.getValue());
      jobs.addAll(result.jobs());
      superseded.addAll(result.superseded());
    }
    return new CoalescePlan(poison, superseded, jobs);
  }

  private static Parsed parse(ConsumerRecord<String, byte[]> record) {
    try {
      return new Job(record, EnvelopeSerde.fromBytes(record.value()));
    } catch (EnvelopeValidationException e) {
      return new Poison(record, FailureClassifier.classify(e));
    }
  }

  private static GroupResult applyRule(EventType type, List<Job> group) {
    return switch (type) {
      case REVIEW_REQUESTED -> collapseToNewest(group);
    };
  }

  private static GroupResult collapseToNewest(List<Job> reviewRequests) {
    Job newest = reviewRequests.stream().max(BY_OFFSET).orElseThrow();
    List<ConsumerRecord<String, byte[]>> older =
        reviewRequests.stream()
            .map(Job::record)
            .filter(record -> record != newest.record())
            .toList();
    return new GroupResult(List.of(newest), older);
  }
}
