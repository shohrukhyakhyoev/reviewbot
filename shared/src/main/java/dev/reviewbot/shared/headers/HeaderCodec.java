package dev.reviewbot.shared.headers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The §3.2 Kafka headers: each header is ONE constant carrying its name AND its format, so a call
 * site cannot pair {@code first_deferred_at} with the wrong encoding (§19.13 — a codec mismatch
 * between the workers is a silent interop bug). Both workers use these constants; nobody
 * re-implements a codec.
 *
 * <p>Kafka-free: values are {@code byte[]}, callers do the {@code Headers} plumbing. Formats:
 * timestamps = epoch-millisecond decimal strings; counters = decimal-int strings; {@code
 * failure_class}/{@code trace_id} = plain UTF-8 strings.
 *
 * <p>Encoders reject what the decoders would reject (fail fast at the buggy writer, not in the other
 * worker): a negative counter or a pre-1970 timestamp throws {@link IllegalArgumentException}.
 * Decoders throw {@link HeaderDecodeException}.
 */
public final class HeaderCodec {

  /**
   * One header: its Kafka name plus how its value is encoded and decoded. Only {@link HeaderCodec}
   * can construct one and the raw encoder/decoder are not exposed, so a call site can neither mint
   * its own {@code first_deferred_at} codec nor bypass the checks below (§19.13).
   */
  public static final class Header<T> {
    private final String name;
    private final Function<T, byte[]> encoder;
    private final Function<byte[], T> decoder;

    private Header(String name, Function<T, byte[]> encoder, Function<byte[], T> decoder) {
      this.name = name;
      this.encoder = encoder;
      this.decoder = decoder;
    }

    public String name() {
      return name;
    }

    public byte[] encode(T value) {
      return encoder.apply(Objects.requireNonNull(value, "header value must not be null"));
    }

    /**
     * @throws HeaderDecodeException naming this header (never the raw bytes)
     */
    public T decode(byte[] value) {
      try {
        return decoder.apply(value);
      } catch (HeaderDecodeException e) {
        throw new HeaderDecodeException("invalid header " + name + ": " + e.getMessage(), e);
      }
    }
  }

  // Timestamps: epoch-ms strings.
  public static final Header<Instant> NOT_BEFORE =
      new Header<>("not_before", HeaderCodec::encodeEpochMs, HeaderCodec::decodeEpochMs);
  public static final Header<Instant> FIRST_RECEIVED_AT =
      new Header<>("first_received_at", HeaderCodec::encodeEpochMs, HeaderCodec::decodeEpochMs);
  public static final Header<Instant> FIRST_DEFERRED_AT =
      new Header<>("first_deferred_at", HeaderCodec::encodeEpochMs, HeaderCodec::decodeEpochMs);

  // Counters: decimal-int strings.
  public static final Header<Integer> ERROR_ATTEMPTS =
      new Header<>("error_attempts", HeaderCodec::encodeCount, HeaderCodec::decodeCount);
  public static final Header<Integer> DEFER_ATTEMPTS =
      new Header<>("defer_attempts", HeaderCodec::encodeCount, HeaderCodec::decodeCount);

  // Plain strings.
  public static final Header<String> FAILURE_CLASS =
      new Header<>("failure_class", HeaderCodec::encodeString, HeaderCodec::decodeString);
  public static final Header<String> TRACE_ID =
      new Header<>("trace_id", HeaderCodec::encodeString, HeaderCodec::decodeString);

  /** Every §3.2 header, for completeness checks. */
  public static final List<Header<?>> ALL =
      List.of(
          NOT_BEFORE,
          FIRST_RECEIVED_AT,
          FIRST_DEFERRED_AT,
          ERROR_ATTEMPTS,
          DEFER_ATTEMPTS,
          FAILURE_CLASS,
          TRACE_ID);

  private HeaderCodec() {}

  // ---- epoch-ms ----

  private static byte[] encodeEpochMs(Instant instant) {
    long epochMs = instant.toEpochMilli();
    if (epochMs < 0) {
      throw new IllegalArgumentException("timestamp header must not be before the epoch");
    }
    return utf8(Long.toString(epochMs));
  }

  private static Instant decodeEpochMs(byte[] value) {
    return Instant.ofEpochMilli(parseNonNegativeLong(value, "epoch-ms"));
  }

  // ---- counters ----

  private static byte[] encodeCount(int count) {
    if (count < 0) {
      throw new IllegalArgumentException("counter header must not be negative");
    }
    return utf8(Integer.toString(count));
  }

  private static int decodeCount(byte[] value) {
    long count = parseNonNegativeLong(value, "count");
    if (count > Integer.MAX_VALUE) {
      throw new HeaderDecodeException("count value is out of int range");
    }
    return (int) count;
  }

  // ---- strings ----

  private static byte[] encodeString(String value) {
    return utf8(value);
  }

  private static String decodeString(byte[] value) {
    if (value == null) {
      throw new HeaderDecodeException("string value is missing");
    }
    return new String(value, StandardCharsets.UTF_8);
  }

  // ---- shared parsing: check present, convert, check the number is sane ----

  private static long parseNonNegativeLong(byte[] value, String kind) {
    if (value == null || value.length == 0) {
      throw new HeaderDecodeException(kind + " value is missing or empty");
    }
    long parsed;
    try {
      parsed = Long.parseLong(new String(value, StandardCharsets.UTF_8));
    } catch (NumberFormatException e) {
      throw new HeaderDecodeException(kind + " value is not a number", e);
    }
    if (parsed < 0) {
      throw new HeaderDecodeException(kind + " value is negative");
    }
    return parsed;
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
