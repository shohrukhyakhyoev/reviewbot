package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Checks.requireText;

/** One changed file. {@code status} is the provider's word (added, modified, ...), kept as text. */
public record DiffFile(String path, String patch, String status) {

  public DiffFile {
    requireText(path, "path");
    requireText(status, "status");
    if (patch == null) {
      throw new IllegalArgumentException("patch must not be null (use \"\" for no patch)");
    }
  }
}
