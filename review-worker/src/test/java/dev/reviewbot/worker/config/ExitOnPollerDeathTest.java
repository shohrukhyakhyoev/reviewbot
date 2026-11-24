package dev.reviewbot.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class ExitOnPollerDeathTest {

  @Test
  void closesTheContextAndExitsWithCode1OffTheCallingThread() throws Exception {
    var context = new GenericApplicationContext();
    context.refresh();
    var exit = new CompletableFuture<String>();

    new ExitOnPollerDeath(
            context, code -> exit.complete(code + " on " + Thread.currentThread().getName()))
        .run();

    assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo("1 on poller-death-exit");
    assertThat(context.isActive()).isFalse();
  }
}
