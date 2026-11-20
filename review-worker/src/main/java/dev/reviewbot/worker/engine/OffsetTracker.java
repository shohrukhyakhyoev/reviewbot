package dev.reviewbot.worker.engine;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.common.TopicPartition;

/** Per-partition contiguous-watermark accounting; the only place in-flight permits come back. */
public final class OffsetTracker {

  private static final class PartitionState {
    private final long generation;
    private long watermark;
    private long lastCommitted;
    private final NavigableSet<Long> islands = new TreeSet<>();

    PartitionState(long generation, long position) {
      this.generation = generation;
      this.watermark = position - 1;
      this.lastCommitted = watermark;
    }

    synchronized long watermark() {
      return watermark;
    }

    synchronized OptionalLong uncommittedWatermark() {
      return watermark == lastCommitted ? OptionalLong.empty() : OptionalLong.of(watermark);
    }

    synchronized void committed(long upTo) {
      if (upTo > watermark) {
        throw new IllegalArgumentException(
            "confirmed " + upTo + " beyond watermark " + watermark + " (passed offset+1?)");
      }
      lastCommitted = Math.max(lastCommitted, upTo);
    }

    /** Returns false, changing nothing, when {@code offset} was already completed. */
    synchronized boolean complete(long offset) {
      if (offset <= watermark) {
        return false;
      }
      if (offset == watermark + 1) {
        watermark = offset;
        while (islands.remove(watermark + 1)) {
          watermark++;
        }
        return true;
      }
      return islands.add(offset);
    }
  }

  private final Map<TopicPartition, PartitionState> parts = new ConcurrentHashMap<>();
  private final AtomicLong generations = new AtomicLong();
  private final Semaphore inFlight;

  public OffsetTracker(Semaphore inFlight) {
    this.inFlight = inFlight;
  }

  public void register(TopicPartition tp, long position) {
    parts.put(tp, new PartitionState(generations.incrementAndGet(), position));
  }

  /** Stamps a record at dispatch; its completion counts only while this registration is current. */
  public TrackedOffset track(TopicPartition tp, long offset) {
    var p = parts.get(tp);
    if (p == null) {
      throw new IllegalStateException("dispatch from unregistered partition " + tp);
    }
    if (offset <= p.watermark()) {
      throw new IllegalStateException("dispatch of " + tp + "@" + offset + " below the position");
    }
    return new TrackedOffset(tp, p.generation, offset);
  }

  public void unregister(Collection<TopicPartition> revoked) {
    parts.keySet().removeAll(revoked);
  }

  public void markComplete(TrackedOffset done) {
    boolean duplicate = false;
    try {
      var p = parts.get(done.partition());
      if (p != null && p.generation == done.generation()) {
        duplicate = !p.complete(done.offset());
      }
    } finally {
      if (!duplicate) {
        inFlight.release();
      }
    }
    if (duplicate) {
      throw new IllegalStateException("completed twice (I5): " + done);
    }
  }

  /** Last-complete offsets advanced since the last confirmed commit; the committer adds 1 (§19.2). */
  public Map<TopicPartition, Long> committable() {
    return committable(parts.keySet());
  }

  public Map<TopicPartition, Long> committable(Collection<TopicPartition> subset) {
    var advanced = new HashMap<TopicPartition, Long>();
    for (var tp : subset) {
      var p = parts.get(tp);
      if (p != null) {
        p.uncommittedWatermark().ifPresent(wm -> advanced.put(tp, wm));
      }
    }
    return advanced;
  }

  /** Records a commit Kafka confirmed; pass the watermarks {@link #committable} returned, not +1. */
  public void markCommitted(Map<TopicPartition, Long> watermarks) {
    watermarks.forEach(
        (tp, wm) -> {
          var p = parts.get(tp);
          if (p != null) {
            p.committed(wm);
          }
        });
  }

  /** Test probe: the current watermark, or empty when the partition is not registered. */
  OptionalLong watermark(TopicPartition tp) {
    var p = parts.get(tp);
    return p == null ? OptionalLong.empty() : OptionalLong.of(p.watermark());
  }
}
