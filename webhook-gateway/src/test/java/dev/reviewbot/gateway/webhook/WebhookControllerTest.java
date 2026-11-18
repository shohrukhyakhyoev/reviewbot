package dev.reviewbot.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import dev.reviewbot.gateway.webhook.WebhookEnqueuer.Result;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;

class WebhookControllerTest {

  private static final String EVENT = "pull_request";
  private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
  private static final byte[] BODY = "{}".getBytes();
  private static final ReviewRequestedEnvelope ENVELOPE =
      new ReviewRequestedEnvelope(
          ReviewRequestedEnvelope.SCHEMA_VERSION,
          "6f1c2a8e-0d3b-4e7a-9c5f-1b2d3e4f5a6b",
          DELIVERY_ID,
          EventType.REVIEW_REQUESTED,
          Instant.parse("2026-11-18T10:15:00Z"),
          42L,
          "acme/app",
          7,
          "0123456789abcdef0123456789abcdef01234567",
          false,
          false);

  private final WebhookIntake intake = mock(WebhookIntake.class);
  private final WebhookEnqueuer enqueuer = mock(WebhookEnqueuer.class);
  private static final Instant NOW = Instant.parse("2026-11-18T10:15:30.123Z");

  private final WebhookController controller =
      new WebhookController(intake, enqueuer, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void nothingToEnqueueIs200AndNeverProduces() {
    when(intake.handle(EVENT, DELIVERY_ID, BODY)).thenReturn(Optional.empty());

    assertThat(controller.webhook(EVENT, DELIVERY_ID, BODY).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    verify(enqueuer, never()).enqueue(any(), any());
  }

  // DUPLICATE is a success for GitHub (§6.1 4a: "already durably enqueued"), not a failure.
  @ParameterizedTest
  @CsvSource({"ENQUEUED, 200", "DUPLICATE, 200", "UNAVAILABLE, 503"})
  void eachEnqueueResultMapsToItsStatus(Result result, int status) {
    when(intake.handle(EVENT, DELIVERY_ID, BODY)).thenReturn(Optional.of(ENVELOPE));
    when(enqueuer.enqueue(any(), any())).thenReturn(result);

    assertThat(controller.webhook(EVENT, DELIVERY_ID, BODY).getStatusCode().value())
        .isEqualTo(status);
  }

  @Test
  void theReceiveTimeComesFromTheClock() {
    when(intake.handle(EVENT, DELIVERY_ID, BODY)).thenReturn(Optional.of(ENVELOPE));
    when(enqueuer.enqueue(any(), any())).thenReturn(Result.ENQUEUED);

    controller.webhook(EVENT, DELIVERY_ID, BODY);

    verify(enqueuer).enqueue(ENVELOPE, NOW);
  }
}
