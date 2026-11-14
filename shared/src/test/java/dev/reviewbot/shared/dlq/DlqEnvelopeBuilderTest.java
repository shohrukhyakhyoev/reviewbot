package dev.reviewbot.shared.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.envelope.EnvelopeValidationException;
import dev.reviewbot.shared.failure.FailureClass;
import dev.reviewbot.shared.headers.HeaderCodec;

/** The golden file is written from the spec §10.2 shape, not produced by the builder. */
class DlqEnvelopeBuilderTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final byte[] EVENT = resource("event-with-unknown-field.json");

  @Test
  void bodyMatchesGoldenField_forField() throws IOException {
    DlqRecord record = fullBuilder(EVENT).build();

    assertThat(JSON.readTree(record.body())).isEqualTo(JSON.readTree(resource("standard.json")));
  }

  @Test
  void originalEventIsTheOriginalBytes() {
    DlqRecord record = fullBuilder(EVENT).build();

    // The unknown field's odd spacing and 1.50 would not survive a parse and re-serialize.
    assertThat(new String(record.body(), StandardCharsets.UTF_8))
        .contains(new String(EVENT, StandardCharsets.UTF_8));
  }

  @Test
  void keyIsThePrKey() {
    assertThat(fullBuilder(EVENT).build().key()).isEqualTo("12345:acme/app:17");
  }

  @Test
  void headersMirrorFailureClassAndInstallationIdThroughTheCodecs() {
    DlqRecord record = fullBuilder(EVENT).build();

    assertThat(record.headers()).containsOnlyKeys("failure_class", "installation_id");
    assertThat(HeaderCodec.FAILURE_CLASS.decode(record.headers().get("failure_class")))
        .isEqualTo("PR_NOT_FOUND");
    assertThat(HeaderCodec.INSTALLATION_ID.decode(record.headers().get("installation_id")))
        .isEqualTo(12345L);
  }

  @Test
  void exhaustedClassIsCarriedInBodyAndHeader() throws IOException {
    FailureClass exhausted = FailureClass.GITHUB_UNAVAILABLE.exhausted();

    DlqRecord record = fullBuilder(EVENT).failureClass(exhausted).build();

    assertThat(JSON.readTree(record.body()).at("/metadata/failure_class").asText())
        .isEqualTo("EXHAUSTED_GITHUB_UNAVAILABLE");
    assertThat(HeaderCodec.FAILURE_CLASS.decode(record.headers().get("failure_class")))
        .isEqualTo("EXHAUSTED_GITHUB_UNAVAILABLE");
  }

  @Test
  void longSnippetIsCappedAndAnnotatedInTheBody() throws IOException {
    DlqException longSnippet = new DlqException("LlmRequest", "bad request", 400, "x".repeat(5000));

    DlqRecord record = fullBuilder(EVENT).exception(longSnippet).build();

    JsonNode exception = JSON.readTree(record.body()).at("/metadata/exception");
    assertThat(exception.get("body_snippet").asText())
        .hasSize(DlqException.MAX_BODY_SNIPPET)
        .endsWith("…[truncated]");
    assertThat(exception.get("body_snippet_note").asText()).isEqualTo("may echo customer input");
  }

  @Test
  void absentTraceIdIsWrittenAsNull() throws IOException {
    DlqRecord record = fullBuilder(EVENT).traceId(null).build();

    assertThat(JSON.readTree(record.body()).at("/metadata/trace_id").isNull()).isTrue();
  }

  @Test
  void anEventThatIsNotAValidEnvelopeIsPoison() {
    assertThatThrownBy(() -> DlqEnvelopeBuilder.forEvent("not json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(EnvelopeValidationException.class);
  }

  @Test
  void bytesAfterTheEventWouldCorruptTheBodyAndAreRejected() {
    byte[] trailing = (new String(EVENT, StandardCharsets.UTF_8) + " garbage").getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> DlqEnvelopeBuilder.forEvent(trailing))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyTerminalClassesAreDeadLettered() {
    DlqEnvelopeBuilder builder = DlqEnvelopeBuilder.forEvent(EVENT);

    assertThatThrownBy(() -> builder.failureClass(FailureClass.GITHUB_UNAVAILABLE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.failureClass(FailureClass.PR_CLOSED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void negativeAttemptsAreRejected() {
    DlqEnvelopeBuilder builder = DlqEnvelopeBuilder.forEvent(EVENT);

    assertThatThrownBy(() -> builder.attempts(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.attempts(0, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void buildingWithAMissingRequiredPartFails() {
    DlqEnvelopeBuilder builder = DlqEnvelopeBuilder.forEvent(EVENT);

    assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> fullBuilder(EVENT).exception(null).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("exception");
    assertThatThrownBy(() -> fullBuilder(EVENT).origin(null).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("origin");
  }

  @Test
  void aByteOrderMarkOrUtf16WouldCorruptTheSplicedBodyAndIsRejected() {
    byte[] withBom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, EVENT);
    byte[] utf16 = new String(EVENT, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_16LE);

    assertThatThrownBy(() -> DlqEnvelopeBuilder.forEvent(withBom))
        .isInstanceOfAny(IllegalArgumentException.class, EnvelopeValidationException.class);
    assertThatThrownBy(() -> DlqEnvelopeBuilder.forEvent(utf16))
        .isInstanceOfAny(IllegalArgumentException.class, EnvelopeValidationException.class);
  }

  @Test
  void aParsedEventCannotBeDeadLetteredAsPoison() {
    DlqEnvelopeBuilder builder = DlqEnvelopeBuilder.forEvent(EVENT);

    assertThatThrownBy(() -> builder.failureClass(FailureClass.POISON_DESERIALIZATION))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static DlqEnvelopeBuilder fullBuilder(byte[] event) {
    return DlqEnvelopeBuilder.forEvent(event)
        .failureClass(FailureClass.PR_NOT_FOUND)
        .attempts(2, 1)
        .exception(new DlqException("GitHubNotFound", "PR gone", 404, "{\"message\":\"Not Found\"}"))
        .firstReceivedAt(Instant.parse("2026-07-05T10:00:00Z"))
        .dlqAt(Instant.parse("2026-07-05T10:05:00Z"))
        .origin(new Origin("reviews.main", 3, 42))
        .traceId("trace-1");
  }

  static byte[] resource(String name) {
    try (InputStream in = DlqEnvelopeBuilderTest.class.getResourceAsStream("/dlq/" + name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
