package dev.reviewbot.shared.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.headers.HeaderCodec;

class RawDlqEnvelopeBuilderTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final byte[] PAYLOAD = "not json".getBytes(StandardCharsets.UTF_8);
  private static final byte[] KEY = "12345:acme/app:17".getBytes(StandardCharsets.UTF_8);

  @Test
  void bodyMatchesGoldenField_forField() throws IOException {
    DlqRecord record = builder(KEY, PAYLOAD, headers()).build();

    assertThat(JSON.readTree(record.body()))
        .isEqualTo(JSON.readTree(DlqEnvelopeBuilderTest.resource("raw.json")));
  }

  @Test
  void keyHeadersAndPayloadRoundTripThroughBase64() throws IOException {
    // Not valid UTF-8: replay must get these exact bytes back.
    byte[] binaryPayload = {(byte) 0xff, 0x00, (byte) 0xfe, 0x10};
    byte[] binaryKey = {(byte) 0x80, 0x01};
    Map<String, byte[]> headers = new LinkedHashMap<>();
    headers.put("blob", new byte[] {(byte) 0xc3, 0x28});

    JsonNode body =
        JSON.readTree(builder(binaryKey, binaryPayload, headers).build().body());

    assertThat(decode(body.get("raw_payload_b64"))).isEqualTo(binaryPayload);
    assertThat(decode(body.get("original_key_b64"))).isEqualTo(binaryKey);
    assertThat(decode(body.at("/original_headers/blob"))).isEqualTo(new byte[] {(byte) 0xc3, 0x28});
  }

  @Test
  void nullKeyAndNullValueAreWrittenAsJsonNull() throws IOException {
    JsonNode body = JSON.readTree(builder(null, null, Map.of()).build().body());

    assertThat(body.get("original_key_b64").isNull()).isTrue();
    assertThat(body.get("raw_payload_b64").isNull()).isTrue();
    assertThat(body.get("original_headers")).isEmpty();
  }

  @Test
  void keyIsThePoisonSentinel() {
    assertThat(builder(KEY, PAYLOAD, headers()).build().key()).isEqualTo("poison:3:42");
    assertThat(RawDlqEnvelopeBuilder.sentinelKey(0, 0)).isEqualTo("poison:0:0");
  }

  @Test
  void onlyFailureClassIsMirroredBecausePoisonHasNoIdentity() {
    DlqRecord record = builder(KEY, PAYLOAD, headers()).build();

    assertThat(record.headers()).containsOnlyKeys("failure_class");
    assertThat(HeaderCodec.FAILURE_CLASS.decode(record.headers().get("failure_class")))
        .isEqualTo("POISON_DESERIALIZATION");
  }

  @Test
  void negativePartitionOrOffsetIsRejected() {
    assertThatThrownBy(() -> RawDlqEnvelopeBuilder.forPoison(KEY, PAYLOAD, Map.of(), -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RawDlqEnvelopeBuilder.forPoison(KEY, PAYLOAD, Map.of(), 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void buildingWithAMissingRequiredPartFails() {
    RawDlqEnvelopeBuilder bare = RawDlqEnvelopeBuilder.forPoison(KEY, PAYLOAD, Map.of(), 3, 42);

    assertThatThrownBy(bare::build).isInstanceOf(NullPointerException.class).hasMessage("exception");
    assertThatThrownBy(() -> bare.exception(exception()).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("dlqAt");
  }

  private static RawDlqEnvelopeBuilder builder(
      byte[] key, byte[] payload, Map<String, byte[]> headers) {
    return RawDlqEnvelopeBuilder.forPoison(key, payload, headers, 3, 42)
        .exception(exception())
        .dlqAt(Instant.parse("2026-07-05T10:05:00Z"));
  }

  private static DlqException exception() {
    return new DlqException(
        "EnvelopeValidationException", "invalid envelope: undecodable JSON", null, null);
  }

  private static Map<String, byte[]> headers() {
    Map<String, byte[]> headers = new LinkedHashMap<>();
    headers.put("trace_id", "trace-".getBytes(StandardCharsets.UTF_8));
    headers.put("x-null", null);
    return headers;
  }

  private static byte[] decode(JsonNode node) {
    return Base64.getDecoder().decode(node.asText());
  }
}
