package dev.reviewbot.worker.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EngineProbeTest {

  @Test
  void positive() {
    assertEquals(1, EngineProbe.sign(5));
  }
}
