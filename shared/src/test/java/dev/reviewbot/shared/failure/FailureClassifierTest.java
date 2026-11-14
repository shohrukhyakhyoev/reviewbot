package dev.reviewbot.shared.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.reviewbot.shared.envelope.EnvelopeValidationException;
import dev.reviewbot.shared.failure.ProviderError.Provider;
import dev.reviewbot.shared.headers.HeaderDecodeException;

class FailureClassifierTest {

  private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

  @Test
  void typedExceptionsKeepTheirClassAndKind() {
    var terminal = new TerminalReviewException(FailureClass.PR_NOT_FOUND, "gone");
    var transientFailure = new TransientReviewException(FailureClass.AMBIGUOUS_POST, "timeout");

    assertThat(FailureClassifier.classify(terminal))
        .isEqualTo(new Outcome.Terminal(FailureClass.PR_NOT_FOUND, terminal));
    assertThat(FailureClassifier.classify(transientFailure))
        .isEqualTo(new Outcome.Transient(FailureClass.AMBIGUOUS_POST, transientFailure));
  }

  @Test
  void deferKeepsItsNotBefore() {
    var hinted = new DeferReviewException(FailureClass.LOCK_HELD, "held", NOW, null);
    var bare = new DeferReviewException(FailureClass.RATE_LIMIT, "limited", null, null);

    assertThat(FailureClassifier.classify(hinted))
        .isEqualTo(new Outcome.Defer(FailureClass.LOCK_HELD, NOW, hinted));
    assertThat(FailureClassifier.classify(bare))
        .isEqualTo(new Outcome.Defer(FailureClass.RATE_LIMIT, null, bare));
  }

  // POSTGRES/REDIS are the infra row; GITHUB/ANTHROPIC are an open provider breaker (§7.1).
  @ParameterizedTest
  @EnumSource(Dependency.class)
  void unavailableDependencyParksWithoutCounters(Dependency dependency) {
    var e = new DependencyUnavailableException(dependency, new RuntimeException("conn"));

    assertThat(FailureClassifier.classify(e)).isEqualTo(new Outcome.BreakerParked(dependency));
  }

  @Test
  void badEnvelopeOrHeaderIsPoison() {
    var envelope = new EnvelopeValidationException("bad");
    var header = new HeaderDecodeException("bad");

    assertThat(FailureClassifier.classify(envelope))
        .isEqualTo(new Outcome.Terminal(FailureClass.POISON_DESERIALIZATION, envelope));
    assertThat(FailureClassifier.classify(header))
        .isEqualTo(new Outcome.Terminal(FailureClass.POISON_DESERIALIZATION, header));
  }

  @Test
  void anythingElseIsRetriedAsUnexpected() {
    var bug = new IllegalStateException("npe-ish");

    assertThat(FailureClassifier.classify(bug))
        .isEqualTo(new Outcome.Transient(FailureClass.UNEXPECTED, bug));
  }

  @Test
  void providerErrorsClassifyThroughToException() {
    ReviewFailureException e =
        ProviderErrorClassifier.toException(
            ProviderError.http(Provider.ANTHROPIC, 429, Map.of("retry-after", "60"), ""), NOW);

    assertThat(FailureClassifier.classify(e))
        .isEqualTo(new Outcome.Defer(FailureClass.PROVIDER_THROTTLE, NOW.plusSeconds(60), e));
  }

  @Test
  void deferKeepsTheProviderErrorForTheSlaEscalationEnvelope() {
    ReviewFailureException e =
        ProviderErrorClassifier.toException(
            ProviderError.http(Provider.ANTHROPIC, 429, Map.of(), "slow down"), NOW);

    Outcome.Defer defer = (Outcome.Defer) FailureClassifier.classify(e);

    assertThat(((ReviewFailureException) defer.exception()).providerError())
        .hasValueSatisfying(p -> assertThat(p.status()).isEqualTo(429));
  }

  @Test
  void wrappedFailuresKeepTheirRealClass() {
    var notFound = new TerminalReviewException(FailureClass.PR_NOT_FOUND, "gone");
    var outage = new DependencyUnavailableException(Dependency.GITHUB, new RuntimeException("open"));

    assertThat(FailureClassifier.classify(new CompletionException(notFound)))
        .isEqualTo(new Outcome.Terminal(FailureClass.PR_NOT_FOUND, notFound));
    assertThat(FailureClassifier.classify(new ExecutionException(new CompletionException(outage))))
        .isEqualTo(new Outcome.BreakerParked(Dependency.GITHUB));
  }

  @Test
  void outermostRecognisedFailureWins() {
    var inner = new TerminalReviewException(FailureClass.PR_NOT_FOUND, "gone");
    var outer = new TransientReviewException(FailureClass.AMBIGUOUS_POST, "timeout", inner);

    assertThat(FailureClassifier.classify(outer))
        .isEqualTo(new Outcome.Transient(FailureClass.AMBIGUOUS_POST, outer));
  }

  @Test
  void cyclicCauseChainTerminatesAsUnexpected() {
    var a = new RuntimeException("a");
    var b = new RuntimeException("b", a);
    a.initCause(b);

    assertThat(FailureClassifier.classify(a))
        .isEqualTo(new Outcome.Transient(FailureClass.UNEXPECTED, a));
  }
}
