package dev.reviewbot.shared.failure;

/**
 * Never retried. E8 lists this in the breakers' {@code ignoreExceptions}: a wave of 404s must not
 * pause the system (§19.9), so nothing that should stay breaker-invisible may use another type.
 */
public class TerminalReviewException extends ReviewFailureException {

  private static final long serialVersionUID = 1L;

  public TerminalReviewException(FailureClass failureClass, String message) {
    this(failureClass, message, null, null);
  }

  public TerminalReviewException(FailureClass failureClass, String message, Throwable cause) {
    this(failureClass, message, cause, null);
  }

  public TerminalReviewException(
      FailureClass failureClass, String message, Throwable cause, ProviderError providerError) {
    super(failureClass, FailureKind.TERMINAL, message, cause, providerError);
  }
}
