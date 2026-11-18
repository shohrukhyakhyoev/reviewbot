package dev.reviewbot.gateway.config;

import java.time.Clock;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import dev.reviewbot.gateway.dedup.DeliveryDedup;
import dev.reviewbot.gateway.kafka.ProducerSettings;
import dev.reviewbot.gateway.kafka.ReviewRecordFactory;
import dev.reviewbot.gateway.webhook.HmacVerificationFilter;
import dev.reviewbot.gateway.webhook.SignatureVerifier;
import dev.reviewbot.gateway.webhook.WebhookEnqueuer;
import dev.reviewbot.gateway.webhook.WebhookIntake;
import dev.reviewbot.gateway.webhook.WebhookTimingFilter;
import dev.reviewbot.shared.config.ReviewbotProperties;

@Configuration
@EnableConfigurationProperties({GatewayProperties.class, ReviewbotProperties.class})
public class GatewayConfig {

  /** Just behind the character-encoding filter (HIGHEST_PRECEDENCE), ahead of every other filter. */
  private static final int HMAC_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  /** Outside the HMAC filter, so its rejections are timed and counted too. */
  private static final int TIMING_FILTER_ORDER = HMAC_FILTER_ORDER - 5;

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
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  DeliveryDedup deliveryDedup(RedissonClient redisson) {
    return new DeliveryDedup(redisson);
  }

  // destroyMethod "" stops Spring calling close(), which waits without limit for unsent records.
  @Bean(destroyMethod = "")
  Producer<String, byte[]> reviewProducer(GatewayProperties gateway, ReviewbotProperties reviewbot) {
    return new KafkaProducer<>(
        ProducerSettings.forGateway(
            gateway.kafkaBootstrapServers(), reviewbot.timeouts().gatewayProduce()));
  }

  /**
   * Bounded close at shutdown. A record still unsent then belongs to a request already answered
   * 503 or about to be, so dropping it loses nothing GitHub thinks we have.
   */
  @Bean
  DisposableBean reviewProducerCloser(
      Producer<String, byte[]> reviewProducer, ReviewbotProperties reviewbot) {
    return () -> reviewProducer.close(reviewbot.timeouts().gatewayProduce());
  }

  @Bean
  WebhookEnqueuer webhookEnqueuer(
      DeliveryDedup dedup, Producer<String, byte[]> reviewProducer, ReviewbotProperties reviewbot) {
    return new WebhookEnqueuer(
        dedup, reviewProducer, new ReviewRecordFactory(), reviewbot.timeouts().gatewayProduce());
  }

  @Bean
  SignatureVerifier signatureVerifier(GatewayProperties properties) {
    return new SignatureVerifier(properties.webhookSecret());
  }

  @Bean
  WebhookIntake webhookIntake(GatewayProperties properties) {
    return new WebhookIntake(properties.reviewDrafts());
  }

  @Bean
  FilterRegistrationBean<WebhookTimingFilter> webhookTimingFilter() {
    FilterRegistrationBean<WebhookTimingFilter> registration =
        new FilterRegistrationBean<>(new WebhookTimingFilter());
    registration.addUrlPatterns("/webhook");
    registration.setOrder(TIMING_FILTER_ORDER);
    return registration;
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
