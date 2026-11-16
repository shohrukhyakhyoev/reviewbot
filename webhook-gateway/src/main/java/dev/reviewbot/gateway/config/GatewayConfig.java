package dev.reviewbot.gateway.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import dev.reviewbot.gateway.dedup.DeliveryDedup;
import dev.reviewbot.gateway.webhook.HmacVerificationFilter;
import dev.reviewbot.gateway.webhook.SignatureVerifier;
import dev.reviewbot.gateway.webhook.WebhookIntake;
import dev.reviewbot.shared.config.ReviewbotProperties;

@Configuration
@EnableConfigurationProperties({GatewayProperties.class, ReviewbotProperties.class})
public class GatewayConfig {

  /** Just behind the character-encoding filter (HIGHEST_PRECEDENCE), ahead of every other filter. */
  private static final int HMAC_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  /**
   * Lazy, so the gateway starts and fails open with Redis down. No retries: Redisson retries each
   * command by default, which would stretch the 2 s budget (§12) toward GitHub's 10 s webhook
   * timeout.
   */
  @Bean(destroyMethod = "shutdown")
  public RedissonClient redissonClient(GatewayProperties gateway, ReviewbotProperties reviewbot) {
    int timeoutMillis = Math.toIntExact(reviewbot.timeouts().redis().toMillis());
    Config config = new Config();
    config.setLazyInitialization(true);
    config
        .useSingleServer()
        .setAddress(gateway.redisUrl())
        .setTimeout(timeoutMillis)
        .setConnectTimeout(timeoutMillis)
        .setRetryAttempts(0);
    return Redisson.create(config);
  }

  @Bean
  DeliveryDedup deliveryDedup(RedissonClient redisson) {
    return new DeliveryDedup(redisson);
  }

  @Bean
  SignatureVerifier signatureVerifier(GatewayProperties properties) {
    return new SignatureVerifier(properties.webhookSecret());
  }

  @Bean
  WebhookIntake webhookIntake(GatewayProperties properties) {
    return new WebhookIntake(properties.reviewDrafts());
  }

  // The filter is deliberately not a bean: a Filter bean would also be registered for every URL.
  @Bean
  FilterRegistrationBean<HmacVerificationFilter> hmacVerificationFilter(
      SignatureVerifier verifier, GatewayProperties properties) {
    FilterRegistrationBean<HmacVerificationFilter> registration =
        new FilterRegistrationBean<>(
            new HmacVerificationFilter(verifier, properties.maxBodyBytes()));
    registration.addUrlPatterns("/webhook");
    registration.setOrder(HMAC_FILTER_ORDER);
    return registration;
  }
}
