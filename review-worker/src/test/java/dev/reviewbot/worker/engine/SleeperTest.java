package dev.reviewbot.worker.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SleeperTest {

  @Test
  void theThreadSleeperWaitsTheGivenDuration() {
    long start = System.nanoTime();

    Sleeper.THREAD.sleep(Duration.ofMillis(20));

    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .isGreaterThanOrEqualTo(Duration.ofMillis(20));
  }

  @Test
  void anInterruptEndsTheWaitAndStaysVisibleToTheNextBlockingCall() {
    Thread.currentThread().interrupt();
    long start = System.nanoTime();

    Sleeper.THREAD.sleep(Duration.ofSeconds(10));

    assertThat(Thread.interrupted()).isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
  }
}
