package dev.reviewbot.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PullRequestPayloadReaderTest {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  @Test
  void readsEveryFieldFromAnOpenedPayload() {
    PullRequestPayload payload = PullRequestPayloadReader.extract(Fixtures.tree("pull_request_opened"));

    assertThat(payload)
        .isEqualTo(
            new PullRequestPayload(
                "opened",
                false,
                12345,
                "acme/app",
                17,
                "9f2c1b7e4d3a8c6b5e0f1a2d3c4b5a69788f0e1d",
                Instant.parse("2026-07-05T10:00:00Z")));
  }

  @Test
  void readsTheDraftFlag() {
    assertThat(PullRequestPayloadReader.extract(Fixtures.tree("pull_request_ready_for_review")).draft())
        .isTrue();
  }

  @Test
  void aMissingDraftFlagMeansNotDraft() {
    JsonNode root = without("/pull_request/draft");

    assertThat(PullRequestPayloadReader.extract(root).draft()).isFalse();
  }

  @Test
  void aNullDraftFlagMeansNotDraft() {
    JsonNode root = with("/pull_request/draft", JSON.nullNode());

    assertThat(PullRequestPayloadReader.extract(root).draft()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/action",
        "/installation/id",
        "/repository/full_name",
        "/pull_request/number",
        "/pull_request/head/sha",
        "/pull_request/updated_at"
      })
  void everyRequiredFieldIsRejectedWhenAbsentOrNull(String pointer) {
    assertMalformed(without(pointer), pointer);
    assertMalformed(with(pointer, JSON.nullNode()), pointer);
  }

  // Wrong shapes, not just absence: Jackson would otherwise coerce or default these silently.
  @ParameterizedTest
  @MethodSource("unusableValues")
  void unusableValuesAreRejected(String pointer, JsonNode value) {
    assertMalformed(with(pointer, value), pointer);
  }

  static Stream<Arguments> unusableValues() {
    return Stream.of(
        arguments("/pull_request/draft", JSON.textNode("true")),
        arguments("/pull_request/draft", JSON.numberNode(1)),
        arguments("/action", JSON.textNode(" ")),
        arguments("/action", JSON.numberNode(1)),
        arguments("/installation/id", JSON.textNode("12345")),
        arguments("/installation/id", JSON.numberNode(0)),
        arguments("/installation/id", JSON.numberNode(1.5)),
        arguments("/repository/full_name", JSON.textNode("")),
        arguments("/pull_request/number", JSON.numberNode(-1)),
        arguments("/pull_request/number", JSON.numberNode(Integer.MAX_VALUE + 1L)),
        arguments("/pull_request/head/sha", JSON.numberNode(7)),
        arguments("/pull_request/updated_at", JSON.textNode("yesterday")),
        arguments("/pull_request/updated_at", JSON.numberNode(1_783_245_600)));
  }

  @Test
  void aParentThatIsNotAnObjectCountsAsMissing() {
    JsonNode root = with("/pull_request", JSON.textNode("not an object"));

    assertMalformed(root, "/pull_request/number");
  }

  @Test
  void theFixtureWithoutHeadShaIsRejectedByName() {
    assertMalformed(Fixtures.tree("pull_request_opened_missing_head_sha"), "/pull_request/head/sha");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json", "[]", "\"text\"", "null", "{\"action\":"})
  void bodyThatIsNotAJsonObjectIsRejected(String body) {
    assertThatThrownBy(() -> PullRequestPayloadReader.parse(body.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOfSatisfying(
            MalformedPayloadException.class, e -> assertThat(e.field()).isEqualTo("<body>"));
  }

  @Test
  void parseErrorNeverQuotesThePayload() {
    byte[] secretish = "{\"token\":\"s3cr3t-value\"".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> PullRequestPayloadReader.parse(secretish))
        .isInstanceOf(MalformedPayloadException.class)
        .hasMessageNotContaining("s3cr3t-value")
        .hasNoCause();
  }

  @Test
  void actionIsReadWithoutTheOtherFields() {
    assertThat(PullRequestPayloadReader.action(JSON.objectNode().put("action", "closed")))
        .isEqualTo("closed");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"action\":null}", "{\"action\":7}"})
  void anUnreadableActionIsTheEmptyString(String json) {
    JsonNode root = PullRequestPayloadReader.parse(json.getBytes(StandardCharsets.UTF_8));

    assertThat(PullRequestPayloadReader.action(root)).isEmpty();
  }

  private static void assertMalformed(JsonNode root, String expectedField) {
    assertThatThrownBy(() -> PullRequestPayloadReader.extract(root))
        .isInstanceOfSatisfying(
            MalformedPayloadException.class,
            e -> assertThat(e.field()).isEqualTo(expectedField));
  }

  private static JsonNode without(String pointer) {
    ObjectNode root = (ObjectNode) Fixtures.tree("pull_request_opened");
    parentOf(root, pointer).remove(fieldOf(pointer));
    return root;
  }

  private static JsonNode with(String pointer, JsonNode value) {
    ObjectNode root = (ObjectNode) Fixtures.tree("pull_request_opened");
    parentOf(root, pointer).set(fieldOf(pointer), value);
    return root;
  }

  private static ObjectNode parentOf(ObjectNode root, String pointer) {
    return (ObjectNode) root.at(pointer.substring(0, pointer.lastIndexOf('/')));
  }

  private static String fieldOf(String pointer) {
    return pointer.substring(pointer.lastIndexOf('/') + 1);
  }
}
