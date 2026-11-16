package dev.reviewbot.gateway.webhook;

import java.io.IOException;

/** The request body is larger than the gateway accepts. */
final class BodyTooLargeException extends IOException {

  BodyTooLargeException(int maxBytes) {
    super("request body exceeds " + maxBytes + " bytes");
  }
}
