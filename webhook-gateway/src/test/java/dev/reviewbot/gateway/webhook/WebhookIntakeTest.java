package dev.reviewbot.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.gateway.webhook.WebhookDecision.IgnoreReason;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;

class WebhookIntakeTest {

  private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";

  private final RecordingMetrics metrics = new RecordingMetrics();
  private final WebhookIntake intake =
      new WebhookIntake(
          new EventFilter(false), new EnvelopeFactory(() -> new UUID(0, 1)), metrics);

  @Test
  void anAcceptedDeliveryYieldsTheEnvelopeAndIsCounted() {
    Optional<ReviewRequestedEnvelope> result =
        intake.handle("pull_request", DELIVERY_ID, Fixtures.bytes("pull_request_synchronize"));

    assertThat(result)
        .hasValueSatisfying(
            envelope -> {
              assertThat(envelope.type()).isEqualTo(EventType.REVIEW_REQUESTED);
              assertThat(envelope.deliveryId()).isEqualTo(DELIVERY_ID);
              assertThat(envelope.headSha()).isEqualTo("c4e8a1d07b925f36e1d0c8b7a6958473625140ff");
            });
    assertThat(metrics.events).containsExactly("accepted");
  }

  @Test
  void anIgnoredDeliveryYieldsNothingAndIsCountedWithItsReason() {
    assertThat(intake.handle("push", DELIVERY_ID, Fixtures.bytes("push"))).isEmpty();
    assertThat(intake.handle("ping", DELIVERY_ID, Fixtures.bytes("ping"))).isEmpty();
    assertThat(intake.handle("pull_request", DELIVERY_ID, Fixtures.bytes("pull_request_closed")))
        .isEmpty();
    assertThat(
            intake.handle("pull_request", DELIVERY_ID, Fixtures.bytes("pull_request_opened_draft")))
        .isEmpty();

    assertThat(metrics.events)
        .containsExactly(
            "ignored:" + IgnoreReason.UNSUPPORTED_EVENT,
            "ignored:" + IgnoreReason.UNSUPPORTED_EVENT,
            "ignored:" + IgnoreReason.UNSUPPORTED_ACTION,
            "ignored:" + IgnoreReason.DRAFT);
  }

  @Test
  void aMalformedDeliveryYieldsNothingAndIsCountedWithTheFieldNotTheValue() {
    Optional<ReviewRequestedEnvelope> result =
        intake.handle(
            "pull_request", DELIVERY_ID, Fixtures.bytes("pull_request_opened_missing_head_sha"));

    assertThat(result).isEmpty();
    assertThat(metrics.events)
        .containsExactly("malformed:" + DELIVERY_ID + ":/pull_request/head/sha");
  }

  @Test
  void aBodyThatIsNotJsonNeverThrows() {
    Optional<ReviewRequestedEnvelope> result =
        intake.handle("pull_request", DELIVERY_ID, "not json".getBytes(StandardCharsets.UTF_8));

    assertThat(result).isEmpty();
    assertThat(metrics.events).containsExactly("malformed:" + DELIVERY_ID + ":<body>");
  }

  // A verified request without X-GitHub-Delivery: not counted as accepted, and not thrown.
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void aMissingDeliveryIdIsMalformedUnderTheHeaderName(String deliveryId) {
    Optional<ReviewRequestedEnvelope> result =
        intake.handle("pull_request", deliveryId, Fixtures.bytes("pull_request_opened"));

    assertThat(result).isEmpty();
    assertThat(metrics.events).containsExactly("malformed:" + deliveryId + ":X-GitHub-Delivery");
  }

  // The header is only needed once a delivery is accepted; an ignored one is acked regardless.
  @Test
  void anIgnoredDeliveryDoesNotNeedADeliveryId() {
    assertThat(intake.handle("ping", null, Fixtures.bytes("ping"))).isEmpty();

    assertThat(metrics.events).containsExactly("ignored:" + IgnoreReason.UNSUPPORTED_EVENT);
  }

  @Test
  void thePublicConstructorWiresTheRealPartsFromTheDraftSetting() {
    byte[] draft = Fixtures.bytes("pull_request_opened_draft");

    assertThat(new WebhookIntake(false).handle("pull_request", DELIVERY_ID, draft)).isEmpty();
    assertThat(new WebhookIntake(true).handle("pull_request", DELIVERY_ID, draft)).isPresent();
  }

  private static final class RecordingMetrics implements WebhookMetrics {
    final List<String> events = new ArrayList<>();

    @Override
    public void accepted() {
      events.add("accepted");
    }

    @Override
    public void ignored(IgnoreReason reason) {
      events.add("ignored:" + reason);
    }

    @Override
    public void malformed(String deliveryId, String field) {
      events.add("malformed:" + deliveryId + ":" + field);
    }

    @Override
    public void answered(int status, Duration latency) {
      events.add("answered:" + status);
    }

    @Override
    public void produceFailed(Throwable cause) {
      events.add("produceFailed:" + cause.getClass().getSimpleName());
    }
  }
}
