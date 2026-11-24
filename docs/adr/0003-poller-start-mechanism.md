# 0003: Start and stop the poller through `SmartLifecycle`

- **Date:** 2026-11-24
- **Ticket:** E4-3
- **Status:** accepted
- **Author:** the human (written down by Claude from interview answers; Claude added only code locations,
  spec links and Spring facts the human confirmed; reasoning unchanged)

## Context
Most beans in the review-worker are passive: they wait until someone calls them. The engine is different:
`Engine.start()` starts the poller thread (`Engine.java:75`). That thread polls Kafka and hands records
to drainers that use other beans, so it must start only after every bean is ready and stop before any of
them is torn down. Spring does not know about the threads I start, so I had to pick the mechanism that
tells it when to start and stop the engine.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| A: start in `@PostConstruct` or the `@Bean` method | simplest; no extra class | starts too early: the poller runs before the context has finished starting, and keeps running if startup fails later |
| B: `ApplicationRunner` (or `ApplicationReadyEvent`) | starts after the context is ready | has no stop: shutdown needs a separate path, so start and stop are not one pair |
| C: `SmartLifecycle` | symmetric: Spring calls `start()` after all beans exist and `stop()` before any bean is destroyed; `isAutoStartup()` lets tests load the context without polling | one extra class that keeps its own `running` flag |

## Decision
C: the `EngineLifecycle` bean (`EngineLifecycle.java`, wired at `EngineConfig.java:48`) implements
`SmartLifecycle`, because it is the only option where start and stop are one symmetric pair.

## Consequences
- Positive: the poller starts after all beans are ready and stops first on shutdown. Poller death uses
  the same stop path (`ExitOnPollerDeath` → `SpringApplication.exit` → `EngineLifecycle.stop()`).
- Negative / accepted cost: an extra class, and a `running` flag it must keep by itself
  (`EngineLifecycle.java:12`); Spring calls `stop()` only on a bean that reports running.
- Constraint for future tickets (E4-4 shutdown drain, E5 real `OutcomeHandler` with a producer): the
  `KafkaConsumer` is never a bean, so only the poller thread closes it (I1), and the engine stops first,
  before the beans its drainers use.

## Relation to the spec
No spec coverage: implementation detail. The ticket left the start mechanism open; §12's shutdown drain
grace is E4-4 work and runs inside this `stop()`.

## Learning point
Spring doesn't know my threads. It creates and destroys beans, but a thread I start has to be tied to its
lifecycle, or it starts too early and outlives the beans it uses.

## Revisit when
`stop()` becomes slow, for example when E4-4's drain makes shutdown wait for in-flight records.
