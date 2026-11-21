package dev.reviewbot.worker.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One PR's waiting line: the poller adds, the PR's drainer takes everything. Never drops, reorders
 * or inspects a record (§8.2).
 *
 * <p>Not final: {@code DispatcherTest} pauses {@link #isEmpty} inside {@code tryRetire} to pin I10.
 */
class PrQueue {

  // ReentrantLock, not synchronized: callers run on virtual threads (§19.1).
  private final ReentrantLock lock = new ReentrantLock();
  private List<TrackedRecord> waiting = new ArrayList<>();

  void add(TrackedRecord tracked) {
    lock.lock();
    try {
      waiting.add(tracked);
    } finally {
      lock.unlock();
    }
  }

  /** Everything waiting, in arrival order; the queue is empty afterwards. */
  List<TrackedRecord> drainAll() {
    lock.lock();
    try {
      List<TrackedRecord> drained = waiting;
      waiting = new ArrayList<>();
      return drained;
    } finally {
      lock.unlock();
    }
  }

  boolean isEmpty() {
    lock.lock();
    try {
      return waiting.isEmpty();
    } finally {
      lock.unlock();
    }
  }
}
