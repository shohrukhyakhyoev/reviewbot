package dev.reviewbot.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class TopicsTest {

  @Test
  void namesAndPartitionCountsMatchSpec41() {
    assertThat(Topics.REVIEW_REQUESTED).isEqualTo("pr.review.requested");
    assertThat(Topics.REVIEW_REQUESTED_PARTITIONS).isEqualTo(6);
    assertThat(Topics.REVIEW_RETRY).isEqualTo("pr.review.retry");
    assertThat(Topics.REVIEW_RETRY_PARTITIONS).isEqualTo(3);
    assertThat(Topics.REVIEW_DLQ).isEqualTo("pr.review.dlq");
    assertThat(Topics.REVIEW_DLQ_PARTITIONS).isEqualTo(3);
  }

  @Test
  void provisioningScriptCreatesTheSameTopicsWithTheSamePartitionCounts() throws IOException {
    // Surefire sets basedir to the module directory, whatever directory Maven was started from.
    var module = Path.of(System.getProperty("basedir", "."));
    var script = Files.readString(module.resolve("../infra/kafka/provision-topics.sh"));
    var inScript = new HashMap<String, Integer>();
    var row = Pattern.compile("(pr\\.review\\.\\w+) (\\d+) ").matcher(script);
    while (row.find()) {
      inScript.put(row.group(1), Integer.valueOf(row.group(2)));
    }

    assertThat(inScript)
        .containsOnly(
            Map.entry(Topics.REVIEW_REQUESTED, Topics.REVIEW_REQUESTED_PARTITIONS),
            Map.entry(Topics.REVIEW_RETRY, Topics.REVIEW_RETRY_PARTITIONS),
            Map.entry(Topics.REVIEW_DLQ, Topics.REVIEW_DLQ_PARTITIONS));
  }
}
