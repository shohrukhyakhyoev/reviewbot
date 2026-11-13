package dev.reviewbot.shared.envelope;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit post-bind validation of a {@link ReviewRequestedEnvelope} (spec §3.3 rule 4, ticket O2).
 *
 * <p>Per-type: the rules depend on {@code type}. Any failure throws {@link
 * EnvelopeValidationException}, which lists every problem found (field names only, never values).
 * A blank string counts as missing.
 */
public final class EnvelopeValidator {

  private EnvelopeValidator() {}

  /**
   * Checks a bound envelope against §3.3 rule 4 and returns normally only if it is trustworthy.
   *
   * @throws EnvelopeValidationException if the envelope violates §3.3 rule 4
   */
  public static void validate(ReviewRequestedEnvelope envelope) {
    if (envelope == null) {
      throw new EnvelopeValidationException("invalid envelope: payload is JSON null");
    }

    List<String> problems = new ArrayList<>();

    // schema_version: required AND must equal 1. Null check first, or the compare would NPE.
    if (envelope.schemaVersion() == null) {
      problems.add("schema_version missing");
    } else if (envelope.schemaVersion() != ReviewRequestedEnvelope.SCHEMA_VERSION) {
      problems.add("schema_version must equal " + ReviewRequestedEnvelope.SCHEMA_VERSION);
    }

    // Required for every type (force/replay are defaulted by the record, so not checked).
    if (isBlank(envelope.eventId())) problems.add("event_id missing");
    if (isBlank(envelope.deliveryId())) problems.add("delivery_id missing");
    if (envelope.type() == null) problems.add("type missing");
    if (envelope.occurredAt() == null) problems.add("occurred_at missing");
    if (envelope.installationId() == null) problems.add("installation_id missing");
    if (isBlank(envelope.repoFullName())) problems.add("repo_full_name missing");
    if (envelope.prNumber() == null) problems.add("pr_number missing");

    // Per-type rules. A switch EXPRESSION must be exhaustive, so adding a v1.5 command type to
    // EventType stops compiling here until someone decides whether it needs head_sha.
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
