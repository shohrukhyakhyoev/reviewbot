package dev.reviewbot.shared.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.shared.failure.FailureKind.Route;

class FailureClassTest {

  static Stream<Arguments> section101Rows() {
    return Stream.of(
        Arguments.of(FailureClass.POISON_DESERIALIZATION, FailureKind.TERMINAL, Route.DLQ, false),
        Arguments.of(FailureClass.PR_NOT_FOUND, FailureKind.TERMINAL, Route.DLQ, false),
        Arguments.of(FailureClass.INSTALLATION_REVOKED, FailureKind.TERMINAL, Route.DLQ, false),
        Arguments.of(FailureClass.LLM_REQUEST, FailureKind.TERMINAL, Route.DLQ, false),
        Arguments.of(FailureClass.DIFF_TOO_LARGE, FailureKind.TERMINAL, Route.DLQ, false),
        Arguments.of(
            FailureClass.MISSING_OR_INVALID_API_KEY, FailureKind.TERMINAL, Route.DLQ, false),
        Arguments.of(FailureClass.AUTHOR_NOT_PERMITTED, FailureKind.SKIP, Route.NONE, false),
        Arguments.of(FailureClass.PR_CLOSED, FailureKind.SKIP, Route.NONE, false),
        Arguments.of(FailureClass.SUSPENDED, FailureKind.SKIP, Route.NONE, false),
        Arguments.of(FailureClass.COMMAND_NOT_PERMITTED, FailureKind.SKIP, Route.NONE, false),
        Arguments.of(
            FailureClass.GITHUB_UNAVAILABLE,
            FailureKind.TRANSIENT,
            Route.RETRY_ERROR_SCHEDULE,
            true),
        Arguments.of(
            FailureClass.ANTHROPIC_UNAVAILABLE,
            FailureKind.TRANSIENT,
            Route.RETRY_ERROR_SCHEDULE,
            true),
        Arguments.of(
            FailureClass.AMBIGUOUS_POST, FailureKind.TRANSIENT, Route.RETRY_ERROR_SCHEDULE, true),
        Arguments.of(
            FailureClass.PROVIDER_THROTTLE, FailureKind.DEFER, Route.RETRY_DEFER_SCHEDULE, true),
        Arguments.of(FailureClass.RATE_LIMIT, FailureKind.DEFER, Route.RETRY_DEFER_SCHEDULE, true),
        Arguments.of(FailureClass.LOCK_HELD, FailureKind.DEFER, Route.RETRY_DEFER_SCHEDULE, true),
        Arguments.of(
            FailureClass.BREAKER_PARKED, FailureKind.BREAKER_REMNANT, Route.RETRY_COOLDOWN, false),
        Arguments.of(FailureClass.COULD_NOT_SCHEDULE, FailureKind.TERMINAL, Route.DLQ, false));
  }

  @ParameterizedTest
  @MethodSource("section101Rows")
  void everyRowHasItsKindRouteAndCounterRule(
      FailureClass c, FailureKind kind, Route route, boolean countsAttempts) {
    assertThat(c.kind()).isEqualTo(kind);
    assertThat(c.kind().route()).isEqualTo(route);
    assertThat(c.kind().countsAttempts()).isEqualTo(countsAttempts);
  }

  @Test
  void infraRowParksOnTheBreakerWithoutCounters() {
    assertThat(FailureKind.INFRA.route()).isEqualTo(Route.PARK_ON_BREAKER);
    assertThat(FailureKind.INFRA.countsAttempts()).isFalse();
  }

  @Test
  void wireStringIsTheConstantName() {
    assertThat(FailureClass.PR_NOT_FOUND.wire()).isEqualTo("PR_NOT_FOUND");
    assertThat(FailureClass.COULD_NOT_SCHEDULE.wire()).isEqualTo("COULD_NOT_SCHEDULE");
  }

  @Test
  void everyKnownClassRoundTripsThroughItsWireString() {
    for (FailureClass c : FailureClass.known()) {
      assertThat(FailureClass.parse(c.wire())).isEqualTo(c);
    }
  }

  @Test
  void exhaustedIsTerminalAndPrefixed() {
    FailureClass e = FailureClass.GITHUB_UNAVAILABLE.exhausted();

    assertThat(e.wire()).isEqualTo("EXHAUSTED_GITHUB_UNAVAILABLE");
    assertThat(e.kind()).isEqualTo(FailureKind.TERMINAL);
    assertThat(FailureClass.parse("EXHAUSTED_GITHUB_UNAVAILABLE")).isEqualTo(e);
  }

  @Test
  void poisonExhaustionIsAKnownClass() {
    assertThat(FailureClass.parse("EXHAUSTED_POISON")).isEqualTo(FailureClass.EXHAUSTED_POISON);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PR_NOT_FOUND", "PROVIDER_THROTTLE", "BREAKER_PARKED", "PR_CLOSED"})
  void onlyTransientClassesExhaust(String wire) {
    assertThatThrownBy(() -> FailureClass.parse(wire).exhausted())
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"NOPE", "", "EXHAUSTED_", "EXHAUSTED_PR_NOT_FOUND", "exhausted_github_unavailable"})
  void unknownWireStringIsRejected(String wire) {
    assertThatThrownBy(() -> FailureClass.parse(wire)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullWireStringIsRejected() {
    assertThatThrownBy(() -> FailureClass.parse(null)).isInstanceOf(NullPointerException.class);
  }
}
