package dev.reviewbot.gateway.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisException;
import org.slf4j.LoggerFactory;

class LoggingDedupMetricsTest {

  private static final RedisException CAUSE =
      new RedisException("Unable to connect to redis-secret.internal:6379");

  private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingDedupMetrics.class);
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
  private final LoggingDedupMetrics metrics = new LoggingDedupMetrics();

  @BeforeEach
  void capture() {
    logged.start();
    logger.addAppender(logged);
  }

  @AfterEach
  void release() {
    logger.detachAppender(logged);
  }

  @Test
  void aFailedCheckWarnsWithTheCauseClassOnly() {
    metrics.checkFailedOpen(CAUSE);

    assertWarningNamingOnlyTheCauseClass();
  }

  @Test
  void aFailedMarkWarnsWithTheCauseClassOnly() {
    metrics.markFailed(CAUSE);

    assertWarningNamingOnlyTheCauseClass();
  }

  @Test
  void aHitIsLoggedAtInfo() {
    metrics.hit();

    assertThat(logged.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.INFO);
  }

  private void assertWarningNamingOnlyTheCauseClass() {
    assertThat(logged.list).singleElement().satisfies(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage())
          .contains("RedisException")
          .doesNotContain("redis-secret.internal");
      assertThat(event.getThrowableProxy()).isNull();
    });
  }
}
