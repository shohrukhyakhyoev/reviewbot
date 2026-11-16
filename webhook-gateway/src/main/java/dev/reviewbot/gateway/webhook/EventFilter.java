package dev.reviewbot.gateway.webhook;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.reviewbot.gateway.webhook.WebhookDecision.Accept;
import dev.reviewbot.gateway.webhook.WebhookDecision.Ignore;
import dev.reviewbot.gateway.webhook.WebhookDecision.IgnoreReason;
import dev.reviewbot.gateway.webhook.WebhookDecision.Malformed;

/** Spec §6.1 step 2: which deliveries become review work. */
final class EventFilter {

  private static final String PULL_REQUEST = "pull_request";
  private static final String READY_FOR_REVIEW = "ready_for_review";
  private static final Set<String> ACCEPTED_ACTIONS =
      Set.of("opened", "synchronize", "reopened", READY_FOR_REVIEW);

  private final boolean reviewDrafts;

  EventFilter(boolean reviewDrafts) {
    this.reviewDrafts = reviewDrafts;
  }

  /** Cheapest checks first: the body is parsed only for the one event type we act on. */
  WebhookDecision decide(String eventType, byte[] body) {
    if (!PULL_REQUEST.equals(eventType)) {
      return new Ignore(IgnoreReason.UNSUPPORTED_EVENT);
    }
    try {
      JsonNode root = PullRequestPayloadReader.parse(body);
      if (!ACCEPTED_ACTIONS.contains(PullRequestPayloadReader.action(root))) {
        return new Ignore(IgnoreReason.UNSUPPORTED_ACTION);
      }
      PullRequestPayload payload = PullRequestPayloadReader.extract(root);
      return isSkippedDraft(payload) ? new Ignore(IgnoreReason.DRAFT) : new Accept(payload);
    } catch (MalformedPayloadException e) {
      return new Malformed(e.field());
    }
  }

  private boolean isSkippedDraft(PullRequestPayload payload) {
    // ready_for_review is the author asking for a review, so it is decided before the draft flag.
    return !payload.action().equals(READY_FOR_REVIEW) && payload.draft() && !reviewDrafts;
  }
}
