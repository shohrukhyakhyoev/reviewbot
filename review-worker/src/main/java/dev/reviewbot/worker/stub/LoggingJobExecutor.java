package dev.reviewbot.worker.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.reviewbot.shared.failure.Outcome;
import dev.reviewbot.worker.engine.CoalescePlan.Job;
import dev.reviewbot.worker.engine.JobExecutor;

/** Stand-in until E5's review pipeline: logs the record's coordinates and reports success. */
public final class LoggingJobExecutor implements JobExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingJobExecutor.class);

  @Override
  public Outcome execute(Job job) {
    var record = job.record();
    LOG.info(
        "stub review partition={} offset={} key={}",
        record.partition(),
        record.offset(),
        record.key());
    return new Outcome.Success();
  }
}
