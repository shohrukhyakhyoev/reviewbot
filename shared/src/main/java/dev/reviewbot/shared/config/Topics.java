package dev.reviewbot.shared.config;

/** Topic names and partition counts (§4.1); {@code TopicsTest} compares provision-topics.sh. */
public final class Topics {

  public static final String REVIEW_REQUESTED = "pr.review.requested";
  public static final int REVIEW_REQUESTED_PARTITIONS = 6;

  public static final String REVIEW_RETRY = "pr.review.retry";
  public static final int REVIEW_RETRY_PARTITIONS = 3;

  public static final String REVIEW_DLQ = "pr.review.dlq";
  public static final int REVIEW_DLQ_PARTITIONS = 3;

  private Topics() {}
}
