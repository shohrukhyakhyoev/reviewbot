package dev.reviewbot.shared.failure;

import java.util.Objects;

/**
 * A dependency is down: a Postgres/Redis operation failed (§10.1 infra row, P1-4) or a provider
 * breaker is open (§7.1). Adapters and E8's breaker decoration wrap their driver or {@code
 * CallNotPermittedException} in this; it parks the record with no counters (§11.2).
 */
public class DependencyUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Dependency dependency;

  public DependencyUnavailableException(Dependency dependency, Throwable cause) {
    super(dependency + " unavailable", cause);
    this.dependency = Objects.requireNonNull(dependency, "dependency");
  }

  public Dependency dependency() {
    return dependency;
  }
}
