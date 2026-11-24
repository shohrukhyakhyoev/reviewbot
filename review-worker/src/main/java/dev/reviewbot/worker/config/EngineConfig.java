package dev.reviewbot.worker.config;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.worker.engine.Engine;
import dev.reviewbot.worker.engine.JobExecutor;
import dev.reviewbot.worker.engine.OutcomeHandler;
import dev.reviewbot.worker.kafka.ConsumerSettings;
import dev.reviewbot.worker.stub.LoggingJobExecutor;
import dev.reviewbot.worker.stub.LoggingOutcomeHandler;

/** Assembles the engine. The consumer is deliberately not a bean: only the poller may close it. */
@Configuration
@EnableConfigurationProperties({ReviewbotProperties.class, WorkerProperties.class})
class EngineConfig {

  @Bean
  JobExecutor jobExecutor() {
    return new LoggingJobExecutor();
  }

  @Bean
  OutcomeHandler outcomeHandler() {
    return new LoggingOutcomeHandler();
  }

  @Bean
  Engine engine(
      ReviewbotProperties reviewbot,
      WorkerProperties worker,
      JobExecutor jobs,
      OutcomeHandler outcomes,
      ConfigurableApplicationContext context) {
    var consumer =
        new KafkaConsumer<String, byte[]>(
            ConsumerSettings.forReviewWorker(
                worker.kafkaBootstrapServers(), reviewbot.consumer()));
    return Engine.create(
        consumer, jobs, outcomes, reviewbot.consumer(), new ExitOnPollerDeath(context, System::exit));
  }

  @Bean
  EngineLifecycle engineLifecycle(Engine engine, WorkerProperties worker) {
    return new EngineLifecycle(engine, worker.engineAutostart());
  }
}
