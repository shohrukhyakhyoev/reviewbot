package dev.reviewbot.gateway.webhook;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads a {@code pull_request} delivery's JSON into a {@link PullRequestPayload}. */
final class PullRequestPayloadReader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PullRequestPayloadReader() {}

  /** Parses to a tree, not a class: a field we do not read can change shape without breaking us. */
  static JsonNode parse(byte[] body) {
    try {
      JsonNode root = MAPPER.readTree(body);
      if (root == null || !root.isObject()) {
        throw new MalformedPayloadException("<body>");
      }
      return root;
    } catch (IOException e) {
      // Not chained: a parse error message can quote the payload.
      throw new MalformedPayloadException("<body>");
    }
  }

  /** The action alone, so an ignored action (e.g. {@code closed}) never needs the other fields. */
  static String action(JsonNode root) {
    JsonNode node = root.path("action");
    return node.isTextual() ? node.textValue() : "";
  }

  static PullRequestPayload extract(JsonNode root) {
    return new PullRequestPayload(
        text(root, "/action"),
        draft(root),
        positiveLong(root, "/installation/id"),
        text(root, "/repository/full_name"),
        positiveInt(root, "/pull_request/number"),
        text(root, "/pull_request/head/sha"),
        instant(root, "/pull_request/updated_at"));
  }

  // GitHub is expected to send it (unverified); absent or null means "not a draft", so we err toward
  // reviewing. A present value of any other type is malformed: asBoolean would read "true" or 1 as
  // a draft.
  private static boolean draft(JsonNode root) {
    String pointer = "/pull_request/draft";
    JsonNode node = root.at(pointer);
    if (node.isMissingNode() || node.isNull()) {
      return false;
    }
    if (!node.isBoolean()) {
      throw new MalformedPayloadException(pointer);
    }
    return node.booleanValue();
  }

  private static JsonNode requiredNode(JsonNode root, String pointer) {
    JsonNode node = root.at(pointer);
    if (node.isMissingNode() || node.isNull()) {
      throw new MalformedPayloadException(pointer);
    }
    return node;
  }

  // A blank string counts as missing, matching EnvelopeValidator in shared.
  private static String text(JsonNode root, String pointer) {
    JsonNode node = requiredNode(root, pointer);
    if (!node.isTextual() || node.textValue().isBlank()) {
      throw new MalformedPayloadException(pointer);
    }
    return node.textValue();
  }

  private static long positiveLong(JsonNode root, String pointer) {
    JsonNode node = requiredNode(root, pointer);
    if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1) {
      throw new MalformedPayloadException(pointer);
    }
    return node.longValue();
  }

  private static int positiveInt(JsonNode root, String pointer) {
    long value = positiveLong(root, pointer);
    if (value > Integer.MAX_VALUE) {
      throw new MalformedPayloadException(pointer);
    }
    return (int) value;
  }

  private static Instant instant(JsonNode root, String pointer) {
    try {
      return Instant.parse(text(root, pointer));
    } catch (DateTimeParseException e) {
      throw new MalformedPayloadException(pointer);
    }
  }
}
