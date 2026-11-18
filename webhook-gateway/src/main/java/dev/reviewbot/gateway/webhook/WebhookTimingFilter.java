package dev.reviewbot.gateway.webhook;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records every {@code /webhook} answer's status and latency. Runs outside the HMAC filter so its
 * 401/413 are counted; reads no body, so it does not weaken that filter's trust boundary.
 */
public final class WebhookTimingFilter extends OncePerRequestFilter {

  private final WebhookMetrics metrics;

  public WebhookTimingFilter() {
    this(new LoggingWebhookMetrics());
  }

  WebhookTimingFilter(WebhookMetrics metrics) {
    this.metrics = metrics;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    // An exception escaping the chain becomes a 500 later, after this filter has returned.
    int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    try {
      chain.doFilter(request, response);
      status = response.getStatus();
    } finally {
      metrics.answered(status, Duration.ofNanos(System.nanoTime() - start));
    }
  }
}
