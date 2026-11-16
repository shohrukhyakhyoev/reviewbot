package dev.reviewbot.gateway.webhook;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import com.fasterxml.jackson.databind.JsonNode;

/** Loads the recorded GitHub payloads under {@code src/test/resources/github}. */
final class Fixtures {

  private Fixtures() {}

  static byte[] bytes(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/github/" + name + ".json")) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture named " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static JsonNode tree(String name) {
    return PullRequestPayloadReader.parse(bytes(name));
  }
}
