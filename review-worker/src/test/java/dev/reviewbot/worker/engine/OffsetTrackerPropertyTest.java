package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.LongStream;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.reviewbot.shared.config.Topics;

/** I3, I4 and I7 over random offsets, orders and reassignments; a failing seed reproduces. */
class OffsetTrackerPropertyTest {

  private static final TopicPartition TP = new TopicPartition(Topics.REVIEW_REQUESTED, 0);

  static LongStream seeds() {
    return LongStream.range(0, 500);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void watermarkIsAlwaysTheContiguousPrefixAndPermitsAreConserved(long seed) {
    var random = new Random(seed);
    long position = random.nextInt(10_000);
    int count = 1 + random.nextInt(300);
    var order = shuffledRange(position, count, random);

    var inFlight = new Semaphore(0);
    var tracker = new OffsetTracker(inFlight);
    tracker.register(TP, position);

    Set<Long> done = new HashSet<>();
    long lastCommittable = position - 1;
    for (long offset : order) {
      tracker.markComplete(tracker.track(TP, offset));
      done.add(offset);
      long prefix = contiguousPrefix(position, done);

      assertThat(tracker.watermark(TP)).hasValue(prefix);
      var reported = tracker.committable().get(TP);
      if (reported != null) {
        assertThat(reported.offset()).isEqualTo(prefix).isGreaterThan(lastCommittable);
        lastCommittable = reported.offset();
        tracker.markCommitted(Map.of(TP, reported));
      }
    }

    assertThat(tracker.watermark(TP)).hasValue(position + count - 1);
    assertThat(inFlight.availablePermits()).isEqualTo(count);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void lateZombiesAfterAReassignmentNeverMoveTheNewWatermark(long seed) {
    var random = new Random(seed);
    long position = random.nextInt(10_000);
    int count = 1 + random.nextInt(300);
    var inFlight = new Semaphore(0);
    var tracker = new OffsetTracker(inFlight);
    tracker.register(TP, position);

    var firstOwner = new ArrayList<TrackedOffset>();
    for (long o = position; o < position + count; o++) {
      firstOwner.add(tracker.track(TP, o));
    }
    Collections.shuffle(firstOwner, random);
    int finishedBeforeRevoke = random.nextInt(count);
    firstOwner.subList(0, finishedBeforeRevoke).forEach(tracker::markComplete);
    var zombies = new HashSet<>(firstOwner.subList(finishedBeforeRevoke, count));

    long resumeAt = tracker.watermark(TP).orElseThrow() + 1;
    tracker.unregister(List.of(TP));
    tracker.register(TP, resumeAt);
    var interleaved = new ArrayList<TrackedOffset>(zombies);
    for (long o = resumeAt; o < position + count; o++) {
      interleaved.add(tracker.track(TP, o));
    }
    Collections.shuffle(interleaved, random);

    Set<Long> doneBySecondOwner = new HashSet<>();
    for (var completion : interleaved) {
      tracker.markComplete(completion);
      if (!zombies.contains(completion)) {
        doneBySecondOwner.add(completion.offset());
      }
      assertThat(tracker.watermark(TP))
          .hasValue(contiguousPrefix(resumeAt, doneBySecondOwner));
    }

    assertThat(tracker.watermark(TP)).hasValue(position + count - 1);
    assertThat(inFlight.availablePermits()).isEqualTo(finishedBeforeRevoke + interleaved.size());
  }

  private static List<Long> shuffledRange(long from, int count, Random random) {
    var offsets = new ArrayList<Long>(count);
    for (long o = from; o < from + count; o++) {
      offsets.add(o);
    }
    Collections.shuffle(offsets, random);
    return offsets;
  }

  private static long contiguousPrefix(long position, Set<Long> done) {
    long prefix = position - 1;
    while (done.contains(prefix + 1)) {
      prefix++;
    }
    return prefix;
  }
}
