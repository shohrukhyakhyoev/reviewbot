package dev.reviewbot.shared.failure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A §10.1 failure class. The wire form (headers §3.2, DLQ envelope §10.2) is the string. Not an
 * enum because {@code EXHAUSTED_{class}} is derived from any transient class.
 */
public final class FailureClass {

  private static final String EXHAUSTED_PREFIX = "EXHAUSTED_";
  private static final Map<String, FailureClass> KNOWN = new LinkedHashMap<>();

  public static final FailureClass POISON_DESERIALIZATION = of("POISON_DESERIALIZATION", FailureKind.TERMINAL);
  public static final FailureClass PR_NOT_FOUND = of("PR_NOT_FOUND", FailureKind.TERMINAL);
  public static final FailureClass INSTALLATION_REVOKED = of("INSTALLATION_REVOKED", FailureKind.TERMINAL);
  public static final FailureClass LLM_REQUEST = of("LLM_REQUEST", FailureKind.TERMINAL);
  public static final FailureClass DIFF_TOO_LARGE = of("DIFF_TOO_LARGE", FailureKind.TERMINAL);
  public static final FailureClass MISSING_OR_INVALID_API_KEY =
      of("MISSING_OR_INVALID_API_KEY", FailureKind.TERMINAL);
  public static final FailureClass COULD_NOT_SCHEDULE = of("COULD_NOT_SCHEDULE", FailureKind.TERMINAL);
  public static final FailureClass EXHAUSTED_POISON = of("EXHAUSTED_POISON", FailureKind.TERMINAL);
  /** Not in §10.1: a 4xx no row covers still needs a terminal route (I5). */
  public static final FailureClass UNCLASSIFIED = of("UNCLASSIFIED", FailureKind.TERMINAL);

  public static final FailureClass AUTHOR_NOT_PERMITTED = of("AUTHOR_NOT_PERMITTED", FailureKind.SKIP);
  public static final FailureClass PR_CLOSED = of("PR_CLOSED", FailureKind.SKIP);
  public static final FailureClass SUSPENDED = of("SUSPENDED", FailureKind.SKIP);
  public static final FailureClass COMMAND_NOT_PERMITTED = of("COMMAND_NOT_PERMITTED", FailureKind.SKIP);

  /** §10.1 names the situations, not the classes; these names are ours. */
  public static final FailureClass GITHUB_UNAVAILABLE = of("GITHUB_UNAVAILABLE", FailureKind.TRANSIENT);
  public static final FailureClass ANTHROPIC_UNAVAILABLE = of("ANTHROPIC_UNAVAILABLE", FailureKind.TRANSIENT);
  public static final FailureClass AMBIGUOUS_POST = of("AMBIGUOUS_POST", FailureKind.TRANSIENT);
  /** Not in §10.1: an exception nothing else classifies, retried then dead-lettered (I5). */
  public static final FailureClass UNEXPECTED = of("UNEXPECTED", FailureKind.TRANSIENT);

  public static final FailureClass PROVIDER_THROTTLE = of("PROVIDER_THROTTLE", FailureKind.DEFER);
  public static final FailureClass RATE_LIMIT = of("RATE_LIMIT", FailureKind.DEFER);
  public static final FailureClass LOCK_HELD = of("LOCK_HELD", FailureKind.DEFER);

  public static final FailureClass BREAKER_PARKED = of("BREAKER_PARKED", FailureKind.BREAKER_REMNANT);

  private final String wire;
  private final FailureKind kind;

  private FailureClass(String wire, FailureKind kind) {
    this.wire = wire;
    this.kind = kind;
  }

  private static FailureClass of(String wire, FailureKind kind) {
    FailureClass c = new FailureClass(wire, kind);
    KNOWN.put(wire, c);
    return c;
  }

  public String wire() {
    return wire;
  }

  public FailureKind kind() {
    return kind;
  }

  FailureClass requireKind(FailureKind expected) {
    if (kind != expected) {
      throw new IllegalArgumentException(wire + " is " + kind + ", expected " + expected);
    }
    return this;
  }

  /** The terminal class a transient failure becomes once its error budget is spent (§7.2). */
  public FailureClass exhausted() {
    if (kind != FailureKind.TRANSIENT) {
      throw new IllegalStateException(wire + " is " + kind + "; only TRANSIENT classes exhaust");
    }
    return new FailureClass(EXHAUSTED_PREFIX + wire, FailureKind.TERMINAL);
  }

  public static FailureClass parse(String wire) {
    Objects.requireNonNull(wire, "failure class must not be null");
    FailureClass known = KNOWN.get(wire);
    if (known != null) {
      return known;
    }
    if (wire.startsWith(EXHAUSTED_PREFIX)) {
      FailureClass base = KNOWN.get(wire.substring(EXHAUSTED_PREFIX.length()));
      if (base != null && base.kind == FailureKind.TRANSIENT) {
        return base.exhausted();
      }
    }
    throw new IllegalArgumentException("unknown failure class: " + wire);
  }

  public static List<FailureClass> known() {
    return List.copyOf(KNOWN.values());
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof FailureClass other && wire.equals(other.wire);
  }

  @Override
  public int hashCode() {
    return wire.hashCode();
  }

  @Override
  public String toString() {
    return wire;
  }
}
