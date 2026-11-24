package dev.reviewbot.worker.engine;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.kafka.clients.consumer.Consumer;

import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.shared.config.Topics;

/** The consumer engine (§8): one poller thread feeding per-PR drainers on virtual threads. */
public final class Engine {

  private static final int POLL_CYCLES_TO_WAIT_ON_STOP = 5;

  private final Poller poller;
  private final ExecutorService drainerThreads;
  private final Duration pollerStopWait;
  private final ReentrantLock lifecycle = new ReentrantLock();
  private Thread pollerThread;

  public static Engine create(
      Consumer<String, byte[]> consumer,
      JobExecutor jobs,
      OutcomeHandler outcomes,
      ReviewbotProperties.Consumer settings,
      Runnable onPollerDeath) {
    return new Engine(
        consumer,
        jobs,
        outcomes,
        new LoggingEngineMetrics(),
        settings,
        onPollerDeath,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  Engine(
      Consumer<String, byte[]> consumer,
      JobExecutor jobs,
      OutcomeHandler outcomes,
      EngineMetrics metrics,
      ReviewbotProperties.Consumer settings,
      Runnable onPollerDeath,
      ExecutorService drainerThreads) {
    var inFlight = new Semaphore(settings.inFlightCap());
    var tracker = new OffsetTracker(inFlight);
    var dispatcher =
        new Dispatcher(
            drainerThreads,
            Drainer.factory(jobs, outcomes, tracker, metrics),
            BadKeyTasks.completingAsPoison(outcomes, tracker, metrics),
            metrics);
    this.poller =
        new Poller(
            consumer,
            Topics.REVIEW_REQUESTED,
            inFlight,
            tracker,
            dispatcher,
            metrics,
            settings,
            onPollerDeath);
    this.drainerThreads = drainerThreads;
    this.pollerStopWait = settings.pollTimeout().multipliedBy(POLL_CYCLES_TO_WAIT_ON_STOP);
  }

  public void start() {
    lifecycle.lock();
    try {
      if (pollerThread == null) {
        pollerThread = Thread.ofPlatform().name("review-poller").start(this::runPollerThenStopDrainers);
      }
    } finally {
      lifecycle.unlock();
    }
  }

  public void stop() {
    lifecycle.lock();
    try {
      if (pollerThread != null) {
        poller.stop();
        awaitPollerExit();
      }
    } finally {
      lifecycle.unlock();
    }
  }

  private void runPollerThenStopDrainers() {
    try {
      poller.run();
    } finally {
      drainerThreads.shutdown();
    }
  }

  private void awaitPollerExit() {
    try {
      pollerThread.join(pollerStopWait);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** E8's breaker and health checks call this from their own threads. */
  public void setDependencyPause(boolean dependencyPause) {
    poller.setDependencyPause(dependencyPause);
  }
}
