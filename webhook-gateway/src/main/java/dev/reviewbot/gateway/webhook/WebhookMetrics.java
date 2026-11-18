package dev.reviewbot.gateway.webhook;

import java.time.Duration;

import dev.reviewbot.gateway.webhook.WebhookDecision.IgnoreReason;

/** What the gateway counts about intake; E7 backs it with a real registry. */
interface WebhookMetrics {

  void accepted();

  void ignored(IgnoreReason reason);

  /** {@code field} names what was missing; never a payload value. */
  void malformed(String deliveryId, String field);

  /** §15 webhook latency and non-2xx count: every {@code /webhook} answer, including 401/413/503. */
  void answered(int status, Duration latency);

  /** Why a produce answered 503: tells "Kafka slow" from a refusal that can never succeed. */
  void produceFailed(Throwable cause);
}
