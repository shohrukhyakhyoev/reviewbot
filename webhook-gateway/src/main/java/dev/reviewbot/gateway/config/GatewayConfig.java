package dev.reviewbot.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import dev.reviewbot.gateway.webhook.HmacVerificationFilter;
import dev.reviewbot.gateway.webhook.SignatureVerifier;
import dev.reviewbot.gateway.webhook.WebhookIntake;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

  /** Just behind the character-encoding filter (HIGHEST_PRECEDENCE), ahead of every other filter. */
  private static final int HMAC_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

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
