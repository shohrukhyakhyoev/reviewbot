package dev.reviewbot.shared.failure;

/** The closed set of dependencies a breaker can park work on (§11.2). */
public enum Dependency {
  GITHUB,
  ANTHROPIC,
  POSTGRES,
  REDIS
}
