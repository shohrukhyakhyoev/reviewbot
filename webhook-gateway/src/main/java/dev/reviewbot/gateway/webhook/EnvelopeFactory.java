package dev.reviewbot.gateway.webhook;

import java.util.UUID;
import java.util.function.Supplier;

import dev.reviewbot.shared.envelope.EnvelopeValidator;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;

/** Builds the §3.1 envelope from an accepted payload. */
final class EnvelopeFactory {

  private final Supplier<UUID> eventIds;

  EnvelopeFactory() {
    this(UUID::randomUUID);
  }

  EnvelopeFactory(Supplier<UUID> eventIds) {
    this.eventIds = eventIds;
  }

  /**
   * Validates what it built: {@code EnvelopeSerde.toBytes} does not, and a blank {@code
   * X-GitHub-Delivery} would otherwise reach the topic and fail at the consumer.
   */
  ReviewRequestedEnvelope build(String deliveryId, PullRequestPayload payload) {
    ReviewRequestedEnvelope envelope =
        new ReviewRequestedEnvelope(
            ReviewRequestedEnvelope.SCHEMA_VERSION,
            eventIds.get().toString(),
            deliveryId,
            EventType.REVIEW_REQUESTED,
            payload.updatedAt(),
            payload.installationId(),
            payload.repoFullName(),
            payload.prNumber(),
            payload.headSha(),
            false,
            false);
    EnvelopeValidator.validate(envelope);
    return envelope;
  }
}
