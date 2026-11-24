package dev.reviewbot.worker.config;

import java.util.function.IntConsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Exits the worker with code 1 so the platform restarts it from the committed offsets. Runs the
 * exit on its own thread: closing the context stops the engine, which waits for the poller thread
 * this is called from.
 */
final class ExitOnPollerDeath implements Runnable {

  static final int EXIT_CODE = 1;

  private final ConfigurableApplicationContext context;
  private final IntConsumer exitProcess;

  ExitOnPollerDeath(ConfigurableApplicationContext context, IntConsumer exitProcess) {
    this.context = context;
    this.exitProcess = exitProcess;
  }

  @Override
  public void run() {
    Thread.ofPlatform()
        .name("poller-death-exit")
        .start(() -> exitProcess.accept(SpringApplication.exit(context, () -> EXIT_CODE)));
  }
}
