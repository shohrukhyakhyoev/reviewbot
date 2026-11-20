package dev.reviewbot.worker.engine;

import org.apache.kafka.common.TopicPartition;

/** A dispatched record's offset, stamped with the partition's tracking generation at dispatch. */
public record TrackedOffset(TopicPartition partition, long generation, long offset) {}
