package dev.reviewbot.shared.envelope;

/**
 * An envelope that cannot be trusted: undecodable JSON, unknown {@code type}, a missing required
 * field, or a wrong {@code schema_version}. Surfaces as the {@code POISON_DESERIALIZATION} input
 * to the failure classifier (E1-2, spec §3.3 rule 4 / §10.1) — the record goes to the DLQ.
 *
 * <p>The message names fields only, never values or payload content (CLAUDE.md: don't log diff or
 * prompt content; log sizes and hashes).
 */
public class EnvelopeValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EnvelopeValidationException(String message) {
    super(message);
  }

  public EnvelopeValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
