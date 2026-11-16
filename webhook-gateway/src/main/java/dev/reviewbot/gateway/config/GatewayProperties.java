package dev.reviewbot.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Gateway-only settings; the webhook secret never appears in {@code toString} or logs. {@code
 * reviewDrafts} is global (O4): the gateway cannot read {@code .reviewbot.yml}, so no per-repo
 * opt-in exists here.
 */
@ConfigurationProperties("reviewbot.gateway")
public record GatewayProperties(
    String webhookSecret,
    @DefaultValue("25MB") DataSize maxBodySize,
    boolean reviewDrafts,
    @DefaultValue("redis://localhost:6379") String redisUrl) {

  public GatewayProperties {
    if (webhookSecret == null || webhookSecret.isBlank()) {
      throw new IllegalArgumentException(
          "reviewbot.gateway.webhook-secret must be set (env GITHUB_WEBHOOK_SECRET)");
    }
    if (redisUrl == null || redisUrl.isBlank()) {
      throw new IllegalArgumentException("reviewbot.gateway.redis-url must be set (env REDIS_URL)");
    }
    if (maxBodySize == null
        || maxBodySize.toBytes() < 1
        || maxBodySize.toBytes() >= Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "reviewbot.gateway.max-body-size must be at least 1 byte and below 2GB");
    }
  }

  public int maxBodyBytes() {
    return (int) maxBodySize.toBytes();
  }

  // redisUrl is left out: it may carry a password (redis://:pw@host).
  @Override
  public String toString() {
    return "GatewayProperties[webhookSecret=***, maxBodySize="
        + maxBodySize
        + ", reviewDrafts="
        + reviewDrafts
        + "]";
  }
}
