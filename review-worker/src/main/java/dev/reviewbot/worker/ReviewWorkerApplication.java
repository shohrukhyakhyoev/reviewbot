package dev.reviewbot.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the review worker deployable. */
@SpringBootApplication
public class ReviewWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ReviewWorkerApplication.class, args);
  }
}
