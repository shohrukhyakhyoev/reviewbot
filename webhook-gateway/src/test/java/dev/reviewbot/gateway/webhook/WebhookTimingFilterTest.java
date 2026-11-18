package dev.reviewbot.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import dev.reviewbot.gateway.webhook.WebhookDecision.IgnoreReason;

class WebhookTimingFilterTest {

  private final RecordingMetrics metrics = new RecordingMetrics();
  private final WebhookTimingFilter filter = new WebhookTimingFilter(metrics);
  private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhook");
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @ParameterizedTest
  @ValueSource(ints = {200, 401, 413, 503})
  void recordsTheStatusTheChainAnswered(int status) throws Exception {
    filter.doFilter(request, response, (req, res) -> response.setStatus(status));

    assertThat(metrics.statuses).containsExactly(status);
  }

  @Test
  void recordsHowLongTheChainTook() throws Exception {
    filter.doFilter(request, response, (req, res) -> sleep(50));

    assertThat(metrics.latencies.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
  }

  @Test
  void anEscapingExceptionIsCountedAsA500AndStillPropagates() {
    FilterChain failing =
        (req, res) -> {
          throw new IllegalStateException("bug downstream");
        };

    assertThatThrownBy(() -> filter.doFilter(request, response, failing))
        .isInstanceOf(IllegalStateException.class);
    assertThat(metrics.statuses).containsExactly(500);
  }

  // The HMAC filter must be the first to touch the body; this one only wraps it.
  @Test
  void neverReadsTheBody() throws Exception {
    request.setContent("{\"a\":1}".getBytes());

    filter.doFilter(
        request,
        response,
        (req, res) -> assertThat(req.getInputStream().readAllBytes()).hasSize(7));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class RecordingMetrics implements WebhookMetrics {
    final List<Integer> statuses = new ArrayList<>();
    final List<Duration> latencies = new ArrayList<>();

    @Override
    public void accepted() {}

    @Override
    public void ignored(IgnoreReason reason) {}

    @Override
    public void malformed(String deliveryId, String field) {}

    @Override
    public void answered(int status, Duration latency) {
      statuses.add(status);
      latencies.add(latency);
    }

    @Override
    public void produceFailed(Throwable cause) {}
  }
}
