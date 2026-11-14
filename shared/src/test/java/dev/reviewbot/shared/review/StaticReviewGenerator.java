package dev.reviewbot.shared.review;

import java.util.List;
import java.util.Objects;

/** Test double: returns the same review for every call, so tests need no Anthropic. */
public final class StaticReviewGenerator implements ReviewGenerator {

  private final Review review;

  public StaticReviewGenerator(Review review) {
    this.review = Objects.requireNonNull(review, "review");
  }

  public static StaticReviewGenerator returning(Finding... findings) {
    return new StaticReviewGenerator(new Review(List.of(findings)));
  }

  @Override
  public Review generate(Diff diff, ReviewContext context, ModelRef model) {
    return review;
  }
}
