package dev.reviewbot.gateway.webhook;

import dev.reviewbot.gateway.webhook.WebhookDecision.IgnoreReason;

/** What the gateway counts about intake; E7 backs it with a real registry. */
interface WebhookMetrics {

  void accepted();

  void ignored(IgnoreReason reason);

  /** {@code field} names what was missing; never a payload value. */
  void malformed(String deliveryId, String field);
}
