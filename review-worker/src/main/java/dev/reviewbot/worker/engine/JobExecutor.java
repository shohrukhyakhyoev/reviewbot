package dev.reviewbot.worker.engine;

import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.worker.engine.CoalescePlan.Job;

/** Runs one coalesced job to its outcome; E5 plugs the §7 pipeline in here. */
@FunctionalInterface
public interface JobExecutor {

  Outcome execute(Job job);
}
