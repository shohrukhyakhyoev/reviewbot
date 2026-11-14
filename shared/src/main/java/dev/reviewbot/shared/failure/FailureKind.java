package dev.reviewbot.shared.failure;

/** The §10.1 kind column. Each kind has exactly one route, so routing never inspects the class. */
public enum FailureKind {
  TERMINAL(Route.DLQ),
  SKIP(Route.NONE),
  TRANSIENT(Route.RETRY_ERROR_SCHEDULE),
  INFRA(Route.PARK_ON_BREAKER),
  DEFER(Route.RETRY_DEFER_SCHEDULE),
  BREAKER_REMNANT(Route.RETRY_COOLDOWN);

  /** Where a failure of this kind goes. INFRA and BREAKER_REMNANT routes touch no counters. */
  public enum Route {
    DLQ,
    NONE,
    RETRY_ERROR_SCHEDULE,
    PARK_ON_BREAKER,
    RETRY_DEFER_SCHEDULE,
    RETRY_COOLDOWN
  }

  private final Route route;

  FailureKind(Route route) {
    this.route = route;
  }

  public Route route() {
    return route;
  }

  public boolean countsAttempts() {
    return this == TRANSIENT || this == DEFER;
  }
}
