package dev.reviewbot.shared.failure;

import java.util.Optional;

import dev.reviewbot.shared.envelope.EnvelopeValidationException;
import dev.reviewbot.shared.headers.HeaderDecodeException;

/**
 * The drainer's catch-all (I5): any throwable becomes exactly one {@link Outcome}, identically in
 * both workers (C-2). Anything unrecognised is retried as {@code UNEXPECTED} rather than dropped.
 */
public final class FailureClassifier {

  // Bounds the cause walk against a cyclic chain.
  private static final int MAX_CAUSE_DEPTH = 16;

  private FailureClassifier() {}

  /**
   * Looks through wrappers ({@code CompletionException}, {@code ExecutionException}, proxies) for
   * the outermost recognised failure, so a wrapped 404 stays terminal and a wrapped outage stays
   * parked instead of burning retries as {@code UNEXPECTED}.
   */
  public static Outcome classify(Throwable t) {
    Throwable current = t;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      Optional<Outcome> known = recognise(current);
      if (known.isPresent()) {
        return known.get();
      }
      current = current.getCause();
    }
    return new Outcome.Transient(FailureClass.UNEXPECTED, t);
  }

  private static Optional<Outcome> recognise(Throwable t) {
    if (t instanceof TerminalReviewException e) {
      return Optional.of(new Outcome.Terminal(e.failureClass(), e));
    }
    if (t instanceof TransientReviewException e) {
      return Optional.of(new Outcome.Transient(e.failureClass(), e));
    }
    if (t instanceof DeferReviewException e) {
      return Optional.of(new Outcome.Defer(e.failureClass(), e.notBefore().orElse(null), e));
    }
    if (t instanceof DependencyUnavailableException e) {
      return Optional.of(new Outcome.BreakerParked(e.dependency()));
    }
    // A bad envelope or header can never succeed on retry, so both are poison (§10.1).
    if (t instanceof EnvelopeValidationException || t instanceof HeaderDecodeException) {
      return Optional.of(new Outcome.Terminal(FailureClass.POISON_DESERIALIZATION, t));
    }
    return Optional.empty();
  }
}
