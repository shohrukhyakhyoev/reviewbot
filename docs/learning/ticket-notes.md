# Ticket → required notes

Machine-read by `scripts/claude/ctx.py` (bundle, `notes`, `close-check`) and enforced by `/close`.
Edit the tables, but keep their shape (slugs in backticks, `;`-separated lists, `—` for none).

## Rules (which note, when)

- **Concept note** (Notion › Concepts, one per roadmap concept; broad concepts split as "Area › Sub"):
  required when the ticket's code *implements or relies on* the concept. First ticket creates it;
  every later listed ticket appends one "Where it shows up" line (ticket, file:line, invariant).
- **Tech page** (Notion › Tech, one per technology): required on the first ticket that calls the
  technology's API directly; later listed tickets append a section for the *new* feature they use.
- **ADR** (`docs/adr/NNNN-*.md`): only when you chose between ≥2 viable options the spec/backlog did
  not dictate. `if: …` = required only if that choice actually came up (`/close` asks you). If the choice
  is already in the spec Appendix, don't write an ADR; link "Appendix #n" from the concept note.
- **Tech page or concept note?** Would the note still be true after swapping the technology?
  Yes → concept. No → tech page. Product-named roadmap items (Redis, Kafka, PostgreSQL, Docker,
  GitHub Actions, Resilience4j) are ticked off via their tech page.
- **Status that passes the gate:** `lite` (December minimum: own-words summary + "where it shows up"
  + one interview question) or `understood` (full template). `draft` does not pass.
- Each listed note must also **mention the ticket ID** in its "Where it shows up" section.
- **Who writes:** notes are written through `/notes write <slug>`: the human answers in chat in their own words and
  Claude only fixes grammar, spelling, word choice and structure (no added ideas); Claude sets the status when the
  bar is met. ADRs stay human-written (`/notes check` reviews them with questions only).
- **Review-driven candidates:** `docs/learning/principles.md` tallies the principles java-mentor cites. One cited
  in reviews of ≥ 2 tickets becomes a candidate: append to its registry slug's note, or add a new registry row here.

## Concept registry

| Slug | Title | Roadmap area |
|---|---|---|
| `concurrency-semaphore` | Concurrency › Semaphore & permit conservation | Java › Concurrency & Multithreading |
| `concurrency-monitors-locks` | Concurrency › synchronized vs ReentrantLock | Java › Concurrency & Multithreading |
| `concurrency-chm-compute` | Concurrency › ConcurrentHashMap.compute & the lost-wakeup race | Java › Concurrency & Multithreading |
| `concurrency-jmm-volatile` | Concurrency › Java Memory Model: volatile & happens-before | Java › Concurrency & Multithreading |
| `concurrency-thread-confinement` | Concurrency › Thread confinement (single-owner objects) | Java › Concurrency & Multithreading |
| `jvm-virtual-threads` | JVM › Virtual threads, carriers & pinning | Java › JVM internals |
| `sql-upsert-constraints` | SQL › Upsert, partial indexes, UNIQUE as concurrency control | DataBase › SQL |
| `di-ioc` | Dependency Injection & the IoC container | Design Patterns › DI, IoC Container |
| `bean-lifecycle` | Spring bean lifecycle (start/stop ordering, SmartLifecycle) | Design Patterns › Beans lifecycle |
| `solid` | SOLID (with the reviewbot class that embodies each principle) | Design Patterns › SOLID |
| `gof-builder` | GoF › Builder | Design Patterns › GoF |
| `gof-adapter` | GoF › Adapter (ports & adapters) | Design Patterns › GoF |
| `gof-factory` | GoF › Factory | Design Patterns › GoF |
| `gof-decorator` | GoF › Decorator | Design Patterns › GoF |
| `gof-state` | GoF › State (and when an enum transition table is enough) | Design Patterns › GoF |
| `gof-strategy` | GoF › Strategy | Design Patterns › GoF |
| `gof-observer` | GoF › Observer | Design Patterns › GoF |
| `rest-principles` | REST principles (status-code semantics; resource design) | Web › REST API |
| `caching-strategies` | Caching strategies (cache-aside, fail-open, repopulate, negative caching) | Caching › strategies |
| `caching-levels` | Caching levels (in-process vs Redis vs DB) | Caching › levels |
| `arch-message-vs-event` | Message-based vs event-based architecture | Async › Message/Event architecture |
| `webhook-vs-websocket-webjob` | WebHook vs WebSocket vs WebJob | Async › WebHook vs WebSocket vs WebJob |
| `circuit-breaker` | Circuit breaker pattern (states, half-open, what must not trip it) | Microservices › Circuit Breakers |
| `authn-vs-authz` | Authentication vs Authorization | Auth |
| `jwt` | JWT (signing, claims, short-lived tokens) | Auth › JWT |
| `hashing-vs-encryption` | Password hashing & salting vs reversible encryption (AEAD) | Auth › password storage (contrast) |
| `oauth2-oidc` | OAuth2 & OIDC | Auth › OIDC/OAuth2 |
| `csrf` | CSRF | Auth › RBAC, CSRF, CORS |
| `containers` | Containers; Dockerfile vs Docker Compose | DevOps › Docker, Containers |
| `cicd-pipeline` | CI/CD pipeline (code → image → container) | DevOps › CI/CD |
| `observability` | Observability: metrics, logs, traces | DevOps › Observability |

## Tech registry

| Slug | Title |
|---|---|
| `kafka` | Apache Kafka (topics, producer, consumer groups, rebalance) |
| `postgresql` | PostgreSQL 16 + Flyway + JdbcTemplate |
| `redis` | Redis 7 + Redisson |
| `spring-boot` | Spring Boot 3 (config binding, filters, lifecycle, security) |
| `testcontainers-wiremock` | Testcontainers + WireMock |
| `docker` | Docker & Docker Compose |
| `github-actions` | GitHub Actions |
| `resilience4j` | Resilience4j |
| `prometheus-grafana` | Prometheus + Grafana (+ OpenTelemetry) |
| `aws` | AWS (deploy target; services decided with the deploy approach) |

## Ticket mapping

| Ticket | Concepts | Tech | ADR |
|---|---|---|---|
| E2-0 | — | — | — |
| E2-1 | `cicd-pipeline` | `github-actions` | — (backfill) |
| E2-2 | `containers` | `docker` | — (backfill) |
| E2-3 | `sql-upsert-constraints` | `postgresql` | — |
| E2-4 | — | `kafka` | — |
| E1-0 | `arch-message-vs-event` | — | — |
| E1-1 | — | — | — |
| E1-2 | — | — | if: you designed the outcome type differently from §7.2's shape (sealed interface vs enum+payload) |
| E1-3 | `gof-builder` | — | — |
| E1-4 | `solid`; `gof-adapter` | — | — |
| E1-5 | `di-ioc` | `spring-boot` | — |
| E3-0 | `authn-vs-authz`; `webhook-vs-websocket-webjob` | `spring-boot` | — |
| E3-1 | `webhook-vs-websocket-webjob` | — | — |
| E3-2 | `caching-strategies` | `redis` | — |
| E3-3 | `arch-message-vs-event`; `rest-principles` | `kafka` | if: you chose how to bound the 5 s produce await |
| E4-0 | `concurrency-semaphore`; `concurrency-monitors-locks` | — | if: property-test approach chosen (jqwik vs seeded random) |
| E4-1 | — | — | — |
| E4-2 | `jvm-virtual-threads`; `concurrency-chm-compute`; `concurrency-monitors-locks`; `solid`; `gof-strategy` | — | — |
| E4-3 | `concurrency-jmm-volatile`; `concurrency-thread-confinement`; `concurrency-semaphore`; `di-ioc`; `bean-lifecycle` | `kafka` | if: poller start mechanism chosen (SmartLifecycle vs alternatives) |
| E4-4 | `bean-lifecycle`; `concurrency-thread-confinement` | `kafka` | — |
| E4-5 | — | `testcontainers-wiremock` | — |
| E5-0 | `sql-upsert-constraints`; `gof-state` | `postgresql` | if: transition-guard representation chosen (enum table vs State classes) |
| E5-1 | `jwt`; `gof-decorator` | — | — |
| E5-2 | `caching-strategies` | `redis` | — |
| E5-3 | `gof-adapter`; `gof-factory`; `hashing-vs-encryption` | — | — |
| E5-4 | `caching-levels`; `authn-vs-authz` | — | if: in-memory cache implementation chosen (library vs hand-rolled) |
| E5-5 | — | — | — |
| E5-6 | `solid` | — | — |
| E5-7 | — | — | — |
| E5-8 | — | — | — |
| E5-9a | `di-ioc` | — | — |
| E5-9b | — | — | — |
| E5-10 | — | `testcontainers-wiremock` | — |
| E6-0 | — | `kafka` | — |
| E6-1 | `gof-strategy` | — | — |
| E6-2 | `observability` | — | — |
| E7-0 | — | `postgresql` | — |
| E7-1 | `rest-principles` | `spring-boot` | — |
| E7-2 | — | — | — |
| E8-0 | `circuit-breaker`; `gof-decorator` | `resilience4j` | — |
| E8-1 | `gof-observer` | `resilience4j` | — |
| E8-2 | `circuit-breaker` | — | — |
| E8-3 | `circuit-breaker` | — | — |
| E8-4 | — | `testcontainers-wiremock` | — |
| E9-0 | `oauth2-oidc`; `csrf`; `authn-vs-authz` | `spring-boot` | — |
| E9-1 | `hashing-vs-encryption` | — | — |
| E9-2 | `caching-strategies` | `redis` | — |
| E9-3 | — | — | — |
| E10-0 | `observability` | `prometheus-grafana` | — |
| E10-1 | `observability` | `prometheus-grafana` | — |
| E10-2 | `observability` | `prometheus-grafana` | — |
| E11-0 | `containers` | `docker`; `aws` | required: deploy approach (EKS vs simpler first deploy) |
| E11-1 | — | `aws` | — |
| E11-2 | — | — | — |

## Roadmap concepts this project does not touch (don't bend the design for them)

Spring Data JPA, Hibernate (E5-0 is explicit SQL by spec) · HATEOAS · HTTP response caching · RabbitMQ
(Kafka only) · WebSocket/WebJob (comparison section only) · API Gateways (webhook-gateway is an ingestion
edge, not an API gateway) · password hashing/salting in code (E5-3 encrypts reversibly; contrast only) ·
CORS · RBAC (E5-4's author policy is the closest, not RBAC) · JVM GC tuning, class loading, JIT ·
ELK/OpenSearch/APM/DataDog · GitLab CI/Jenkins.
