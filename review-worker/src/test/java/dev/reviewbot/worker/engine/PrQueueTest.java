package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.Topics;

class PrQueueTest {

  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);

  private static TrackedRecord tracked(long offset) {
    var record =
        new ConsumerRecord<String, byte[]>(
            Topics.REVIEW_REQUESTED, 0, offset, "12345:acme/app:17", new byte[0]);
    return new TrackedRecord(record, new TrackedOffset(P0, 1, offset));
  }

  private static List<Long> offsets(List<TrackedRecord> batch) {
    return batch.stream().map(tr -> tr.trackedOffset().offset()).toList();
  }

  @Test
  void newQueueIsEmptyAndDrainsNothing() {
    var queue = new PrQueue();

    assertThat(queue.isEmpty()).isTrue();
    assertThat(queue.drainAll()).isEmpty();
  }

  @Test
  void drainAllReturnsEverythingInArrivalOrderAndEmptiesTheQueue() {
    var queue = new PrQueue();
    queue.add(tracked(7));
    queue.add(tracked(3));
    queue.add(tracked(5));

    assertThat(queue.isEmpty()).isFalse();
    assertThat(offsets(queue.drainAll())).containsExactly(7L, 3L, 5L);
    assertThat(queue.isEmpty()).isTrue();
    assertThat(queue.drainAll()).isEmpty();
  }

  @Test
  void aRecordAddedAfterADrainArrivesInTheNextDrainOnly() {
    var queue = new PrQueue();
    queue.add(tracked(1));
    List<TrackedRecord> first = queue.drainAll();

    queue.add(tracked(2));

    assertThat(offsets(first)).containsExactly(1L);
    assertThat(offsets(queue.drainAll())).containsExactly(2L);
  }

  @Test
  void concurrentAddsAreEachDrainedExactlyOnceInPerProducerOrder() throws InterruptedException {
    int producers = 8;
    int perProducer = 20_000;
    var queue = new PrQueue();
    var start = new CountDownLatch(1);
    var producing = new CountDownLatch(producers);
    var threads = new ArrayList<Thread>();
    for (int p = 0; p < producers; p++) {
      long base = (long) p * perProducer;
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    awaitQuietly(start);
                    for (long i = 0; i < perProducer; i++) {
                      queue.add(tracked(base + i));
                    }
                    producing.countDown();
                  }));
    }

    var drained = new ArrayList<Long>();
    start.countDown();
    // Checked before the drain, so the last drain starts after every add has finished.
    boolean producersFinished;
    do {
      producersFinished = producing.getCount() == 0;
      drained.addAll(offsets(queue.drainAll()));
    } while (!producersFinished);
    for (Thread t : threads) {
      t.join();
    }

    assertThat(drained).hasSize(producers * perProducer);
    assertThat(new HashSet<>(drained)).hasSize(producers * perProducer);
    long[] lastPerProducer = new long[producers];
    Arrays.fill(lastPerProducer, -1);
    for (long offset : drained) {
      int producer = (int) (offset / perProducer);
      assertThat(offset).isGreaterThan(lastPerProducer[producer]);
      lastPerProducer[producer] = offset;
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
