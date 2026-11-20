package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** I5's precondition over random batches: every record lands in exactly one slot. */
class CoalescerPropertyTest {

  static LongStream seeds() {
    return LongStream.range(0, 300);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void everyRecordLandsInExactlyOneSlotAndTheJobIsTheHighestValidOffset(long seed) {
    var random = new Random(seed);
    int count = random.nextInt(41);
    long offset = random.nextInt(10_000);
    List<ConsumerRecord<String, byte[]>> batch = new ArrayList<>();
    List<ConsumerRecord<String, byte[]>> poisonInputs = new ArrayList<>();
    long highestValid = -1;
    for (int i = 0; i < count; i++) {
      offset += 1 + random.nextInt(3);
      if (random.nextInt(10) < 3) {
        var bad = CoalescerTest.poison(offset);
        poisonInputs.add(bad);
        batch.add(bad);
      } else {
        batch.add(CoalescerTest.valid(offset));
        highestValid = offset;
      }
    }
    Collections.shuffle(batch, random);

    var plan = Coalescer.coalesce(batch);

    Map<ConsumerRecord<String, byte[]>, Integer> timesPlaced = new IdentityHashMap<>();
    Stream.of(CoalescerTest.poisonRecords(plan), plan.superseded(), CoalescerTest.jobRecords(plan))
        .flatMap(List::stream)
        .forEach(r -> timesPlaced.merge(r, 1, Integer::sum));

    assertThat(plan.poison().size() + plan.superseded().size() + plan.jobs().size())
        .isEqualTo(batch.size());
    assertThat(timesPlaced).hasSize(batch.size());
    assertThat(timesPlaced.values()).allMatch(n -> n == 1);
    assertThat(batch).allMatch(timesPlaced::containsKey);
    assertThat(CoalescerTest.poisonRecords(plan))
        .containsExactlyInAnyOrderElementsOf(poisonInputs);

    if (highestValid < 0) {
      assertThat(plan.jobs()).isEmpty();
    } else {
      assertThat(plan.jobs()).singleElement().extracting(job -> job.record().offset())
          .isEqualTo(highestValid);
    }
  }
}
