# CLAUDE.md — reviewbot project rules

You are implementing **reviewbot**, a GitHub App that posts AI-generated code reviews on pull requests. The full design lives in `docs/spec.md`. This file contains the **non-negotiable rules** for working in this repo. Read it every session. When a rule here conflicts with your instinct, the rule wins.

## How to work in this repo

1. **Read the ticket, then read the spec sections it references** (in `docs/spec.md`, spec v1.2) before writing any code. Do not implement from memory of this file — the spec is the source of truth for behavior. On any perceived conflict between a ticket and the spec, **the spec wins** — say so instead of silently choosing.
1a. **When given a ticket ID, locate ONLY that ticket's section** in `tickets/v1-backlog.md` (search for its header line). Never read the full backlog — it is ~60 pages and wastes context. The ticket plus its listed spec sections are your complete input.
2. **Plan before coding.** For any non-trivial ticket, first write a short plan (files you'll create/change, the approach, the tests you'll write) and state it. Then implement. This is required, not optional.
3. **Every ticket includes tests, and the tests must pass before the ticket is done.** No ticket is complete with failing or missing tests. See "Definition of done" below.
4. **Update `PROGRESS.md` at the end of every ticket** — what you built, key decisions, anything the next session must know. This is how future sessions stay oriented; skipping it breaks the next session.
5. **Stay in scope.** Build only what the current ticket asks. Do NOT implement Tier 2 (hardening) or Tier 3 (v1.5) work early — those are separate scheduled epics. If you notice something out of scope that needs doing, note it in `PROGRESS.md` under "Deferred/noticed", don't build it.
6. **Never weaken a test to make it pass.** If a test fails, fix the code or, if the test is genuinely wrong, explain why before changing it.

## Tech stack (do not substitute)

- **Java 21** (use virtual threads, `ReentrantLock`; do NOT use Java 24+ features — e.g. JEP 491 synchronized-pinning fixes do not apply here).
- **Spring Boot 3.x**, **Maven** multi-module (parent POM + `shared`, `webhook-gateway`, `review-worker`, `retry-worker`, `infra`). Versions and plugins are managed in the parent POM — never repeated in children. Build command: `mvn clean verify`.
- **PostgreSQL 16** (Flyway migrations), **Redis 7** (Redisson client), **Apache Kafka** (plain `KafkaConsumer`/`KafkaProducer` — the parallel consumer is HAND-BUILT, do not add Confluent Parallel Consumer).
- **Resilience4j** for circuit breakers. **Jackson** for JSON. **Testcontainers + WireMock** for integration tests.
- Anthropic API via a `ReviewGenerator` port (one adapter in v1).

## INVARIANTS — correctness laws, never violate

These cannot be inferred from the code. Violating any is a correctness bug even if tests pass.

- **I1** Exactly one thread (the poller) touches the `KafkaConsumer` object (poll, commit, pause, resume). It is not thread-safe.
- **I2** Two events for the same PR (`installation:repo:pr`) never process concurrently — enforced by the per-PR drainer locally and the Redisson lock across instances.
- **I3** Commit only the highest offset N where EVERY offset ≤ N has reached a terminal outcome (contiguous watermark). Committing past a gap silently loses the gapped record. This is the single most dangerous bug in the system — property-test it.
- **I4** Semaphore permits: acquire at dispatch, release inside `markComplete`. One door. A permit must never leak.
- **I5** Every record reaches exactly one terminal outcome (SUCCESS | SKIP | DEFER | TRANSIENT | TERMINAL | BREAKER_PARKED) before `markComplete`. The drainer's catch-all must classify and route — nothing falls through silently.
- **I6** Pause/resume composes two authorities: `wantPause = dependencyPause || availablePermits() < MAX_POLL_RECORDS`; `canResume = !dependencyPause && availablePermits() >= RESUME_THRESHOLD`. Pause if either demands; resume only if both permit.
- **I7** On partition revocation: stop dispatching to revoked partitions, brief grace, commit their watermark. In-flight zombies run on; their `markComplete` is recorded but never committed (generation fencing) — the new owner reconciles.
- **I8** Finalize order: DB (`SUCCEEDED`) → Redis dedup marker → unlock. Kafka commit is last, via watermark after `markComplete`. Reordering reopens a designed-out failure.
- **I9** Produce-then-complete on EVERY hop (main→retry, retry→retry, →DLQ): produce to the destination topic BEFORE `markComplete`. A crash between them redelivers (safe); the reverse loses events.
- **I10** Drainer retirement re-checks queue emptiness INSIDE `map.compute()` so a racing record either re-loops the drainer or spawns a fresh one — no lost-wakeup.

## Critical implementation traps (these are where plausible code is wrong)

- **Virtual-thread pinning:** use `ReentrantLock`, not `synchronized`, around anything that may block. The `OffsetTracker`'s tiny synchronized block is fine (pure computation, no I/O).
- **Commit `watermark + 1`:** Kafka's committed offset means "next record to deliver," not "last processed."
- **Redisson lock:** `tryLock` WITHOUT a leaseTime (enables the watchdog auto-renewal). Passing a leaseTime disables renewal and reintroduces the fixed-TTL zombie. Release in `finally`.
- **Jackson:** `FAIL_ON_UNKNOWN_PROPERTIES = false` on all consumers; explicit required-field validation after binding (missing required field → POISON → DLQ).
- **HMAC:** constant-time compare (`MessageDigest.isEqual`).
- **Secrets:** BYOK keys encrypted at rest (KMS envelope encryption), decrypted only in the adapter call path, NEVER logged, NEVER in `.reviewbot.yml`. Never log diff/prompt content either — log sizes and hashes.
- **Header codecs** live in the `shared` module; both workers use them (a codec mismatch is a silent interop bug).
- **Marker matching** must filter the GitHub review listing to the app's own bot user first (an unfiltered marker match is forgeable), and paginate to exhaustion.

## Definition of done (every ticket)

- Code implements the ticket's acceptance criteria and matches the referenced spec section.
- Unit tests for the logic; the engine classes (poller, dispatcher, drainer, OffsetTracker) target ~100% branch coverage; OffsetTracker gets property tests over randomized completion orders.
- Integration tests where the ticket spans components (Testcontainers for Kafka/Postgres/Redis, WireMock for GitHub/Anthropic).
- `mvn clean verify` green. Lint/format clean. JaCoCo ≥ 80% overall.
- `PROGRESS.md` updated.
- A one-line summary of what changed and why, for the human reviewer.

## Repo layout

```
pom.xml                  parent POM — dependencyManagement, pluginManagement, Java 21
shared/                  envelope, DLQ envelope, PrKey + Redis key builders, header codecs,
                         ReviewGenerator port, failure classifier, config models
webhook-gateway/         HMAC verify, delivery dedup, produce, /settings/**
review-worker/           parallel consumer engine + review pipeline + DLQ indexer + admin
retry-worker/            delay-aware retry consumer (calls the same core)
infra/                   docker-compose, Flyway migrations, k8s manifests, dashboards
docs/spec.md             the design specification v1.2 (source of truth for behavior)
tickets/v1-backlog.md    54 V1 tickets in dependency order — read ONLY the named ticket
PROGRESS.md              running build journal — read at session start, update at ticket end
```

## Local dev note

Single-broker KRaft cannot host RF=3 / min.insync.replicas=2. Local compose uses RF=1, min.insync.replicas=1. Production (k8s/MSK) uses RF=3 / ISR=2. Don't chase phantom `NOT_ENOUGH_REPLICAS` errors locally.
