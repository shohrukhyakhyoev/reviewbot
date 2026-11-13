package dev.reviewbot.shared.envelope;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-bind validation of a {@link ReviewRequestedEnvelope} (spec §3.3 rule 4). Throws one {@link
 * EnvelopeValidationException} listing every problem (field names only). A blank string counts as
 * missing.
 */
public final class EnvelopeValidator {

  private EnvelopeValidator() {}

  public static void validate(ReviewRequestedEnvelope envelope) {
    if (envelope == null) {
      throw new EnvelopeValidationException("invalid envelope: payload is JSON null");
    }

    List<String> problems = new ArrayList<>();

    if (envelope.schemaVersion() == null) {
      problems.add("schema_version missing");
    } else if (envelope.schemaVersion() != ReviewRequestedEnvelope.SCHEMA_VERSION) {
      problems.add("schema_version must equal " + ReviewRequestedEnvelope.SCHEMA_VERSION);
    }

    if (isBlank(envelope.eventId())) problems.add("event_id missing");
    if (isBlank(envelope.deliveryId())) problems.add("delivery_id missing");
    if (envelope.type() == null) problems.add("type missing");
    if (envelope.occurredAt() == null) problems.add("occurred_at missing");
    if (envelope.installationId() == null) problems.add("installation_id missing");
    if (isBlank(envelope.repoFullName())) problems.add("repo_full_name missing");
    if (envelope.prNumber() == null) problems.add("pr_number missing");

    // Exhaustive switch expression: a new EventType fails to compile here until someone decides
    // whether it needs head_sha.
    if (envelope.type() != null) {
      boolean headShaRequired =
          switch (envelope.type()) {
            case REVIEW_REQUESTED -> true;
          };
      if (headShaRequired && isBlank(envelope.headSha())) {
        problems.add("head_sha missing");
      }
    }

    if (!problems.isEmpty()) {
      throw new EnvelopeValidationException("invalid envelope: " + String.join(", ", problems));
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
