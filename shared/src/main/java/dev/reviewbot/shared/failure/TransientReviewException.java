package dev.reviewbot.shared.failure;

/** Retried on the error schedule; counts toward the breaker (§11.2). */
public class TransientReviewException extends ReviewFailureException {

  private static final long serialVersionUID = 1L;

  public TransientReviewException(FailureClass failureClass, String message) {
    this(failureClass, message, null, null);
  }

  public TransientReviewException(FailureClass failureClass, String message, Throwable cause) {
    this(failureClass, message, cause, null);
  }

  public TransientReviewException(
      FailureClass failureClass, String message, Throwable cause, ProviderError providerError) {
    super(failureClass, FailureKind.TRANSIENT, message, cause, providerError);
  }
}
