package dev.reviewbot.shared.failure;

import java.util.Objects;
import java.util.Optional;

/**
 * A failure that already knows its class. Carries the {@link ProviderError} it came from, when
 * there was one, so the DLQ envelope (§10.2) can report status and body snippet.
 */
public abstract class ReviewFailureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final FailureClass failureClass;
  private final transient ProviderError providerError;

  ReviewFailureException(
      FailureClass failureClass,
      FailureKind expected,
      String message,
      Throwable cause,
      ProviderError providerError) {
    super(message, cause);
    this.failureClass = Objects.requireNonNull(failureClass, "failureClass").requireKind(expected);
    this.providerError = providerError;
  }

  public FailureClass failureClass() {
    return failureClass;
  }

  public Optional<ProviderError> providerError() {
    return Optional.ofNullable(providerError);
  }
}
