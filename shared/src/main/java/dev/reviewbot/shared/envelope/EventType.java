package dev.reviewbot.shared.envelope;

/**
 * Envelope {@code type} (spec §3.1). v1 knows only {@link #REVIEW_REQUESTED}; the v1.5 command
 * types ({@code REREVIEW_COMMAND}, {@code MODEL_COMMAND}) are deliberately absent — adding them is
 * an additive change made by that ticket.
 */
public enum EventType {
  REVIEW_REQUESTED
}
