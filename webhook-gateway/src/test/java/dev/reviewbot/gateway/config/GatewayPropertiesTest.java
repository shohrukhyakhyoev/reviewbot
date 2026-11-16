package dev.reviewbot.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class GatewayPropertiesTest {

  private static final DataSize CAP = DataSize.ofMegabytes(25);

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void blankSecretFailsStartup(String secret) {
    assertThatThrownBy(() -> new GatewayProperties(secret, CAP, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("webhook-secret");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0B", "-1B", "2GB"})
  void bodyCapOutsideTheSupportedRangeFailsStartup(String size) {
    DataSize bad = DataSize.parse(size);

    assertThatThrownBy(() -> new GatewayProperties("secret", bad, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-body-size");
  }

  @Test
  void missingBodyCapFailsStartup() {
    assertThatThrownBy(() -> new GatewayProperties("secret", null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-body-size");
  }

  @Test
  void toStringNeverShowsTheSecret() {
    GatewayProperties props = new GatewayProperties("s3cr3t-value", CAP, false);

    assertThat(props.toString()).doesNotContain("s3cr3t-value");
  }

  @Test
  void draftsAreSkippedUnlessExplicitlyEnabled() {
    GatewayProperties defaults = bind(Map.of("reviewbot.gateway.webhook-secret", "x"));
    GatewayProperties enabled =
        bind(
            Map.of(
                "reviewbot.gateway.webhook-secret", "x", "reviewbot.gateway.review-drafts", "true"));

    assertThat(defaults.reviewDrafts()).isFalse();
    assertThat(enabled.reviewDrafts()).isTrue();
  }

  @Test
  void bodyCapDefaultsTo25MbAndCanBeOverridden() {
    GatewayProperties defaults = bind(Map.of("reviewbot.gateway.webhook-secret", "x"));
    GatewayProperties overridden =
        bind(
            Map.of(
                "reviewbot.gateway.webhook-secret", "x", "reviewbot.gateway.max-body-size", "1KB"));

    assertThat(defaults.maxBodySize()).isEqualTo(CAP);
    assertThat(defaults.maxBodyBytes()).isEqualTo(25 * 1024 * 1024);
    assertThat(overridden.maxBodyBytes()).isEqualTo(1024);
  }

  private static GatewayProperties bind(Map<String, String> properties) {
    return new Binder(new MapConfigurationPropertySource(properties))
        .bind("reviewbot.gateway", GatewayProperties.class)
        .get();
  }
}
