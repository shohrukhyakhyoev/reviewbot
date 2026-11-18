package dev.reviewbot.gateway.webhook;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;

/** {@code POST /webhook}; the HMAC filter has already verified the request and cached its body. */
@RestController
class WebhookController {

  private final WebhookIntake intake;
  private final WebhookEnqueuer enqueuer;
  private final Clock clock;

  WebhookController(WebhookIntake intake, WebhookEnqueuer enqueuer, Clock clock) {
    this.intake = intake;
    this.enqueuer = enqueuer;
    this.clock = clock;
  }

  @PostMapping("/webhook")
  ResponseEntity<Void> webhook(
      @RequestHeader(name = "X-GitHub-Event", required = false) String eventType,
      @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId,
      @RequestBody byte[] body) {
    Instant receivedAt = clock.instant();

    Optional<ReviewRequestedEnvelope> toEnqueue = intake.handle(eventType, deliveryId, body);
    // Ignored or malformed: retrying cannot change the outcome, so GitHub must see success (§6.1.2).
    if (toEnqueue.isEmpty()) {
      return ResponseEntity.ok().build();
    }
    return switch (enqueuer.enqueue(toEnqueue.get(), receivedAt)) {
      // A duplicate is already durably enqueued (§6.1.4a): success too, not an error.
      case ENQUEUED, DUPLICATE -> ResponseEntity.ok().build();
      case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    };
  }
}
