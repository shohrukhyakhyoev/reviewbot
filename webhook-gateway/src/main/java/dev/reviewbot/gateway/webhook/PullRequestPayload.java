package dev.reviewbot.gateway.webhook;

import java.time.Instant;

/** The few fields of a {@code pull_request} delivery the gateway needs; everything else is ignored. */
record PullRequestPayload(
    String action,
    boolean draft,
    long installationId,
    String repoFullName,
    int prNumber,
    String headSha,
    Instant updatedAt) {}
