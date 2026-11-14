package dev.reviewbot.shared.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class OutcomeTest {

  private static final RuntimeException CAUSE = new RuntimeException("boom");

  @Test
  void eachVariantAcceptsItsOwnKind() {
    assertThat(Outcome.SUCCESS).isNotNull();
    assertThat(new Outcome.Skip(FailureClass.PR_CLOSED).classIfAny())
        .contains(FailureClass.PR_CLOSED);
    assertThat(Outcome.Skip.NO_CLASS.classIfAny()).isEmpty();
    assertThat(new Outcome.Defer(FailureClass.LOCK_HELD, Instant.EPOCH, CAUSE).notBeforeIfAny())
        .contains(Instant.EPOCH);
    assertThat(new Outcome.Defer(FailureClass.LOCK_HELD, null, CAUSE).notBeforeIfAny()).isEmpty();
    assertThat(new Outcome.Transient(FailureClass.AMBIGUOUS_POST, CAUSE).exception())
        .isSameAs(CAUSE);
    assertThat(new Outcome.Terminal(FailureClass.PR_NOT_FOUND, CAUSE).failureClass())
        .isEqualTo(FailureClass.PR_NOT_FOUND);
    assertThat(new Outcome.BreakerParked(Dependency.POSTGRES).dependency())
        .isEqualTo(Dependency.POSTGRES);
  }

  @Test
  void exhaustionBuildsATerminalOutcomeFromTheTransientOne() {
    Outcome.Transient t = new Outcome.Transient(FailureClass.GITHUB_UNAVAILABLE, CAUSE);

    Outcome.Terminal exhausted = new Outcome.Terminal(t.failureClass().exhausted(), t.exception());

    assertThat(exhausted.failureClass().wire()).isEqualTo("EXHAUSTED_GITHUB_UNAVAILABLE");
  }

  @Test
  void variantsRejectAClassOfTheWrongKind() {
    assertThatThrownBy(() -> new Outcome.Skip(FailureClass.PR_NOT_FOUND))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Outcome.Defer(FailureClass.PR_CLOSED, null, CAUSE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Outcome.Transient(FailureClass.LOCK_HELD, CAUSE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Outcome.Terminal(FailureClass.GITHUB_UNAVAILABLE, CAUSE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void variantsRejectMissingPayload() {
    assertThatThrownBy(() -> new Outcome.Terminal(FailureClass.PR_NOT_FOUND, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Outcome.Transient(FailureClass.AMBIGUOUS_POST, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Outcome.Defer(null, null, CAUSE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Outcome.Defer(FailureClass.LOCK_HELD, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Outcome.BreakerParked(null))
        .isInstanceOf(NullPointerException.class);
  }
}
