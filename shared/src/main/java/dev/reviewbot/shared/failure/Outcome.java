package dev.reviewbot.shared.failure;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The closed set of §7.2 drainer outcomes (I5). Each variant carries what its handler row consumes
 * and rejects a class of the wrong kind, so a handler never meets an inconsistent outcome.
 */
public sealed interface Outcome {

  Success SUCCESS = new Success();

  record Success() implements Outcome {}

  /** {@code failureClass} is null for a skip with no class. */
  record Skip(FailureClass failureClass) implements Outcome {
    public static final Skip NO_CLASS = new Skip(null);

    public Skip {
      if (failureClass != null) {
        failureClass.requireKind(FailureKind.SKIP);
      }
    }

    public Optional<FailureClass> classIfAny() {
      return Optional.ofNullable(failureClass);
    }
  }

  /**
   * {@code notBefore} is null when the provider gave no usable hint. {@code exception} is kept
   * because a defer that outlives the 2 h SLA becomes a TERMINAL whose DLQ envelope is built from it.
   */
  record Defer(FailureClass failureClass, Instant notBefore, Throwable exception)
      implements Outcome {
    public Defer {
      failureClass.requireKind(FailureKind.DEFER);
      Objects.requireNonNull(exception, "exception");
    }

    public Optional<Instant> notBeforeIfAny() {
      return Optional.ofNullable(notBefore);
    }
  }

  record Transient(FailureClass failureClass, Throwable exception) implements Outcome {
    public Transient {
      failureClass.requireKind(FailureKind.TRANSIENT);
      Objects.requireNonNull(exception, "exception");
    }
  }

  record Terminal(FailureClass failureClass, Throwable exception) implements Outcome {
    public Terminal {
      failureClass.requireKind(FailureKind.TERMINAL);
      Objects.requireNonNull(exception, "exception");
    }
  }

  /** Wire class is always {@link FailureClass#BREAKER_PARKED}; the dependency picks the cooldown. */
  record BreakerParked(Dependency dependency) implements Outcome {
    public BreakerParked {
      Objects.requireNonNull(dependency, "dependency");
    }
  }
}
