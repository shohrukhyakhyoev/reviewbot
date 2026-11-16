package dev.reviewbot.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.gateway.webhook.WebhookDecision.Accept;
import dev.reviewbot.gateway.webhook.WebhookDecision.Ignore;
import dev.reviewbot.gateway.webhook.WebhookDecision.IgnoreReason;
import dev.reviewbot.gateway.webhook.WebhookDecision.Malformed;

class EventFilterTest {

  private final EventFilter skippingDrafts = new EventFilter(false);
  private final EventFilter reviewingDrafts = new EventFilter(true);

  @ParameterizedTest
  @ValueSource(strings = {"opened", "synchronize", "reopened"})
  void acceptsTheNonDraftActions(String action) {
    WebhookDecision decision =
        skippingDrafts.decide("pull_request", Fixtures.bytes("pull_request_" + action));

    assertThat(decision).isInstanceOfSatisfying(Accept.class, a -> assertThat(a.payload().action()).isEqualTo(action));
  }

  // The fixture is a draft (draft=true): the draft rule must not swallow ready_for_review.
  @Test
  void readyForReviewIsAcceptedEvenThoughThePayloadSaysDraft() {
    byte[] body = Fixtures.bytes("pull_request_ready_for_review");

    assertThat(skippingDrafts.decide("pull_request", body)).isInstanceOf(Accept.class);
    assertThat(reviewingDrafts.decide("pull_request", body)).isInstanceOf(Accept.class);
  }

  // A push to a draft PR is a draft synchronize, the commonest draft delivery, so every action counts.
  @ParameterizedTest
  @ValueSource(strings = {"opened", "synchronize", "reopened"})
  void aDraftIsSkippedByDefaultAndReviewedWhenOnForEveryNonReadyAction(String action) {
    ObjectNode root = (ObjectNode) Fixtures.tree("pull_request_opened");
    root.put("action", action);
    ((ObjectNode) root.get("pull_request")).put("draft", true);

    assertThat(skippingDrafts.decide("pull_request", bytes(root)))
        .isEqualTo(new Ignore(IgnoreReason.DRAFT));
    assertThat(reviewingDrafts.decide("pull_request", bytes(root))).isInstanceOf(Accept.class);
  }

  @Test
  void aDraftIsSkippedByDefault() {
    WebhookDecision decision =
        skippingDrafts.decide("pull_request", Fixtures.bytes("pull_request_opened_draft"));

    assertThat(decision).isEqualTo(new Ignore(IgnoreReason.DRAFT));
  }

  @Test
  void aDraftIsReviewedWhenReviewDraftsIsOn() {
    WebhookDecision decision =
        reviewingDrafts.decide("pull_request", Fixtures.bytes("pull_request_opened_draft"));

    assertThat(decision).isInstanceOf(Accept.class);
  }

  @Test
  void aNonDraftIsReviewedWhateverReviewDraftsSays() {
    byte[] body = Fixtures.bytes("pull_request_opened");

    assertThat(skippingDrafts.decide("pull_request", body)).isInstanceOf(Accept.class);
    assertThat(reviewingDrafts.decide("pull_request", body)).isInstanceOf(Accept.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"push", "ping", "issue_comment", "check_run", ""})
  void otherEventTypesAreIgnored(String eventType) {
    assertThat(skippingDrafts.decide(eventType, Fixtures.bytes("push")))
        .isEqualTo(new Ignore(IgnoreReason.UNSUPPORTED_EVENT));
  }

  @Test
  void aMissingEventHeaderIsIgnored() {
    assertThat(skippingDrafts.decide(null, Fixtures.bytes("ping")))
        .isEqualTo(new Ignore(IgnoreReason.UNSUPPORTED_EVENT));
  }

  // The body of an event we do not act on is never parsed, so garbage there cannot fail us.
  @Test
  void anIgnoredEventTypeIsNotParsed() {
    assertThat(skippingDrafts.decide("ping", "not json".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(new Ignore(IgnoreReason.UNSUPPORTED_EVENT));
  }

  @Test
  void aClosedPullRequestIsIgnored() {
    assertThat(skippingDrafts.decide("pull_request", Fixtures.bytes("pull_request_closed")))
        .isEqualTo(new Ignore(IgnoreReason.UNSUPPORTED_ACTION));
  }

  // converted_to_draft is the trap: it is a pull_request event on a draft, but not a review request.
  @ParameterizedTest
  @ValueSource(strings = {"closed", "edited", "labeled", "converted_to_draft", "review_requested", ""})
  void otherPullRequestActionsAreIgnored(String action) {
    ObjectNode root = (ObjectNode) Fixtures.tree("pull_request_opened");
    root.put("action", action);

    assertThat(skippingDrafts.decide("pull_request", bytes(root)))
        .isEqualTo(new Ignore(IgnoreReason.UNSUPPORTED_ACTION));
  }

  // An action we ignore must not be reclassified as malformed just because other fields are absent.
  @Test
  void anIgnoredActionNeverNeedsTheOtherFields() {
    assertThat(skippingDrafts.decide("pull_request", "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(new Ignore(IgnoreReason.UNSUPPORTED_ACTION));
  }

  @Test
  void anAcceptedActionMissingARequiredFieldIsMalformed() {
    assertThat(
            skippingDrafts.decide(
                "pull_request", Fixtures.bytes("pull_request_opened_missing_head_sha")))
        .isEqualTo(new Malformed("/pull_request/head/sha"));
  }

  @Test
  void aPullRequestBodyThatIsNotJsonIsMalformed() {
    assertThat(skippingDrafts.decide("pull_request", "not json".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(new Malformed("<body>"));
  }

  @Test
  void aMalformedDraftIsMalformedNotSkipped() {
    ObjectNode root = (ObjectNode) Fixtures.tree("pull_request_opened_draft");
    ((ObjectNode) root.get("installation")).removeAll();

    assertThat(skippingDrafts.decide("pull_request", bytes(root)))
        .isEqualTo(new Malformed("/installation/id"));
  }

  private static byte[] bytes(ObjectNode root) {
    return root.toString().getBytes(StandardCharsets.UTF_8);
  }
}
