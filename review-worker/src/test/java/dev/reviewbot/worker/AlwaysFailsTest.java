package dev.reviewbot.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlwaysFailsTest {

  @Test
  void fails() {
    assertEquals(1, 2);
  }
}
