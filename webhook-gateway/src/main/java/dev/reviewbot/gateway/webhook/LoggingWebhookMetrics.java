package dev.reviewbot.gateway.webhook;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.reviewbot.gateway.webhook.WebhookDecision.IgnoreReason;

/** Stand-in until E7 adds a metrics registry: one log line per outcome, no payload content. */
final class LoggingWebhookMetrics implements WebhookMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingWebhookMetrics.class);

  @Override
  public void accepted() {
    LOG.info("webhook accepted");
  }

  @Override
  public void ignored(IgnoreReason reason) {
    LOG.info("webhook ignored reason={}", reason);
  }

  @Override
  public void malformed(String deliveryId, String field) {
    LOG.warn("webhook malformed, acked without retry deliveryId={} field={}", deliveryId, field);
  }

  @Override
  public void answered(int status, Duration latency) {
    if (status >= 200 && status < 300) {
      LOG.info("webhook answered status={} latencyMs={}", status, latency.toMillis());
    } else {
      LOG.warn("webhook answered non-2xx status={} latencyMs={}", status, latency.toMillis());
    }
  }

  // Class name only: Kafka's messages can name brokers and topics; the class picks the runbook.
  @Override
  public void produceFailed(Throwable cause) {
    LOG.warn("webhook produce failed cause={}", cause.getClass().getName());
  }
}
