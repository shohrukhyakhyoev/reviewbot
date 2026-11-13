package dev.reviewbot.shared.headers;

/**
 * A Kafka header value that cannot be decoded: missing, empty, non-numeric, negative or out of range
 * (spec §3.2). Distinct from {@code EnvelopeValidationException}: a bad header says nothing about
 * whether the payload is poison, and the DB stays the source of truth for the counters, so how the
 * consumer reacts is the failure classifier's decision (E1-2), not this codec's.
 *
 * <p>Messages name the header (added by {@code Header.decode}) and the kind of failure, never the raw
 * bytes.
 */
public class HeaderDecodeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public HeaderDecodeException(String message) {
    super(message);
  }

  public HeaderDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
