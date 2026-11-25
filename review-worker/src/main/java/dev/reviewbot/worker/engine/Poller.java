package dev.reviewbot.worker.engine;

import static java.util.stream.Collectors.toMap;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

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
  private final Duration revocationGrace;
  private final int inFlightCap;
  private final Duration shutdownGrace;
  private final Duration kafkaCallTimeout;
  private final Sleeper sleeper;
  private final Runnable onDeath;

  private volatile boolean dependencyPause;
  private volatile boolean running = true;
  private boolean intakePaused;
  private boolean closing;
  private boolean backpressureHoldingIntake;

  Poller(
      Consumer<String, byte[]> consumer,
      String topic,
      Semaphore inFlight,
      OffsetTracker tracker,
      Dispatcher dispatcher,
      EngineMetrics metrics,
      ReviewbotProperties.Consumer settings,
      Sleeper sleeper,
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
    this.revocationGrace = settings.revocationGrace();
    this.inFlightCap = settings.inFlightCap();
    this.shutdownGrace = settings.shutdownGrace();
    this.kafkaCallTimeout = settings.kafkaCallTimeout();
    this.sleeper = sleeper;
    this.onDeath = onDeath;
  }

  @Override
  public void run() {
    try {
      subscribe();
      pollUntilStopped();
      awaitInFlightWork();
      commitBeforeClosing();
    } catch (Throwable fatal) {
      metrics.pollerDied(fatal);
      onDeath.run();
    } finally {
      closeConsumer();
    }
  }

  /** Called from another thread; {@code wakeup()} is the one consumer call that allows it (I1). */
  void stop() {
    running = false;
    consumer.wakeup();
  }

  void setDependencyPause(boolean dependencyPause) {
    this.dependencyPause = dependencyPause;
  }

  void subscribe() {
    consumer.subscribe(List.of(topic), new AssignmentListener());
  }

  private void pollUntilStopped() {
    while (running) {
      try {
        runOnce();
      } catch (WakeupException stopRequested) {
        // stop() clears running before ringing, so the loop condition is what ends the run.
      }
    }
  }

  void runOnce() {
    applyPauseGate();
    ConsumerRecords<String, byte[]> records = consumer.poll(pollTimeout);
    intake(records);
    commitInBackground();
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

  private void commitInBackground() {
    Map<TopicPartition, TrackedOffset> watermarks = tracker.committable(consumer.assignment());
    if (watermarks.isEmpty()) {
      return;
    }
    consumer.commitAsync(
        toCommitOffsets(watermarks), (offsets, error) -> onCommitAnswered(watermarks, error));
  }

  private static Map<TopicPartition, OffsetAndMetadata> toCommitOffsets(
      Map<TopicPartition, TrackedOffset> watermarks) {
    return watermarks.entrySet().stream()
        .collect(toMap(Map.Entry::getKey, entry -> new OffsetAndMetadata(entry.getValue().offset() + 1)));
  }

  private void onCommitAnswered(Map<TopicPartition, TrackedOffset> watermarks, Exception error) {
    if (error == null) {
      tracker.markCommitted(watermarks);
    } else {
      metrics.commitFailed(watermarks.keySet(), error);
    }
  }

  private void awaitInFlightWork() {
    try {
      boolean allPermitsReturned =
          inFlight.tryAcquire(inFlightCap, shutdownGrace.toNanos(), TimeUnit.NANOSECONDS);
      if (allPermitsReturned) {
        inFlight.release(inFlightCap);
      } else {
        metrics.shutdownGraceExpired(inFlightCap - inFlight.availablePermits());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void commitBeforeClosing() {
    commitAndWait(tracker.committable(consumer.assignment()));
  }

  private void commitAndWait(Map<TopicPartition, TrackedOffset> watermarks) {
    if (watermarks.isEmpty()) {
      return;
    }
    Map<TopicPartition, OffsetAndMetadata> offsets = toCommitOffsets(watermarks);
    try {
      commitSyncRetryingOnWakeup(offsets);
    } catch (KafkaException e) {
      metrics.commitFailed(watermarks.keySet(), e);
    }
  }

  private void commitSyncRetryingOnWakeup(Map<TopicPartition, OffsetAndMetadata> offsets) {
    try {
      consumer.commitSync(offsets, kafkaCallTimeout);
    } catch (WakeupException leftoverFromStop) {
      consumer.commitSync(offsets, kafkaCallTimeout);
    }
  }

  private void closeConsumer() {
    closing = true;
    try {
      consumer.close(kafkaCallTimeout);
    } catch (KafkaException e) {
      metrics.consumerCloseFailed(e);
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
      // close() re-enters here for every partition. No handover then: a clean shutdown has already
      // committed, and a dying poller leaves the rest to redelivery (ADR 0004).
      if (closing) {
        return;
      }
      dispatcher.markClosed(partitions);
      sleeper.sleep(revocationGrace);
      commitAndWait(tracker.committable(partitions));
      tracker.unregister(partitions);
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
      dispatcher.markClosed(partitions);
      tracker.unregister(partitions);
    }
  }
}
