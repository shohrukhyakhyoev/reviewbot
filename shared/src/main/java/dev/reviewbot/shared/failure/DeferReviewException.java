package dev.reviewbot.shared.failure;

import java.time.Instant;
import java.util.Optional;

/** Backpressure, not failure: retried on the defer schedule, optionally not before a given time. */
public class DeferReviewException extends ReviewFailureException {

  private static final long serialVersionUID = 1L;

  private final Instant notBefore;

  /** {@code notBefore} is null when the provider gave no usable hint. */
  public DeferReviewException(
      FailureClass failureClass, String message, Instant notBefore, ProviderError providerError) {
    super(failureClass, FailureKind.DEFER, message, null, providerError);
    this.notBefore = notBefore;
  }

  public Optional<Instant> notBefore() {
    return Optional.ofNullable(notBefore);
  }
}
