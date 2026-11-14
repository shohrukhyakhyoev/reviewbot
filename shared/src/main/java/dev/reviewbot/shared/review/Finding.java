package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Checks.requireText;

/** One issue the model reported (spec §7.1 step 10). */
public record Finding(String file, String summary, Severity severity, String detail) {

  public Finding {
    requireText(file, "file");
    requireText(summary, "summary");
    requireText(detail, "detail");
    if (severity == null) {
      throw new IllegalArgumentException("severity must not be null");
    }
  }
}
