package dev.reviewbot.shared.review;

/** Finding severity (spec §7.1 step 10); consumed by the min-severity filter in step 11. */
public enum Severity {
  // Least severe first, the reverse of the spec's listing: atOrAbove compares ordinals.
  NIT,
  MINOR,
  MAJOR,
  CRITICAL;

  /** True when this severity is at least as severe as {@code min}. */
  public boolean atOrAbove(Severity min) {
    return ordinal() >= min.ordinal();
  }
}
