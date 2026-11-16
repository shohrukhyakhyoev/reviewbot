package dev.reviewbot.gateway.webhook;

/**
 * A delivery of an accepted event type that lacks a field we need. Names the field's JSON pointer
 * only, never a value: payloads carry code and titles that must not reach logs.
 */
final class MalformedPayloadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  MalformedPayloadException(String field) {
    super("payload field missing or invalid: " + field);
    this.field = field;
  }

  String field() {
    return field;
  }
}
