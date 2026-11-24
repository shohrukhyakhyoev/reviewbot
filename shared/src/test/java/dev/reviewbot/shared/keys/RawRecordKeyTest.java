package dev.reviewbot.shared.keys;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RawRecordKeyTest {

  @Test
  void everyPossibleByteRoundTrips() {
    byte[] everyByte = new byte[256];
    for (int value = 0; value < everyByte.length; value++) {
      everyByte[value] = (byte) value;
    }

    String decoded = new String(everyByte, RawRecordKey.CHARSET);

    assertThat(RawRecordKey.toBytes(decoded)).isEqualTo(everyByte);
  }

  @Test
  void aNullKeyStaysNull() {
    assertThat(RawRecordKey.toBytes(null)).isNull();
  }
}
