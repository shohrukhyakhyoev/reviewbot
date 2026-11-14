package dev.reviewbot.shared.failure;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the classifier needs from a failed provider call, free of any HTTP client type. Header names
 * are case-insensitive. {@code status == NO_RESPONSE} means timeout or connection failure, with the
 * original exception in {@code cause}. The body snippet is capped and left out of {@link
 * #toString()} because provider 4xx bodies can quote diff fragments (S-4).
 */
public record ProviderError(
    Provider provider, int status, Map<String, String> headers, String bodySnippet, Throwable cause) {

  public enum Provider {
    GITHUB,
    ANTHROPIC
  }

  public static final int NO_RESPONSE = 0;
  public static final int MAX_BODY_SNIPPET = 512;

  public ProviderError {
    Objects.requireNonNull(provider, "provider");
    // 0 means "no response" and always has a cause, so an adapter that forgot to set the status
    // fails here instead of being retried as an outage.
    boolean noResponse = status == NO_RESPONSE && cause != null;
    if (!noResponse && (status < 100 || status > 599)) {
      throw new IllegalArgumentException("not an HTTP status: " + status + " (use noResponse)");
    }
    Map<String, String> lower = new HashMap<>();
    Objects.requireNonNull(headers, "headers")
        .forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
    headers = Map.copyOf(lower);
    if (bodySnippet == null) {
      bodySnippet = "";
    } else if (bodySnippet.length() > MAX_BODY_SNIPPET) {
      bodySnippet = bodySnippet.substring(0, MAX_BODY_SNIPPET);
    }
  }

  public static ProviderError http(
      Provider provider, int status, Map<String, String> headers, String bodySnippet) {
    return new ProviderError(provider, status, headers, bodySnippet, null);
  }

  public static ProviderError noResponse(Provider provider, Throwable cause) {
    return new ProviderError(provider, NO_RESPONSE, Map.of(), "", cause);
  }

  public String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }

  @Override
  public String toString() {
    return "ProviderError[" + provider + " " + status + ", bodyChars=" + bodySnippet.length() + "]";
  }
}
