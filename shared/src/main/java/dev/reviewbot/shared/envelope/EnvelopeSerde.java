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
 * JSON bytes ↔ {@link ReviewRequestedEnvelope} (spec §3.1, §3.3). The only place Jackson is
 * configured for the wire contract: reads always bind with these settings and then validate.
 */
public final class EnvelopeSerde {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          // Tolerant of unknown fields (§3.3 rule 2).
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          // Strict about types (rule 1): Jackson would otherwise bind 1.9 -> 1, "17" -> 17, 5 -> "5".
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .withCoercionConfig(
              LogicalType.Textual,
              config ->
                  config
                      .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
          // Enum order is not a contract: `type` is its name, never its ordinal.
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          // Registered after JavaTimeModule so this reader wins for Instant.
          .addModule(new SimpleModule().addDeserializer(Instant.class, new StrictInstantReader()))
          .build();

  private EnvelopeSerde() {}

  /** Does not validate: the consumer is the trust boundary (§3.3 rule 4). */
  public static byte[] toBytes(ReviewRequestedEnvelope envelope) {
    try {
      return MAPPER.writeValueAsBytes(envelope);
    } catch (IOException e) {
      throw new IllegalStateException("cannot serialize envelope", e);
    }
  }

  /**
   * Binds then validates. Every way the bytes can be untrustworthy surfaces as one {@link
   * EnvelopeValidationException} (the {@code POISON_DESERIALIZATION} input, E1-2).
   *
   * <p>A bind failure's cause may quote a field value; log its class, not its message.
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

  /** Jackson's default also reads a number as epoch seconds, letting a retyped field through. */
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
