package dev.reviewbot.worker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.worker.engine.Engine;

class EngineLifecycleTest {

  private final MockConsumer<String, byte[]> consumer =
      new MockConsumer<>(OffsetResetStrategy.EARLIEST);
  private final Engine engine =
      Engine.create(
          consumer,
          job -> new Outcome.Success(),
          (record, outcome) -> {},
          new ReviewbotProperties.Consumer(
              Duration.ofSeconds(45),
              Duration.ofSeconds(3),
              Duration.ofSeconds(300),
              Duration.ofMillis(20),
              100,
              200,
              100,
              Duration.ofSeconds(30)),
          () -> {});

  @Test
  void startAndStopDriveTheEngineAndTheRunningFlag() {
    var lifecycle = new EngineLifecycle(engine, true);

    lifecycle.start();
    assertThat(lifecycle.isRunning()).isTrue();

    lifecycle.stop();
    assertThat(lifecycle.isRunning()).isFalse();
    await().until(consumer::closed);
  }

  @Test
  void autoStartupFollowsTheSetting() {
    assertThat(new EngineLifecycle(engine, true).isAutoStartup()).isTrue();
    assertThat(new EngineLifecycle(engine, false).isAutoStartup()).isFalse();
  }
}
