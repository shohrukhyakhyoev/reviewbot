# PROGRESS archive

Completed entries moved out of `PROGRESS.md` once it holds more than 5 (newest at the top here too).
Verbatim, never edited. Not auto-read by any session; open it when you need history.

---

### [E2-2] Local compose stack — 2026-10-15
- Built: `infra/docker-compose.yml` (project `reviewbot`): Kafka `apache/kafka:3.8.1` (KRaft single broker, RF=1/ISR=1, `auto.create.topics.enable=false`), `postgres:16-alpine`, `redis:7-alpine`, `wiremock/wiremock:3.9.2` (empty `infra/wiremock/mappings/`). Healthchecks on all four; named volumes `kafka-data`, `postgres-data`. Plus `infra/verify-stack.sh` and `infra/README.md`.
- Decisions: **Kafka listeners:** `localhost:9092` for host processes (apps run from the IDE), `kafka:19092` for other containers; E2-4's provisioning should use one of these (e.g. `docker compose exec kafka ... --bootstrap-server localhost:19092`). **WireMock host port is 8089** (container 8080) so 8080 stays free for Spring Boot apps. Postgres db/user/password are all `reviewbot` (local only). Kafka image tag matches the `kafka-clients` 3.8.1 pin. Redis has no auth/persistence. `verify-stack.sh` runs checks via `docker compose exec` (no host Kafka/psql/redis clients) except WireMock, which uses host `curl`; it `cd`s into `infra/` instead of passing `-f <path>`, and sets `MSYS_NO_PATHCONV=1` — both needed because Git Bash on Windows mangles `/opt/kafka/...` container paths and, once conversion is off, unresolved POSIX `-f` paths.
- Files/modules: `infra/docker-compose.yml`, `infra/verify-stack.sh`, `infra/README.md`, `infra/wiremock/mappings/.gitkeep`.
- Tests: manual, no Java. Cold start (fresh images + volumes) `up -d --wait` reached all-healthy in ~90 s including image pulls. `verify-stack.sh` exit 0 with stack up (from repo root, `/tmp`, and via `./`), exit 1 with stack down. Effective broker config checked via `kafka-configs.sh`: `auto.create.topics.enable=false`, `min.insync.replicas=1`, `default.replication.factor=1`, `offsets.topic.replication.factor=1`; producing to a missing topic over the host listener returned `UNKNOWN_TOPIC_OR_PARTITION` and created nothing. `./mvnw clean verify` green.

### [E2-1] CI pipeline — 2026-10-15
- Built: `.github/workflows/ci.yml` on PRs + pushes to `main` (Spotless fast-fail → `./mvnw clean verify` → overall line gate → JaCoCo report artifact; Maven repo + wrapper cached). Engine rule in the parent POM: per-class BRANCH COVEREDRATIO ≥ 0.95 on `dev.reviewbot.worker.engine.*` (matches nothing until E4).
- Decisions: overall ≥ 80% line gate is `.github/scripts/coverage-gate.sh`, which sums each module's `target/site/jacoco/jacoco.csv` — `jacoco:check` is per module and cannot gate the reactor, and an aggregator module would fight E2-0's enforcer ban and Boot repackaging. Consequence: a module's coverage counts only from its own tests. Engine threshold is a property (`jacoco.engine.branch.minimum`, 0.95); raise to 1.00 if unreachable branches never appear. Runner pinned to `ubuntu-24.04` (`ubuntu-latest` moves to 26 on 2026-10-19). "Lint" = Spotless on Java sources only.
- Files/modules: `.github/workflows/ci.yml`, `.github/scripts/coverage-gate.sh` (mode 100755), parent `pom.xml` (`jacoco-check-engine` execution).
- Tests: the pipeline itself, green. Negative checks run once on throwaway draft PRs (base `e2-1-ci`), each failing at the intended step, then closed and branches deleted: failing test (Build and test), Spotless violation (Format check), 0% coverage (Coverage gate), half-covered engine class (Build and test, `ratio is 0.50, expected 0.95`). Engine rule also verified passing when both branches covered.


### [E2-0] Maven multi-module skeleton — 2026-08-11
- Built: parent POM (Java 21, Boot 3.3.5 BOM, pinned stack versions, pluginManagement) + 5-module reactor: `shared` (plain jar), `webhook-gateway`, `review-worker`, `retry-worker` (Boot apps). `infra/` is a plain directory, not a module. No business code.
- Decisions: see key-decisions log below (groupId/packages, Maven wrapper, LF line endings).
- Files/modules: `pom.xml`, four module POMs, three `*Application` mains + `application.yml`, `shared/.../package-info.java`, `mvnw`/`mvnw.cmd`/`.mvn/wrapper/maven-wrapper.properties`, `.gitattributes`, `infra/.gitkeep`.
- Tests: one `@SpringBootTest` context-load test per deployable (3 total, no external services). Enforcer ban verified once by adding a test-scoped `review-worker → webhook-gateway` dependency — build failed with the intended message — then reverted.
