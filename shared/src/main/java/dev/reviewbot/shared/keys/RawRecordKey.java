package dev.reviewbot.shared.keys;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** How both workers hold a Kafka key as a String without losing a byte for the RAW DLQ (§10.2). */
public final class RawRecordKey {

  public static final Charset CHARSET = StandardCharsets.ISO_8859_1;

  private RawRecordKey() {}

  /** The broker's original bytes for a key decoded with {@link #CHARSET}; a null key stays null. */
  public static byte[] toBytes(String decodedKey) {
    return decodedKey == null ? null : decodedKey.getBytes(CHARSET);
  }
}
