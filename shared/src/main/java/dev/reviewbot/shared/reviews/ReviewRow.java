package dev.reviewbot.shared.reviews;

import java.time.Instant;

/** The {@code reviews} columns the claim and handlers decide on; nullable ones may be null. */
record ReviewRow(
    long id,
    ReviewStatus status,
    int errorAttempts,
    int deferAttempts,
    String failureClass,
    Instant postAttemptedAt,
    Instant firstReceivedAt,
    Instant firstDeferredAt) {}
