package dev.reviewbot.worker.engine;

import static java.util.stream.Collectors.toMap;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import dev.reviewbot.shared.config.ReviewbotProperties;

/** The one thread that touches the {@code KafkaConsumer} (§8.1, I1): pause gate, intake, commits. */
final class Poller implements Runnable {

  private final Consumer<String, byte[]> consumer;
  private final String topic;
  private final Semaphore inFlight;
  private final OffsetTracker tracker;
  private final Dispatcher dispatcher;
  private final EngineMetrics metrics;
  private final Duration pollTimeout;
  private final int maxPollRecords;
  private final int resumeThreshold;
  private final Runnable onDeath;

  private volatile boolean dependencyPause;
  private volatile boolean running = true;
  private boolean intakePaused;
  private boolean backpressureHoldingIntake;

  Poller(
      Consumer<String, byte[]> consumer,
      String topic,
      Semaphore inFlight,
      OffsetTracker tracker,
      Dispatcher dispatcher,
      EngineMetrics metrics,
      ReviewbotProperties.Consumer settings,
      Runnable onDeath) {
    this.consumer = consumer;
    this.topic = topic;
    this.inFlight = inFlight;
    this.tracker = tracker;
    this.dispatcher = dispatcher;
    this.metrics = SafeEngineMetrics.wrap(metrics);
    this.pollTimeout = settings.pollTimeout();
    this.maxPollRecords = settings.maxPollRecords();
    this.resumeThreshold = settings.resumeThreshold();
    this.onDeath = onDeath;
  }

  @Override
  public void run() {
    try {
      subscribe();
      while (running) {
        runOnce();
      }
    } catch (Throwable fatal) {
      metrics.pollerDied(fatal);
      onDeath.run();
    } finally {
      consumer.close();
    }
  }

  void stop() {
    running = false;
  }

  void subscribe() {
    consumer.subscribe(List.of(topic), new AssignmentListener());
  }

  void runOnce() {
    applyPauseGate();
    ConsumerRecords<String, byte[]> records = consumer.poll(pollTimeout);
    intake(records);
    commitWatermarks();
  }

  void setDependencyPause(boolean dependencyPause) {
    this.dependencyPause = dependencyPause;
  }

  private void applyPauseGate() {
    int freePermits = inFlight.availablePermits();
    boolean dependencyDown = dependencyPause;
    boolean wantPause = dependencyDown || freePermits < maxPollRecords;
    boolean canResume = !dependencyDown && freePermits >= resumeThreshold;

    if (wantPause && !intakePaused) {
      consumer.pause(consumer.assignment());
      intakePaused = true;
    } else if (canResume && intakePaused) {
      consumer.resume(consumer.assignment());
      intakePaused = false;
    }
    reportBackpressure(intakePaused && freePermits < resumeThreshold);
  }

  private void reportBackpressure(boolean holdingIntake) {
    if (holdingIntake != backpressureHoldingIntake) {
      backpressureHoldingIntake = holdingIntake;
      metrics.pauseState(PauseReason.BACKPRESSURE, holdingIntake);
    }
  }

  private void intake(ConsumerRecords<String, byte[]> records) {
    Set<TopicPartition> stillOwned = consumer.assignment();
    for (ConsumerRecord<String, byte[]> record : records) {
      TopicPartition partition = new TopicPartition(record.topic(), record.partition());
      if (stillOwned.contains(partition)) {
        admit(partition, record);
      }
    }
  }

  private void admit(TopicPartition partition, ConsumerRecord<String, byte[]> record) {
    TrackedOffset receipt = tracker.track(partition, record.offset());
    inFlight.acquireUninterruptibly();
    dispatcher.route(new TrackedRecord(record, receipt));
  }

  private void commitWatermarks() {
    Map<TopicPartition, Long> watermarks = tracker.committable();
    if (watermarks.isEmpty()) {
      return;
    }
    consumer.commitAsync(
        toCommitOffsets(watermarks), (offsets, error) -> onCommitAnswered(watermarks, error));
  }

  private static Map<TopicPartition, OffsetAndMetadata> toCommitOffsets(
      Map<TopicPartition, Long> watermarks) {
    return watermarks.entrySet().stream()
        .collect(toMap(Map.Entry::getKey, entry -> new OffsetAndMetadata(entry.getValue() + 1)));
  }

  private void onCommitAnswered(Map<TopicPartition, Long> watermarks, Exception error) {
    if (error == null) {
      tracker.markCommitted(watermarks);
    } else {
      metrics.commitFailed(watermarks.keySet(), error);
    }
  }

  private final class AssignmentListener implements ConsumerRebalanceListener {

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
      for (TopicPartition tp : partitions) {
        tracker.register(tp, consumer.position(tp));
      }
      dispatcher.reopen(partitions);
      pauseNewPartitionsWhileIntakePaused(partitions);
    }

    private void pauseNewPartitionsWhileIntakePaused(Collection<TopicPartition> partitions) {
      if (intakePaused) {
        consumer.pause(partitions);
      }
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
      // E4-4.
    }
  }
}
