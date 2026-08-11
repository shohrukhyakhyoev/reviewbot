package dev.reviewbot.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The context must load with no external services running. */
@SpringBootTest
class ReviewWorkerApplicationTest {

  @Autowired private ApplicationContext context;

  @Test
  void contextLoads() {
    assertThat(context).isNotNull();
    assertThat(context.getEnvironment().getProperty("spring.application.name"))
        .isEqualTo("review-worker");
  }
}
