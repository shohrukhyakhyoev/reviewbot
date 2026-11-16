package dev.reviewbot.gateway.webhook;

import java.util.Optional;

import dev.reviewbot.gateway.webhook.WebhookDecision.Accept;
import dev.reviewbot.gateway.webhook.WebhookDecision.Ignore;
import dev.reviewbot.gateway.webhook.WebhookDecision.Malformed;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;

/**
 * Spec §6.1 steps 2-3: turns one verified delivery into an envelope, or says there is nothing to
 * enqueue. Dedup, produce and the HTTP status are the caller's (E3-3).
 */
public final class WebhookIntake {

  private static final String DELIVERY_HEADER = "X-GitHub-Delivery";

  private final EventFilter filter;
  private final EnvelopeFactory factory;
  private final WebhookMetrics metrics;

  public WebhookIntake(boolean reviewDrafts) {
    this(new EventFilter(reviewDrafts), new EnvelopeFactory(), new LoggingWebhookMetrics());
  }

  WebhookIntake(EventFilter filter, EnvelopeFactory factory, WebhookMetrics metrics) {
    this.filter = filter;
    this.factory = factory;
    this.metrics = metrics;
  }

  /**
   * Empty means "answer 200, enqueue nothing": the outcome is already counted and logged. Never
   * throws for a delivery the reader or the delivery-id check rejects, so a payload that cannot
   * succeed is not retried. Anything they accept must pass {@code EnvelopeValidator}; if a new rule
   * there breaks that, the exception escapes on purpose (a bug, not a bad delivery).
   */
  public Optional<ReviewRequestedEnvelope> handle(
      String eventType, String deliveryId, byte[] body) {
    return switch (filter.decide(eventType, body)) {
      case Accept accepted -> build(deliveryId, accepted.payload());
      case Ignore ignored -> {
        metrics.ignored(ignored.reason());
        yield Optional.empty();
      }
      case Malformed malformed -> {
        metrics.malformed(deliveryId, malformed.field());
        yield Optional.empty();
      }
    };
  }

  private Optional<ReviewRequestedEnvelope> build(String deliveryId, PullRequestPayload payload) {
    // Checked here, not left to the factory's validator, so the metric label stays a field name.
    if (deliveryId == null || deliveryId.isBlank()) {
      metrics.malformed(deliveryId, DELIVERY_HEADER);
      return Optional.empty();
    }
    ReviewRequestedEnvelope envelope = factory.build(deliveryId, payload);
    metrics.accepted();
    return Optional.of(envelope);
  }
}
