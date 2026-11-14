package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Checks.immutableCopy;

import java.util.List;

/** The code to review. Minimal on purpose: E5/E9 may add fields; the port signature stays frozen. */
public record Diff(List<DiffFile> files) {

  public Diff {
    files = immutableCopy(files, "files");
  }
}
