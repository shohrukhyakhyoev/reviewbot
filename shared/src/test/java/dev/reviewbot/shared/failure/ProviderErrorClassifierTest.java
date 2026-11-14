package dev.reviewbot.shared.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.shared.failure.ProviderError.Provider;
import dev.reviewbot.shared.failure.ProviderErrorClassifier.Classification;

class ProviderErrorClassifierTest {

  private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

  private static ProviderError err(Provider p, int status) {
    return ProviderError.http(p, status, Map.of(), "");
  }

  private static ProviderError err(Provider p, int status, Map<String, String> headers) {
    return ProviderError.http(p, status, headers, "");
  }

  private static Classification classify(ProviderError e) {
    return ProviderErrorClassifier.classify(e, NOW);
  }

  private static Optional<Instant> notBefore(ProviderError e) {
    return Optional.ofNullable(classify(e).notBefore());
  }

  static Stream<Arguments> statusTable() {
    return Stream.of(
        Arguments.of(Provider.GITHUB, 404, FailureClass.PR_NOT_FOUND),
        Arguments.of(Provider.GITHUB, 401, FailureClass.INSTALLATION_REVOKED),
        Arguments.of(Provider.GITHUB, 403, FailureClass.INSTALLATION_REVOKED),
        Arguments.of(Provider.GITHUB, 400, FailureClass.UNCLASSIFIED),
        Arguments.of(Provider.GITHUB, 422, FailureClass.UNCLASSIFIED),
        Arguments.of(Provider.GITHUB, 408, FailureClass.GITHUB_UNAVAILABLE),
        Arguments.of(Provider.ANTHROPIC, 408, FailureClass.ANTHROPIC_UNAVAILABLE),
        Arguments.of(Provider.GITHUB, 500, FailureClass.GITHUB_UNAVAILABLE),
        Arguments.of(Provider.GITHUB, 502, FailureClass.GITHUB_UNAVAILABLE),
        Arguments.of(Provider.GITHUB, 429, FailureClass.PROVIDER_THROTTLE),
        Arguments.of(Provider.ANTHROPIC, 400, FailureClass.LLM_REQUEST),
        Arguments.of(Provider.ANTHROPIC, 404, FailureClass.LLM_REQUEST),
        Arguments.of(Provider.ANTHROPIC, 401, FailureClass.MISSING_OR_INVALID_API_KEY),
        Arguments.of(Provider.ANTHROPIC, 403, FailureClass.MISSING_OR_INVALID_API_KEY),
        Arguments.of(Provider.ANTHROPIC, 429, FailureClass.PROVIDER_THROTTLE),
        Arguments.of(Provider.ANTHROPIC, 500, FailureClass.ANTHROPIC_UNAVAILABLE),
        Arguments.of(Provider.ANTHROPIC, 529, FailureClass.ANTHROPIC_UNAVAILABLE),
        Arguments.of(Provider.ANTHROPIC, 413, FailureClass.UNCLASSIFIED));
  }

  @ParameterizedTest
  @MethodSource("statusTable")
  void statusMapsToClass(Provider provider, int status, FailureClass expected) {
    assertThat(classify(err(provider, status)).failureClass()).isEqualTo(expected);
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void timeoutIsTransient(Provider provider) {
    FailureClass c = classify(ProviderError.noResponse(provider, new IOException("x"))).failureClass();

    assertThat(c.kind()).isEqualTo(FailureKind.TRANSIENT);
  }

  @Test
  void ambiguous403WithRateLimitHeadersDefers() {
    ProviderError e =
        ProviderError.http(
            Provider.GITHUB, 403, Map.of("X-RateLimit-Remaining", "0"), "API rate limit exceeded");

    assertThat(classify(e).failureClass()).isEqualTo(FailureClass.PROVIDER_THROTTLE);
  }

  @Test
  void ambiguous403WithRetryAfterDefers() {
    ProviderError e = err(Provider.GITHUB, 403, Map.of("Retry-After", "30"));

    assertThat(classify(e).failureClass().kind()).isEqualTo(FailureKind.DEFER);
  }

  @Test
  void ambiguous403WithRateLimitBodyOnlyDefers() {
    ProviderError e =
        ProviderError.http(
            Provider.GITHUB, 403, Map.of(), "You have exceeded a secondary rate limit.");

    assertThat(classify(e).failureClass().kind()).isEqualTo(FailureKind.DEFER);
  }

  @Test
  void permission403IsTerminal() {
    ProviderError e =
        ProviderError.http(
            Provider.GITHUB,
            403,
            Map.of("X-RateLimit-Remaining", "4999"),
            "Resource not accessible by integration");

    assertThat(classify(e).failureClass().kind()).isEqualTo(FailureKind.TERMINAL);
  }

  @Test
  void retryAfterSecondsBecomesNotBefore() {
    ProviderError e = err(Provider.ANTHROPIC, 429, Map.of("retry-after", "90"));

    assertThat(notBefore(e)).contains(NOW.plusSeconds(90));
  }

  @Test
  void retryAfterHttpDateBecomesNotBefore() {
    ProviderError e =
        err(Provider.GITHUB, 429, Map.of("Retry-After", "Fri, 13 Nov 2026 12:05:00 GMT"));

    assertThat(notBefore(e)).contains(Instant.parse("2026-11-13T12:05:00Z"));
  }

  @Test
  void resetEpochBecomesNotBefore() {
    long reset = NOW.plusSeconds(600).getEpochSecond();
    ProviderError e =
        err(
            Provider.GITHUB,
            403,
            Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset)));

    assertThat(notBefore(e)).contains(Instant.ofEpochSecond(reset));
  }

  @Test
  void retryAfterWinsOverReset() {
    ProviderError e =
        err(
            Provider.GITHUB,
            429,
            Map.of(
                "retry-after", "10", "x-ratelimit-reset", Long.toString(NOW.getEpochSecond() + 999)));

    assertThat(notBefore(e)).contains(NOW.plusSeconds(10));
  }

  @Test
  void unparseableRetryAfterFallsBackToReset() {
    long reset = NOW.plusSeconds(120).getEpochSecond();
    ProviderError e =
        err(
            Provider.GITHUB,
            429,
            Map.of("retry-after", "soon", "x-ratelimit-reset", Long.toString(reset)));

    assertThat(notBefore(e)).contains(Instant.ofEpochSecond(reset));
  }

  @ParameterizedTest
  @ValueSource(strings = {"soon", "", "-5", "0", "99999999999999999999", "Mon, 99 Foo 2026"})
  void unusableRetryAfterGivesNoNotBefore(String value) {
    assertThat(notBefore(err(Provider.GITHUB, 429, Map.of("retry-after", value)))).isEmpty();
  }

  @Test
  void pastOrAbsurdResetGivesNoNotBefore() {
    long past = NOW.minusSeconds(5).getEpochSecond();
    long farFuture = NOW.plusSeconds(10L * 86_400).getEpochSecond();

    assertThat(reset(past)).isEmpty();
    assertThat(reset(farFuture)).isEmpty();
    assertThat(reset(Long.MAX_VALUE)).isEmpty();
    assertThat(notBefore(err(Provider.GITHUB, 429, Map.of("x-ratelimit-reset", "abc")))).isEmpty();
  }

  private static Optional<Instant> reset(long epochSecond) {
    return notBefore(
        err(Provider.GITHUB, 429, Map.of("x-ratelimit-reset", Long.toString(epochSecond))));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 99, 600})
  void httpFactoryRejectsAnUnsetOrImpossibleStatus(int status) {
    assertThatThrownBy(() -> ProviderError.http(Provider.GITHUB, status, Map.of(), ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonicalConstructorRejectsAnUnsetStatusWithoutACause() {
    assertThatThrownBy(() -> new ProviderError(Provider.GITHUB, 0, Map.of(), "", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProviderError(Provider.GITHUB, 700, Map.of(), "", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonErrorStatusIsAProgrammingError() {
    assertThatThrownBy(() -> classify(err(Provider.GITHUB, 200)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toExceptionKeepsStatusBodyAndCauseForTheDlqEnvelope() {
    IOException io = new IOException("reset");
    ReviewFailureException transientFailure =
        ProviderErrorClassifier.toException(ProviderError.noResponse(Provider.GITHUB, io), NOW);
    assertThat(transientFailure).isInstanceOf(TransientReviewException.class).hasCause(io);
    assertThat(transientFailure.providerError()).isPresent();

    ReviewFailureException terminal =
        ProviderErrorClassifier.toException(
            ProviderError.http(Provider.GITHUB, 404, Map.of(), "Not Found"), NOW);
    assertThat(terminal).isInstanceOf(TerminalReviewException.class);
    assertThat(terminal.failureClass()).isEqualTo(FailureClass.PR_NOT_FOUND);
    assertThat(terminal.providerError().orElseThrow().status()).isEqualTo(404);
    assertThat(terminal.providerError().orElseThrow().bodySnippet()).isEqualTo("Not Found");

    ReviewFailureException deferred =
        ProviderErrorClassifier.toException(
            err(Provider.GITHUB, 429, Map.of("retry-after", "5")), NOW);
    assertThat(deferred).isInstanceOf(DeferReviewException.class);
    assertThat(((DeferReviewException) deferred).notBefore()).contains(NOW.plusSeconds(5));
  }

  @Test
  void bodySnippetIsCappedAndNeverPrinted() {
    String body = "secret diff line ".repeat(100);

    ProviderError e = ProviderError.http(Provider.GITHUB, 422, Map.of(), body);

    assertThat(e.bodySnippet()).hasSize(ProviderError.MAX_BODY_SNIPPET);
    assertThat(e.toString()).doesNotContain("secret");
  }

  @Test
  void exceptionRejectsAClassOfTheWrongKind() {
    assertThatThrownBy(() -> new TerminalReviewException(FailureClass.RATE_LIMIT, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TransientReviewException(FailureClass.PR_CLOSED, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DeferReviewException(FailureClass.AMBIGUOUS_POST, "x", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
