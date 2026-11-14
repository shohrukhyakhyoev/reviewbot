package dev.reviewbot.shared.dlq;

import java.util.Objects;

/** The §10.2 exception block. Provider text can quote diff fragments, so it is capped (S-4). */
public record DlqException(String type, String message, Integer httpStatus, String bodySnippet) {

  public static final int MAX_BODY_SNIPPET = 2048;
  public static final String TRUNCATED_SUFFIX = "…[truncated]";
  public static final String SNIPPET_NOTE = "may echo customer input";

  public DlqException {
    Objects.requireNonNull(type, "type");
    message = clean(message);
    bodySnippet = clean(bodySnippet);
  }

  // A lone surrogate (e.g. from an upstream substring) makes the JSON writer throw, inside the
  // failure path. The suffix counts toward the cap.
  private static String clean(String text) {
    if (text == null) {
      return null;
    }
    String valid = replaceLoneSurrogates(text);
    if (valid.length() <= MAX_BODY_SNIPPET) {
      return valid;
    }
    int keep = MAX_BODY_SNIPPET - TRUNCATED_SUFFIX.length();
    // Cutting between a valid pair would create a lone surrogate.
    if (Character.isHighSurrogate(valid.charAt(keep - 1))) {
      keep--;
    }
    return valid.substring(0, keep) + TRUNCATED_SUFFIX;
  }

  private static String replaceLoneSurrogates(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        out.append(c).append(text.charAt(++i));
      } else if (Character.isSurrogate(c)) {
        out.append('�');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
