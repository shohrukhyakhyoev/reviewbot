package dev.reviewbot.shared.envelope;

/**
 * An envelope that cannot be trusted. Becomes the {@code POISON_DESERIALIZATION} input to the
 * failure classifier (E1-2, §3.3 rule 4): the record goes to the DLQ. Messages name fields only,
 * never values.
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
