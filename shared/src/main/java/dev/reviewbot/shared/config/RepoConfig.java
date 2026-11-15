package dev.reviewbot.shared.config;

import java.util.Objects;

import dev.reviewbot.shared.review.Severity;

/** A repo's {@code .reviewbot.yml}; a null {@code model} means no override (§7.1 step 7). */
public record RepoConfig(String model, Severity minSeverity, ReviewAuthors reviewAuthors) {

  // NIT keeps every finding. §7.1 step 11 doesn't name a default; this is our choice.
  public static final RepoConfig DEFAULTS = new RepoConfig(null, Severity.NIT, ReviewAuthors.WRITE);

  public RepoConfig {
    if (model != null && model.isBlank()) {
      throw new IllegalArgumentException("model must be null or non-blank");
    }
    Objects.requireNonNull(minSeverity, "minSeverity");
    Objects.requireNonNull(reviewAuthors, "reviewAuthors");
  }
}
