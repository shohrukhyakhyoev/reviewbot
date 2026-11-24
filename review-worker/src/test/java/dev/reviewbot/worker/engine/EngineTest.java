package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.worker.engine.CoalescePlan.Job;

class EngineTest {

  private static final TopicPartition P0 = new TopicPartition(Topics.REVIEW_REQUESTED, 0);
  private static final ReviewbotProperties.Consumer SETTINGS =
      new ReviewbotProperties.Consumer(
          Duration.ofSeconds(45),
          Duration.ofSeconds(3),
          Duration.ofSeconds(300),
          Duration.ofMillis(20),
          100,
          200,
          100,
          Duration.ofSeconds(30));

  private final ObservedConsumer consumer = new ObservedConsumer();
  private final ObservedDrainerThreads drainerThreads = new ObservedDrainerThreads(consumer);
  private final List<Job> executedJobs = new CopyOnWriteArrayList<>();
  private final List<Outcome> handledOutcomes = new CopyOnWriteArrayList<>();
  private final AtomicInteger pollerDeaths = new AtomicInteger();
  private final Engine engine =
      new Engine(
          consumer,
          job -> {
            executedJobs.add(job);
            return new Outcome.Success();
          },
          (record, outcome) -> handledOutcomes.add(outcome),
          new RecordingEngineMetrics(),
          SETTINGS,
          pollerDeaths::incrementAndGet,
          drainerThreads);

  @AfterEach
  void stopEngine() {
    engine.stop();
  }

  @Test
  void aStartedEngineRunsARecordThroughTheJobExecutorAndCommitsIt() {
    assignP0WithOneRecordOnFirstPoll();

    engine.start();

    await()
        .untilAsserted(
            () ->
                assertThat(consumer.committed(Set.of(P0)))
                    .containsEntry(P0, new OffsetAndMetadata(1)));
    assertThat(executedJobs).hasSize(1);
  }

  @Test
  void stopClosesTheConsumerOnThePollerThread() {
    assignP0WithOneRecordOnFirstPoll();
    engine.start();
    awaitPolls(2);

    stopAndAwaitPollerExit();

    assertThat(consumer.closedByThread).isEqualTo("review-poller");
  }

  @Test
  void stopShutsTheDrainerExecutorOnlyAfterThePollerHasFinished() {
    assignP0WithOneRecordOnFirstPoll();
    engine.start();
    awaitPolls(2);

    stopAndAwaitPollerExit();

    assertThat(drainerThreads.isShutdown()).isTrue();
    assertThat(drainerThreads.consumerWasClosedAtShutdown).isTrue();
  }

  @Test
  void stopBeforeStartTouchesNothing() {
    engine.stop();

    assertThat(consumer.closedByThread).isNull();
    assertThat(drainerThreads.isShutdown()).isFalse();
  }

  @Test
  void stoppingTwiceIsHarmless() {
    assignP0WithOneRecordOnFirstPoll();
    engine.start();
    awaitPolls(2);

    engine.stop();
    stopAndAwaitPollerExit();

    assertThat(consumer.closeCalls).hasValue(1);
  }

  @Test
  void startingTwiceRunsOnePoller() {
    assignP0WithOneRecordOnFirstPoll();

    engine.start();
    engine.start();
    awaitPolls(3);

    assertThat(consumer.subscribeCalls).hasValue(1);
  }

  @Test
  void aStoppedEngineDoesNotStartAgain() {
    engine.start();
    awaitPolls(1);
    stopAndAwaitPollerExit();
    int pollsAfterStop = consumer.polls.get();

    engine.start();

    await()
        .during(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(2))
        .until(() -> consumer.subscribeCalls.get() == 1);
    assertThat(consumer.polls).hasValue(pollsAfterStop);
  }

  @Test
  void aDeadPollerRunsTheDeathActionOnce() {
    consumer.setPollException(new KafkaException("broker gone"));

    engine.start();

    await().untilAsserted(() -> assertThat(pollerDeaths).hasValue(1));
    stopAndAwaitPollerExit();
    assertThat(pollerDeaths).hasValue(1);
  }

  @Test
  void aBadKeyRecordReachesTheHandlerAsPoisonAndIsCommitted() {
    consumer.updateBeginningOffsets(Map.of(P0, 0L));
    consumer.schedulePollTask(
        () -> {
          consumer.rebalance(List.of(P0));
          consumer.addRecord(
              new ConsumerRecord<>(P0.topic(), 0, 0, "not-a-pr-key", new byte[0]));
        });

    engine.start();

    await()
        .untilAsserted(
            () ->
                assertThat(consumer.committed(Set.of(P0)))
                    .containsEntry(P0, new OffsetAndMetadata(1)));
    assertThat(executedJobs).isEmpty();
    assertThat(handledOutcomes)
        .singleElement()
        .isInstanceOfSatisfying(
            Outcome.Terminal.class,
            terminal ->
                assertThat(terminal.failureClass())
                    .isEqualTo(FailureClass.POISON_DESERIALIZATION));
  }

  @Test
  void aPollerOutlivingTheStopWaitKeepsTheDrainerExecutorOpenUntilItExits() {
    var pollRelease = new CountDownLatch(1);
    consumer.pollRelease = pollRelease;
    engine.start();
    awaitPolls(1);

    engine.stop();
    boolean shutWhilePollerAlive = drainerThreads.isShutdown();
    pollRelease.countDown();

    assertThat(shutWhilePollerAlive).isFalse();
    await().until(drainerThreads::isShutdown);
  }

  @Test
  void dependencyPauseReachesThePoller() {
    assignP0WithOneRecordOnFirstPoll();
    engine.start();
    awaitPolls(2);

    engine.setDependencyPause(true);

    await().untilAsserted(() -> assertThat(consumer.paused()).containsExactly(P0));
  }

  private void assignP0WithOneRecordOnFirstPoll() {
    consumer.updateBeginningOffsets(Map.of(P0, 0L));
    consumer.schedulePollTask(
        () -> {
          consumer.rebalance(List.of(P0));
          consumer.addRecord(CoalescerTest.valid(0));
        });
  }

  private void stopAndAwaitPollerExit() {
    engine.stop();
    await().until(drainerThreads::isShutdown);
  }

  private void awaitPolls(int count) {
    await().until(() -> consumer.polls.get() >= count);
  }

  /** Records which thread closed it, and counts the calls the lifecycle must make exactly once. */
  private static final class ObservedConsumer extends MockConsumer<String, byte[]> {

    final AtomicInteger polls = new AtomicInteger();
    final AtomicInteger subscribeCalls = new AtomicInteger();
    final AtomicInteger closeCalls = new AtomicInteger();
    volatile String closedByThread;
    volatile CountDownLatch pollRelease;

    ObservedConsumer() {
      super(OffsetResetStrategy.EARLIEST);
    }

    @Override
    public ConsumerRecords<String, byte[]> poll(Duration timeout) {
      polls.incrementAndGet();
      awaitPollRelease();
      return super.poll(timeout);
    }

    private void awaitPollRelease() {
      if (pollRelease == null) {
        return;
      }
      try {
        pollRelease.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void subscribe(
        Collection<String> topics, ConsumerRebalanceListener listener) {
      subscribeCalls.incrementAndGet();
      super.subscribe(topics, listener);
    }

    @Override
    public synchronized void close() {
      closeCalls.incrementAndGet();
      closedByThread = Thread.currentThread().getName();
      super.close();
    }
  }

  /** Real virtual threads; remembers whether the consumer was already closed at shutdown. */
  private static final class ObservedDrainerThreads extends AbstractExecutorService {

    private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
    private final ObservedConsumer consumer;
    volatile boolean consumerWasClosedAtShutdown;

    ObservedDrainerThreads(ObservedConsumer consumer) {
      this.consumer = consumer;
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(command);
    }

    @Override
    public void shutdown() {
      consumerWasClosedAtShutdown = consumer.closedByThread != null;
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      consumerWasClosedAtShutdown = consumer.closedByThread != null;
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }
}
