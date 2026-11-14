package dev.reviewbot.shared.dlq;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Body-writing plumbing shared by both DLQ builders. */
final class DlqJson {

  @FunctionalInterface
  interface BodyWriter {
    void write(JsonGenerator generator) throws IOException;
  }

  private static final JsonFactory FACTORY = new JsonFactory();

  // Trailing tokens must fail: the original event is spliced into the body as raw text.
  private static final ObjectMapper STRICT_READER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private DlqJson() {}

  static byte[] write(BodyWriter writer) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (JsonGenerator generator = FACTORY.createGenerator(out)) {
      generator.writeStartObject();
      writer.write(generator);
      generator.writeEndObject();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write DLQ body", e);
    }
    return out.toByteArray();
  }

  // Validates the very text that gets spliced in: Jackson's byte reader would also accept a BOM or
  // UTF-16, which a UTF-8 splice would turn into a corrupt body.
  static String requireSplicableJson(byte[] json) {
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(json))
              .toString();
      STRICT_READER.readTree(text);
      return text;
    } catch (IOException e) {
      throw new IllegalArgumentException("original event is not a single UTF-8 JSON value", e);
    }
  }

  static void writeException(JsonGenerator generator, DlqException exception) throws IOException {
    generator.writeObjectFieldStart("exception");
    generator.writeStringField("type", exception.type());
    nullableString(generator, "message", exception.message());
    if (exception.httpStatus() == null) {
      generator.writeNullField("http_status");
    } else {
      generator.writeNumberField("http_status", exception.httpStatus());
    }
    nullableString(generator, "body_snippet", exception.bodySnippet());
    if (exception.bodySnippet() != null) {
      generator.writeStringField("body_snippet_note", DlqException.SNIPPET_NOTE);
    }
    generator.writeEndObject();
  }

  static void nullableString(JsonGenerator generator, String name, String value)
      throws IOException {
    if (value == null) {
      generator.writeNullField(name);
    } else {
      generator.writeStringField(name, value);
    }
  }
}
