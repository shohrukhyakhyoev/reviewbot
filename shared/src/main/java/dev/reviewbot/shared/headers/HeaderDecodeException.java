package dev.reviewbot.shared.headers;

/**
 * A Kafka header value that cannot be decoded (spec §3.2). Distinct from {@code
 * EnvelopeValidationException}: a bad header says nothing about the payload, so how to react is the
 * failure classifier's call (E1-2). Messages name the header, never the raw bytes.
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
