package dev.reviewbot.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the webhook gateway deployable. */
@SpringBootApplication
public class WebhookGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebhookGatewayApplication.class, args);
  }
}
