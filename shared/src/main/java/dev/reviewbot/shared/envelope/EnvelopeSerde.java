package dev.reviewbot.shared.envelope;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON bytes ↔ {@link ReviewRequestedEnvelope}: the one place Jackson is configured for the wire
 * contract (spec §3.1, §3.3). Producer and consumers all go through here, so nobody can bind an
 * envelope without the tolerant-reader settings, and nobody can read one without validating it.
 */
public final class EnvelopeSerde {

  /** Thread-safe once configured; shared. */
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          // Tolerant reader (§3.3 rule 2): old consumers must survive new producers.
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          // But strict about types (§3.3 rule 1: never retype). Without these Jackson binds
          // 1.9 -> 1, "17" -> 17, "true" -> true — a retyped field would pass as a plausible value.
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          // ...and numbers/booleans must not silently become strings either (event_id: 5).
          .withCoercionConfig(
              LogicalType.Textual,
              config ->
                  config
                      .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
          // `type` is its name on the wire, never its ordinal (declaration order is not a contract).
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          // WRITE side: write occurred_at as an ISO-8601 string ("2026-07-05T10:00:00Z"), not a number.
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          // READ side: Jackson would accept a numeric occurred_at as epoch seconds and silently land
          // in the wrong century, so StrictInstantReader accepts only an ISO-8601 string. Registered
          // after JavaTimeModule, so this reader wins for Instant (serialization still comes from
          // JavaTimeModule).
          .addModule(new SimpleModule().addDeserializer(Instant.class, new StrictInstantReader()))
          .build();

  private EnvelopeSerde() {}

  /**
   * Serializes an envelope for the topic value. Does not validate: the consumer is the trust
   * boundary (§3.3 rule 4).
   */
  public static byte[] toBytes(ReviewRequestedEnvelope envelope) {
    try {
      return MAPPER.writeValueAsBytes(envelope);
    } catch (IOException e) {
      // Serializing our own record cannot fail on valid input — a bug, not a poison record.
      throw new IllegalStateException("cannot serialize envelope", e);
    }
  }

  /**
   * Binds then validates. Anything that makes the bytes untrustworthy — a null/empty value,
   * malformed JSON, a wrongly-typed field, an unknown {@code type}, or a failed {@link
   * EnvelopeValidator} rule — surfaces as one {@link EnvelopeValidationException} (the {@code
   * POISON_DESERIALIZATION} input, E1-2).
   *
   * <p>The cause of a bind failure may quote an offending field value; log its class, not its
   * message.
   */
  public static ReviewRequestedEnvelope fromBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new EnvelopeValidationException("invalid envelope: empty record value");
    }
    ReviewRequestedEnvelope envelope;
    try {
      envelope = MAPPER.readValue(bytes, ReviewRequestedEnvelope.class);
    } catch (IOException e) {
      throw new EnvelopeValidationException("invalid envelope: undecodable JSON", e);
    }
    EnvelopeValidator.validate(envelope);
    return envelope;
  }

  /**
   * Reads an {@link Instant} only from a JSON string in ISO-8601 instant form (§3.1: {@code
   * "2026-07-05T10:00:00Z"}). Jackson's default also accepts a number and reads it as epoch seconds,
   * which would let a retyped {@code occurred_at} through (§3.3 rule 1).
   */
  private static final class StrictInstantReader extends StdDeserializer<Instant> {

    private static final long serialVersionUID = 1L;

    StrictInstantReader() {
      super(Instant.class);
    }

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        return (Instant) context.handleUnexpectedToken(Instant.class, parser);
      }
      String text = parser.getText();
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException e) {
        return (Instant) context.handleWeirdStringValue(Instant.class, text, "not an ISO-8601 instant");
      }
    }
  }
}
