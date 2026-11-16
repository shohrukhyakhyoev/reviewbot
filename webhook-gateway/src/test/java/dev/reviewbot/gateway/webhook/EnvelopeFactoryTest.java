package dev.reviewbot.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.shared.envelope.EnvelopeSerde;
import dev.reviewbot.shared.envelope.EnvelopeValidationException;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;

class EnvelopeFactoryTest {

  private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
  private static final UUID EVENT_ID = new UUID(0, 1);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final EnvelopeFactory factory = new EnvelopeFactory(() -> EVENT_ID);

  // Compares the wire bytes, so a renamed or retyped field fails here and not at a consumer.
  @ParameterizedTest
  @ValueSource(strings = {"opened", "synchronize", "reopened", "ready_for_review"})
  void eachAcceptedActionProducesItsGoldenEnvelope(String action) throws IOException {
    PullRequestPayload payload =
        PullRequestPayloadReader.extract(Fixtures.tree("pull_request_" + action));

    byte[] wire = EnvelopeSerde.toBytes(factory.build(DELIVERY_ID, payload));

    assertThat(JSON.readTree(wire)).isEqualTo(golden("envelope_" + action));
  }

  @Test
  void theBuiltEnvelopePassesTheConsumersOwnValidation() {
    PullRequestPayload payload =
        PullRequestPayloadReader.extract(Fixtures.tree("pull_request_opened"));

    ReviewRequestedEnvelope built = factory.build(DELIVERY_ID, payload);

    assertThat(EnvelopeSerde.fromBytes(EnvelopeSerde.toBytes(built))).isEqualTo(built);
  }

  @Test
  void everyEnvelopeGetsItsOwnEventId() {
    EnvelopeFactory real = new EnvelopeFactory();
    PullRequestPayload payload =
        PullRequestPayloadReader.extract(Fixtures.tree("pull_request_opened"));

    assertThat(real.build(DELIVERY_ID, payload).eventId())
        .isNotEqualTo(real.build(DELIVERY_ID, payload).eventId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  "})
  void aBlankDeliveryIdIsRefusedBeforeItCanBeProduced(String deliveryId) {
    PullRequestPayload payload =
        PullRequestPayloadReader.extract(Fixtures.tree("pull_request_opened"));

    assertThatThrownBy(() -> factory.build(deliveryId, payload))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining("delivery_id");
  }

  @Test
  void aNullDeliveryIdIsRefusedToo() {
    PullRequestPayload payload =
        PullRequestPayloadReader.extract(Fixtures.tree("pull_request_opened"));

    assertThatThrownBy(() -> factory.build(null, payload))
        .isInstanceOf(EnvelopeValidationException.class)
        .hasMessageContaining("delivery_id");
  }

  private static JsonNode golden(String name) throws IOException {
    try (InputStream in = EnvelopeFactoryTest.class.getResourceAsStream("/golden/" + name + ".json")) {
      return JSON.readTree(in);
    }
  }
}
