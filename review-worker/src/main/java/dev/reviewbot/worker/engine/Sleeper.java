package dev.reviewbot.worker.engine;

import java.time.Duration;

/** The poller's grace waits, replaceable so tests do not wait in real time. */
@FunctionalInterface
interface Sleeper {

  Sleeper THREAD = Sleeper::threadSleep;

  void sleep(Duration duration);

  private static void threadSleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      // The flag stays set: the next consumer call throws Kafka's InterruptException. A commit
      // reports it; a poll ends the poller (ADR 0004).
      Thread.currentThread().interrupt();
    }
  }
}
