package dev.reviewbot.shared.envelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Wire-contract tests for the §3.1 envelope: serde, tolerant reader, validation (spec §18). */
class EnvelopeContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The §3.1 example, as a producer would send it. */
  private static final String VALID =
      """
      {
        "schema_version": 1,
        "event_id": "c1a2-uuid",
        "delivery_id": "delivery-1",
        "type": "REVIEW_REQUESTED",
        "occurred_at": "2026-07-05T10:00:00Z",
        "installation_id": 12345,
        "repo_full_name": "acme/app",
        "pr_number": 17,
        "head_sha": "abc123",
        "force": false,
        "replay": false
      }
      """;

  private static ReviewRequestedEnvelope envelope(String type, String headSha) {
    return new ReviewRequestedEnvelope(
        1,
        "c1a2-uuid",
        "delivery-1",
        type == null ? null : EventType.valueOf(type),
        Instant.parse("2026-07-05T10:00:00Z"),
        12345L,
        "acme/app",
        17,
        headSha,
        false,
        false);
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] without(String field) throws Exception {
    ObjectNode node = (ObjectNode) JSON.readTree(VALID);
    node.remove(field);
    return JSON.writeValueAsBytes(node);
  }

  private static byte[] with(String field, Object value) throws Exception {
    ObjectNode node = (ObjectNode) JSON.readTree(VALID);
    node.set(field, JSON.valueToTree(value));
    return JSON.writeValueAsBytes(node);
  }

  // ---- round trip / wire shape ----

  @Test
  void validPayloadBindsEveryField() {
    ReviewRequestedEnvelope e = EnvelopeSerde.fromBytes(bytes(VALID));

    assertThat(e.schemaVersion()).isEqualTo(1);
    assertThat(e.eventId()).isEqualTo("c1a2-uuid");
    assertThat(e.deliveryId()).isEqualTo("delivery-1");
    assertThat(e.type()).isEqualTo(EventType.REVIEW_REQUESTED);
    assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-07-05T10:00:00Z"));
    assertThat(e.installationId()).isEqualTo(12345L);
    assertThat(e.repoFullName()).isEqualTo("acme/app");
    assertThat(e.prNumber()).isEqualTo(17);
    assertThat(e.headSha()).isEqualTo("abc123");
    assertThat(e.force()).isFalse();
    assertThat(e.replay()).isFalse();
  }

  @Test
  void roundTripsThroughBytes() {
    ReviewRequestedEnvelope original =
        new ReviewRequestedEnvelope(
            1,
            "e",
            "d",
            EventType.REVIEW_REQUESTED,
            Instant.parse("2026-07-05T10:00:00Z"),
            1L,
            "a/b",
            2,
            "sha",
            true,
            true);

    assertThat(EnvelopeSerde.fromBytes(EnvelopeSerde.toBytes(original))).isEqualTo(original);
  }

  @Test
  void serializesWithSnakeCaseNamesAndIsoTimestamp() throws Exception {
    JsonNode node = JSON.readTree(EnvelopeSerde.toBytes(envelope("REVIEW_REQUESTED", "abc123")));

    assertThat(node.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder(
            "schema_version",
            "event_id",
            "delivery_id",
            "type",
            "occurred_at",
            "installation_id",
            "repo_full_name",
            "pr_number",
            "head_sha",
            "force",
            "replay");
    assertThat(node.get("occurred_at").isTextual()).isTrue();
    assertThat(node.get("occurred_at").asText()).isEqualTo("2026-07-05T10:00:00Z");
  }

  @Test
  void forceAndReplayDefaultToFalseWhenAbsent() throws Exception {
    ReviewRequestedEnvelope e = EnvelopeSerde.fromBytes(without("force"));
    assertThat(e.force()).isFalse();

    ReviewRequestedEnvelope e2 = EnvelopeSerde.fromBytes(without("replay"));
    assertThat(e2.replay()).isFalse();
  }

  // ---- tolerant reader ----

  @Test
  void unknownExtraFieldsAreIgnored() throws Exception {
    ObjectNode node = (ObjectNode) JSON.readTree(VALID);
    node.put("future_field", "x");
    node.set("future_object", JSON.readTree("{\"a\": [1, 2, 3]}"));

    assertThat(EnvelopeSerde.fromBytes(JSON.writeValueAsBytes(node)).prNumber()).isEqualTo(17);
  }

  // ---- required fields: one case per field ----

  @ParameterizedTest
  @ValueSource(
      strings = {
        "schema_version",
        "event_id",
        "delivery_id",
        "type",
        "occurred_at",
        "installation_id",
        "repo_full_name",
        "pr_number",
        "head_sha"
      })
  void missingRequiredFieldIsRejectedAndNamed(String field) throws Exception {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(without(field)))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining(field + " missing");
  }

  @ParameterizedTest
  @ValueSource(strings = {"event_id", "delivery_id", "repo_full_name", "head_sha"})
  void blankStringCountsAsMissing(String field) throws Exception {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(with(field, "  ")))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining(field + " missing");
  }

  @Test
  void explicitJsonNullCountsAsMissing() throws Exception {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(with("pr_number", null)))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining("pr_number missing");
  }

  @Test
  void reportsAllProblemsAtOnce() throws Exception {
    ObjectNode node = (ObjectNode) JSON.readTree(VALID);
    node.remove("event_id");
    node.remove("pr_number");

    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(JSON.writeValueAsBytes(node)))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining("event_id missing")
        .hasMessageContaining("pr_number missing");
  }

  // ---- schema_version ----

  @ParameterizedTest
  @ValueSource(ints = {0, 2, -1, 99})
  void schemaVersionOtherThanOneIsRejected(int version) throws Exception {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(with("schema_version", version)))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining("schema_version must equal 1");
  }

  // ---- per-type matrix: head_sha ----

  @Test
  void reviewRequestedRequiresHeadSha() {
    assertThatCode(() -> EnvelopeValidator.validate(envelope("REVIEW_REQUESTED", "abc123")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> EnvelopeValidator.validate(envelope("REVIEW_REQUESTED", null)))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining("head_sha missing");
    assertThatThrownBy(() -> EnvelopeValidator.validate(envelope("REVIEW_REQUESTED", "")))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining("head_sha missing");
  }

  @Test
  void nullTypeIsReportedAndDoesNotCrashPerTypeRules() {
    assertThatThrownBy(() -> EnvelopeValidator.validate(envelope(null, "abc123")))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining("type missing");
  }

  // ---- everything else that makes bytes untrustworthy is one typed failure ----

  @Test
  void jsonNullPayloadIsRejected() {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(bytes("null")))
        .isInstanceOf(EnvelopeValidationException.class);
  }

  @Test
  void nullOrEmptyRecordValueIsRejected() {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(null))
        .isInstanceOf(EnvelopeValidationException.class);
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(new byte[0]))
        .isInstanceOf(EnvelopeValidationException.class);
  }

  @Test
  void malformedJsonIsRejectedWithCause() {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(bytes("{not json")))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasCauseInstanceOf(java.io.IOException.class);
  }

  @Test
  void unknownTypeValueIsRejected() throws Exception {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(with("type", "SOMETHING_NEW")))
        .isInstanceOf(EnvelopeValidationException.class);
  }

  @Test
  void wronglyTypedFieldIsRejected() throws Exception {
    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(with("pr_number", "not-a-number")))
        .isInstanceOf(EnvelopeValidationException.class);
  }

  /**
   * §3.3 rule 1: a retyped field is a contract break, so Jackson's lenient scalar coercions must be
   * off. Each case would otherwise bind to a plausible-but-wrong value (1.9 -> 1, "17" -> 17).
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          schema_version | 1.9
          schema_version | "1"
          pr_number      | 17.9
          pr_number      | "17"
          installation_id| "12345"
          installation_id| 12345.5
          force          | "true"
          force          | 1
          replay         | "false"
          event_id       | 5
          delivery_id    | true
          type           | 0
          type           | 1
          occurred_at    | 1783245600
          occurred_at    | 1783245600000
          occurred_at    | 1783245600.5
          occurred_at    | true
          occurred_at    | "yesterday"
          """)
  void lenientScalarCoercionsAreRejected(String field, String jsonLiteral) throws Exception {
    ObjectNode node = (ObjectNode) JSON.readTree(VALID);
    node.set(field.trim(), JSON.readTree(jsonLiteral.trim()));

    assertThatThrownBy(() -> EnvelopeSerde.fromBytes(JSON.writeValueAsBytes(node)))
        .isInstanceOf(EnvelopeValidationException.class);
  }
}
