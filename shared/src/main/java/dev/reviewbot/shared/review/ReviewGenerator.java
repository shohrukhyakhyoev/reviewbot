package dev.reviewbot.shared.review;

/**
 * The only LLM-facing surface in {@code shared} (spec §14.2). A provider is one implementation; no
 * provider type may appear here.
 */
public interface ReviewGenerator {

  Review generate(Diff diff, ReviewContext context, ModelRef model);
}
