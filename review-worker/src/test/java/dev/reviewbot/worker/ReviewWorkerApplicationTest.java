package dev.reviewbot.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

import dev.reviewbot.worker.engine.Engine;

/** The context must load with no external services running, so the engine is not started. */
@SpringBootTest(properties = "reviewbot.worker.engine-autostart=false")
class ReviewWorkerApplicationTest {

  @Autowired private ApplicationContext context;

  @Test
  void contextLoads() {
    assertThat(context).isNotNull();
    assertThat(context.getEnvironment().getProperty("spring.application.name"))
        .isEqualTo("review-worker");
  }

  @Test
  void theEngineIsAssembledButLeftStoppedWhenAutostartIsOff() {
    assertThat(context.getBean(Engine.class)).isNotNull();
    assertThat(context.getBeansOfType(SmartLifecycle.class).get("engineLifecycle").isRunning())
        .isFalse();
  }
}
