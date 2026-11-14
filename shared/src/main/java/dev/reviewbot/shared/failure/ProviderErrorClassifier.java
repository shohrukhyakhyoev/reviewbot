package dev.reviewbot.shared.failure;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

import dev.reviewbot.shared.failure.ProviderError.Provider;

/** Maps a failed provider call to its §10.1 class. Pure: the clock is a parameter. */
public final class ProviderErrorClassifier {

  /** {@code notBefore} is null when there is no usable hint. */
  public record Classification(FailureClass failureClass, Instant notBefore) {}

  private static final Duration MAX_NOT_BEFORE = Duration.ofDays(1);

  private ProviderErrorClassifier() {}

  public static Classification classify(ProviderError error, Instant now) {
    int status = error.status();
    boolean github = error.provider() == Provider.GITHUB;

    if (status == ProviderError.NO_RESPONSE || status == 408 || status >= 500) {
      return of(unavailable(error.provider()));
    }
    if (status < 400) {
      throw new IllegalArgumentException("not an error status: " + status);
    }
    // A 403 is only a rate limit if the headers/body say so; otherwise it is a permission failure.
    if (status == 429 || (status == 403 && looksRateLimited(error))) {
      return new Classification(FailureClass.PROVIDER_THROTTLE, notBefore(error, now).orElse(null));
    }
    return switch (status) {
      case 404 -> of(github ? FailureClass.PR_NOT_FOUND : FailureClass.LLM_REQUEST);
      case 400 -> of(github ? FailureClass.UNCLASSIFIED : FailureClass.LLM_REQUEST);
      case 401, 403 ->
          of(github ? FailureClass.INSTALLATION_REVOKED : FailureClass.MISSING_OR_INVALID_API_KEY);
      default -> of(FailureClass.UNCLASSIFIED);
    };
  }

  public static ReviewFailureException toException(ProviderError error, Instant now) {
    Classification c = classify(error, now);
    String message = error.provider() + " " + error.status();
    FailureClass fc = c.failureClass();
    return switch (fc.kind()) {
      case DEFER -> new DeferReviewException(fc, message, c.notBefore(), error);
      case TRANSIENT -> new TransientReviewException(fc, message, error.cause(), error);
      case TERMINAL -> new TerminalReviewException(fc, message, error.cause(), error);
      case SKIP, INFRA, BREAKER_REMNANT ->
          throw new IllegalStateException("provider errors never classify as " + fc.kind());
    };
  }

  private static Classification of(FailureClass c) {
    return new Classification(c, null);
  }

  private static FailureClass unavailable(Provider provider) {
    return provider == Provider.GITHUB
        ? FailureClass.GITHUB_UNAVAILABLE
        : FailureClass.ANTHROPIC_UNAVAILABLE;
  }

  private static boolean looksRateLimited(ProviderError error) {
    if (error.header("retry-after") != null || "0".equals(error.header("x-ratelimit-remaining"))) {
      return true;
    }
    String body = error.bodySnippet().toLowerCase(Locale.ROOT);
    return body.contains("rate limit") || body.contains("abuse detection");
  }

  // Retry-After is tried first; only if it is absent or unparseable does x-ratelimit-reset apply.
  // Whichever value is picked is dropped when it is not in the future or is more than a day away
  // (our guard, not in §12), so the handler falls back to the defer schedule.
  private static Optional<Instant> notBefore(ProviderError error, Instant now) {
    Optional<Instant> parsed =
        parseRetryAfter(error.header("retry-after"), now)
            .or(() -> parseResetEpoch(error.header("x-ratelimit-reset")));
    return parsed.filter(
        t -> t.isAfter(now) && Duration.between(now, t).compareTo(MAX_NOT_BEFORE) <= 0);
  }

  private static Optional<Instant> parseRetryAfter(String value, Instant now) {
    if (value == null) {
      return Optional.empty();
    }
    String v = value.trim();
    try {
      return Optional.of(now.plusSeconds(Long.parseLong(v)));
    } catch (NumberFormatException | ArithmeticException | DateTimeException notSeconds) {
      try {
        return Optional.of(
            ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
      } catch (DateTimeParseException notDate) {
        return Optional.empty();
      }
    }
  }

  private static Optional<Instant> parseResetEpoch(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.ofEpochSecond(Long.parseLong(value.trim())));
    } catch (NumberFormatException | DateTimeException e) {
      return Optional.empty();
    }
  }
}
