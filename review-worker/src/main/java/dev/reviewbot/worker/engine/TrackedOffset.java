package dev.reviewbot.worker.engine;

import org.apache.kafka.common.TopicPartition;

/** An offset (a dispatched record's, or a watermark to commit) stamped with its partition's registration. */
public record TrackedOffset(TopicPartition partition, long generation, long offset) {}
