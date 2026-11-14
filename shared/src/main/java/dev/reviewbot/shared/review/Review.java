package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Checks.immutableCopy;

import java.util.List;

/** What one generate call returns. May grow additively; the port signature stays frozen. */
public record Review(List<Finding> findings) {

  public Review {
    findings = immutableCopy(findings, "findings");
  }
}
