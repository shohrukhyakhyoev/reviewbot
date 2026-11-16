package dev.reviewbot.gateway.webhook;

/** What the gateway does with one verified delivery. Every variant is answered {@code 200}. */
sealed interface WebhookDecision {

  /** Becomes an envelope and goes on to dedup and produce. */
  record Accept(PullRequestPayload payload) implements WebhookDecision {}

  /** Deliberately not reviewed; acked so GitHub does not retry. */
  record Ignore(IgnoreReason reason) implements WebhookDecision {}

  /** An accepted event type that lacks a required field; identical bytes would fail identically. */
  record Malformed(String field) implements WebhookDecision {}

  /** A closed set, so it can label a metric without exploding its cardinality. */
  enum IgnoreReason {
    UNSUPPORTED_EVENT,
    UNSUPPORTED_ACTION,
    DRAFT
  }
}
