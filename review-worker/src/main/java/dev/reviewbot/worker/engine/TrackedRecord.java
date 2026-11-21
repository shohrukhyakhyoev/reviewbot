package dev.reviewbot.worker.engine;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/** A dispatched record and the offset its completion must hand back to the tracker. */
public record TrackedRecord(ConsumerRecord<String, byte[]> record, TrackedOffset trackedOffset) {}
