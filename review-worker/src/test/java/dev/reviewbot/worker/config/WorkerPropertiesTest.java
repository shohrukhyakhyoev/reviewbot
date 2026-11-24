package dev.reviewbot.worker.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerPropertiesTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  "})
  void aMissingBootstrapServerListFailsStartupNamingTheEnvVariable(String servers) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new WorkerProperties(servers, true))
        .withMessageContaining("KAFKA_BOOTSTRAP_SERVERS");
  }
}
