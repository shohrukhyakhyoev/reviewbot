package dev.reviewbot.shared.headers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.shared.headers.HeaderCodec.Header;

/**
 * Codec tests for the §3.2 headers: every header's exact name, wire format and round trip, plus
 * malformed input. Each header is tested individually so pairing a header with the wrong format
 * fails a test (§19.13).
 */
class HeaderCodecTest {

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static final long FAR_FUTURE_MS = 253_402_300_799_000L; // 9999-12-31T23:59:59Z

  static Stream<Header<Instant>> timestampHeaders() {
    return Stream.of(
        HeaderCodec.NOT_BEFORE, HeaderCodec.FIRST_RECEIVED_AT, HeaderCodec.FIRST_DEFERRED_AT);
  }

  static Stream<Header<Integer>> counterHeaders() {
    return Stream.of(HeaderCodec.ERROR_ATTEMPTS, HeaderCodec.DEFER_ATTEMPTS);
  }

  static Stream<Header<String>> stringHeaders() {
    return Stream.of(HeaderCodec.FAILURE_CLASS, HeaderCodec.TRACE_ID);
  }

  // ---- names: the §3.2 contract, one assertion per header ----

  @Test
  void everyHeaderHasItsSpecName() {
    assertThat(HeaderCodec.NOT_BEFORE.name()).isEqualTo("not_before");
    assertThat(HeaderCodec.ERROR_ATTEMPTS.name()).isEqualTo("error_attempts");
    assertThat(HeaderCodec.DEFER_ATTEMPTS.name()).isEqualTo("defer_attempts");
    assertThat(HeaderCodec.FAILURE_CLASS.name()).isEqualTo("failure_class");
    assertThat(HeaderCodec.FIRST_RECEIVED_AT.name()).isEqualTo("first_received_at");
    assertThat(HeaderCodec.FIRST_DEFERRED_AT.name()).isEqualTo("first_deferred_at");
    assertThat(HeaderCodec.TRACE_ID.name()).isEqualTo("trace_id");
  }

  @Test
  void allListsExactlyTheSpecHeaders() {
    assertThat(HeaderCodec.ALL)
        .extracting(Header::name)
        .containsExactlyInAnyOrder(
            "not_before",
            "error_attempts",
            "defer_attempts",
            "installation_id",
            "failure_class",
            "first_received_at",
            "first_deferred_at",
            "trace_id");
  }

  @Test
  void installationIdRoundTripsAsDecimalString() {
    byte[] encoded = HeaderCodec.INSTALLATION_ID.encode(12345L);

    assertThat(new String(encoded, StandardCharsets.UTF_8)).isEqualTo("12345");
    assertThat(HeaderCodec.INSTALLATION_ID.decode(encoded)).isEqualTo(12345L);
  }

  @Test
  void installationIdRejectsZeroAtBothEnds() {
    assertThatThrownBy(() -> HeaderCodec.INSTALLATION_ID.encode(0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HeaderCodec.INSTALLATION_ID.decode("0".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(HeaderDecodeException.class);
    assertThatThrownBy(() -> HeaderCodec.INSTALLATION_ID.decode("abc".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(HeaderDecodeException.class);
  }

  // ---- timestamps (not_before, first_received_at, first_deferred_at) ----

  @ParameterizedTest
  @MethodSource("timestampHeaders")
  void timestampHeaderRoundTripsIncludingEpochZeroAndFarFuture(Header<Instant> header) {
    for (long ms : List.of(0L, 1L, 1_783_245_600_000L, FAR_FUTURE_MS)) {
      Instant instant = Instant.ofEpochMilli(ms);
      assertThat(header.decode(header.encode(instant))).isEqualTo(instant);
    }
  }

  @ParameterizedTest
  @MethodSource("timestampHeaders")
  void timestampHeaderWireFormatIsEpochMsDecimalString(Header<Instant> header) {
    assertThat(header.encode(Instant.EPOCH)).isEqualTo(utf8("0"));
    assertThat(header.encode(Instant.ofEpochMilli(1_783_245_600_123L)))
        .isEqualTo(utf8("1783245600123"));
    assertThat(header.encode(Instant.ofEpochMilli(FAR_FUTURE_MS))).isEqualTo(utf8("253402300799000"));
  }

  @ParameterizedTest
  @MethodSource("timestampHeaders")
  void timestampHeaderTruncatesSubMillisecondPrecision(Header<Instant> header) {
    Instant withNanos = Instant.ofEpochSecond(10, 123_456_789);

    assertThat(header.decode(header.encode(withNanos))).isEqualTo(Instant.ofEpochMilli(10_123));
  }

  @ParameterizedTest
  @MethodSource("timestampHeaders")
  void timestampHeaderRejectsNullAndEmpty(Header<Instant> header) {
    assertThatThrownBy(() -> header.decode(null)).isInstanceOf(HeaderDecodeException.class);
    assertThatThrownBy(() -> header.decode(new byte[0])).isInstanceOf(HeaderDecodeException.class);
  }

  @ParameterizedTest
  @MethodSource("timestampHeaders")
  void timestampHeaderRejectsMalformedWithoutEchoingTheValue(Header<Instant> header) {
    for (String bad : List.of("abc", "1.5", "12 ", "-1", "9223372036854775808")) {
      assertThatThrownBy(() -> header.decode(utf8(bad)))
          .isInstanceOf(HeaderDecodeException.class)
          .hasMessageStartingWith("invalid header " + header.name() + ": epoch-ms value")
          .hasMessageNotContaining(bad);
    }
  }

  @ParameterizedTest
  @MethodSource("timestampHeaders")
  void timestampHeaderEncoderRejectsPreEpochInstants(Header<Instant> header) {
    assertThatThrownBy(() -> header.encode(Instant.ofEpochMilli(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- counters (error_attempts, defer_attempts) ----

  @ParameterizedTest
  @MethodSource("counterHeaders")
  void counterHeaderRoundTrips(Header<Integer> header) {
    for (int count : List.of(0, 1, 7, Integer.MAX_VALUE)) {
      assertThat(header.decode(header.encode(count))).isEqualTo(count);
    }
  }

  @ParameterizedTest
  @MethodSource("counterHeaders")
  void counterHeaderWireFormatIsDecimalString(Header<Integer> header) {
    assertThat(header.encode(42)).isEqualTo(utf8("42"));
    assertThat(header.encode(0)).isEqualTo(utf8("0"));
  }

  @ParameterizedTest
  @MethodSource("counterHeaders")
  void counterHeaderRejectsNullAndEmpty(Header<Integer> header) {
    assertThatThrownBy(() -> header.decode(null)).isInstanceOf(HeaderDecodeException.class);
    assertThatThrownBy(() -> header.decode(new byte[0])).isInstanceOf(HeaderDecodeException.class);
  }

  @ParameterizedTest
  @MethodSource("counterHeaders")
  void counterHeaderRejectsMalformedAndOutOfRange(Header<Integer> header) {
    for (String bad : List.of("abc", "1.5", "-1", "2147483648", "99999999999999999999")) {
      assertThatThrownBy(() -> header.decode(utf8(bad)))
          .isInstanceOf(HeaderDecodeException.class)
          .hasMessageStartingWith("invalid header " + header.name() + ": count value")
          .hasMessageNotContaining(bad);
    }
  }

  @ParameterizedTest
  @MethodSource("counterHeaders")
  void counterHeaderEncoderRejectsNegative(Header<Integer> header) {
    assertThatThrownBy(() -> header.encode(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  // ---- strings (failure_class, trace_id) ----

  @ParameterizedTest
  @MethodSource("stringHeaders")
  void stringHeaderRoundTrips(Header<String> header) {
    for (String value : List.of("", "TRANSIENT", "4bf92f3577b34da6a3ce929d0e0e4736", "ünïcode-✓")) {
      assertThat(header.decode(header.encode(value))).isEqualTo(value);
    }
  }

  @ParameterizedTest
  @MethodSource("stringHeaders")
  void stringHeaderWireFormatIsUtf8(Header<String> header) {
    assertThat(header.encode("TRANSIENT")).isEqualTo(utf8("TRANSIENT"));
  }

  @ParameterizedTest
  @MethodSource("stringHeaders")
  void stringHeaderRejectsMissingValue(Header<String> header) {
    assertThatThrownBy(() -> header.decode(null)).isInstanceOf(HeaderDecodeException.class);
  }

  // ---- encoder null handling ----

  @ParameterizedTest
  @ValueSource(strings = {"not_before", "error_attempts", "failure_class"})
  void encodingNullIsRejected(String name) {
    Header<?> header =
        HeaderCodec.ALL.stream().filter(h -> h.name().equals(name)).findFirst().orElseThrow();

    assertThatThrownBy(() -> encodeNull(header)).isInstanceOf(NullPointerException.class);
  }

  @SuppressWarnings("unchecked")
  private static void encodeNull(Header<?> header) {
    ((Header<Object>) header).encode(null);
  }
}
