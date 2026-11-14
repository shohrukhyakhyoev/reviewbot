package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Checks.requireText;

/** Names the model to use; the adapter maps it to its provider's id. */
public record ModelRef(String id) {

  public ModelRef {
    requireText(id, "id");
  }
}
