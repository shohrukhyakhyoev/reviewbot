package dev.reviewbot.worker.config;

import org.springframework.context.SmartLifecycle;

import dev.reviewbot.worker.engine.Engine;

/** Lets Spring start the engine after every bean exists and stop it first on shutdown. */
final class EngineLifecycle implements SmartLifecycle {

  private final Engine engine;
  private final boolean autoStartup;
  private volatile boolean running;

  EngineLifecycle(Engine engine, boolean autoStartup) {
    this.engine = engine;
    this.autoStartup = autoStartup;
  }

  @Override
  public void start() {
    engine.start();
    running = true;
  }

  @Override
  public void stop() {
    engine.stop();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return autoStartup;
  }
}
