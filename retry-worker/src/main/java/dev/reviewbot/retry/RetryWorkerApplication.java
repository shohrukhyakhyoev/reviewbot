package dev.reviewbot.retry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the retry worker deployable. */
@SpringBootApplication
public class RetryWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(RetryWorkerApplication.class, args);
  }
}
