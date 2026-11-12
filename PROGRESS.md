# PROGRESS.md — reviewbot build journal

**The SessionStart hook injects "Current state"; every `/ticket` bundle injects "Key decisions log". `/close` updates this file.**
This file is how sessions stay oriented — Claude Code has no memory between sessions, so this is the memory
(plus `.claude/worklog/<ID>.md` within a ticket). Ticket state and mode live in `tickets/status.md`.

Keep entries short and factual. Newest at the top of "Completed"; keep the **last 5** here and move older
entries verbatim to the top of `docs/progress-archive.md`. The point is orientation, not prose.

---

## Current state (update this line every session)

- **Phase:** E2 — Infra & scaffold.
- **Last ticket completed:** E2-4 (Topic provisioning).
- **Next ticket:** E1-0 (Event envelope, validation & header codecs, mode Pair). Execution order and modes: `tickets/status.md` (`python scripts/claude/ctx.py next`).
- **Claude Code setup:** done 2026-11-12 (`docs/claude-setup-plan.md`, branch `claude-setup`). Modes per amendment 1 (16 Own tickets: the human writes the invariant/ordering cores). **No fixed date**; order: core (E1–E5) → E6 → E8 → **first deploy**, simple-first (Docker + GitHub Actions + AWS, single host) → E7, E9, E10 → EKS/Terraform (E11). Jan–Feb: exams, no project time.
- **Build status:** `./mvnw clean verify` green locally (after E2-4, which changed no Java; CI green as of E2-1, PR #2; the E2-3 Testcontainers test and the new `kafka-topics` job get their first CI run on the E2-4 PR) — 5 reactor modules, 13 tests.

---

## How to update this file (instructions for the agent)

At the end of each ticket `/close` (only after its gates pass) adds a "Completed" entry with: ticket ID, one line on what was built, any decision a future session must respect, any new file/module of note, hours, and the Learning line. It updates the "Current state" block. If you noticed something out of scope, add it to "Deferred/noticed". Do not delete history — archive it (`docs/progress-archive.md`).

The `- Learning:` line is checked by the commit-msg hook on `E?-?: …` commits: no `[ ]` left, and `teach-back: pass`.

---

## Completed (newest first)

<!-- Template for each entry:
### [E1-1] Short title — YYYY-MM-DD
- Built: one or two lines.
- Decisions: anything that constrains future work (e.g. "chose X over Y because Z").
- Files/modules: notable additions.
- Tests: what's covered.
- Hours: est X / actual Y (Y = sum of the worklog's session lines, confirmed by the human).
- Learning: [x] concept:<slug> (<link>) · [x] tech:<slug> (<link>) · [x] adr:<NNNN or n/a> · teach-back: pass (k/N)
-->

### [E2-4] Topic provisioning — 2026-11-12
- Built: `infra/kafka/provision-topics.sh` (create-if-absent, verify-never-alter; the three §4.1 topics 6/3/3, 7d/3d/30d, cleanup=delete; `KAFKA_ENV=local|prod` → RF=1/ISR=1 or RF=3/ISR=2; `--render` prints the resolved config without a broker), one-shot `kafka-init` compose service, `infra/kafka/verify-topics.sh`, CI job `kafka-topics`, topic check in `verify-stack.sh`.
- Decisions: any drift on an existing topic (partitions, RF, retention, cleanup, min ISR) exits 1 and nothing is altered; a partition change goes through the §4.4 topic swap. Verification is strict: settings must be set at topic level (a value only inherited from broker defaults reads as drift). `TOPIC_TABLE` is a test hook, refused without `ALLOW_TOPIC_TABLE_OVERRIDE=1`. The script is authoritative for infra; the `shared` constants (E1-5) must be kept in sync. `up -d --wait` does not wait for one-shot services (verify-stack retries the topic check). Notes are now written in chat via `/notes write` (setup amendment 4).
- Files/modules: `infra/kafka/provision-topics.sh`, `infra/kafka/verify-topics.sh`, `infra/docker-compose.yml` (kafka-init), `infra/verify-stack.sh`, `infra/README.md`, `.github/workflows/ci.yml`, setup docs for the notes flow (`CLAUDE.md`, `/notes` skill, guard message).
- Tests: `verify-topics.sh` (the `kafka-topics` CI job; run locally and green): prod render asserted; run 1 creates 3 topics; `--describe` asserts partitions/RF/retention/cleanup/min ISR; run 2 all UNCHANGED with identical describe (same TopicIds); wrong partition count and drifted retention refused (rc 1, topic untouched); TOPIC_TABLE guard and malformed row (rc 2). `./mvnw clean verify` green (no Java changed). The CI job's first real run is this PR.
- Hours: est 1 / actual 3
- Learning: [x] concept:n/a · [x] tech:kafka (https://app.notion.com/p/416997ba8e7c818e8cf5f4da2f174473) · [x] adr:n/a · teach-back: pass (1/1)

### [E2-3] Flyway V1 migration — 2026-11-12
- Built: `infra/db/migration/V1__init.sql` (§5.1 verbatim: reviews, installations, dlq_entries, both UNIQUEs, four indexes incl. partial `idx_reviews_last`); one-shot `flyway` compose service (`flyway/flyway:10.10.0-alpine`, mounts `infra/db/migration`, runs after Postgres is healthy); schema check in `infra/verify-stack.sh`.
- Decisions: apps do not self-migrate (O1): compose service locally, k8s Job in E11. Flyway image pinned to 10.10.0 = the Boot 3.3.5 BOM version (bump together). Applied migrations are immutable: changes go in V2+ (checksum mismatch fails the run). Parent POM passes `reviewbot.migrations.dir` to surefire so tests find `infra/` from any cwd.
- Files/modules: `infra/db/migration/V1__init.sql`, `infra/docker-compose.yml`, `infra/verify-stack.sh`, `infra/README.md`, parent `pom.xml`, `shared/pom.xml` (test deps), `shared/.../db/SchemaMigrationTest.java`.
- Tests: `SchemaMigrationTest` (10 tests; Testcontainers `postgres:16-alpine` + programmatic Flyway): clean apply, re-run no-op, three tables, both UNIQUEs, four indexes with column order, partial predicate, `round`=1 and `fence`=0 defaults. Manual: compose `flyway` run twice (apply, then "No migration necessary"); `verify-stack.sh` all green.
- Hours: est 1 / actual 2
- Learning: [x] concept:sql-upsert-constraints (https://app.notion.com/p/416997ba8e7c810185c7c834671b7ba8) · [x] tech:postgresql (https://app.notion.com/p/416997ba8e7c8187831ce49a5e1ee72b) · [x] adr:n/a · teach-back: pass (1/1)

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

---

## Key decisions log (cross-cutting, referenced later)

Decisions that affect more than the ticket that made them. Keep it tight.

- **Coordinates & packages (E2-0):** groupId `dev.reviewbot`, version `0.1.0-SNAPSHOT`, parent artifactId `reviewbot-parent`. Base packages: `dev.reviewbot.shared`, `dev.reviewbot.gateway`, `dev.reviewbot.worker`, `dev.reviewbot.retry`. **E4 must put engine classes in `dev.reviewbot.worker.engine`** — E2-1 scopes the ~100% branch coverage rule to `**/engine/**` in review-worker.
- **Maven wrapper, not system Maven (E2-0):** no `mvn` on the dev machine, so the build is driven by a committed wrapper (`mvnw`, script-only distribution — no jar committed) pinning **Maven 3.9.9**. Build command is `./mvnw clean verify` (`.\mvnw.cmd` on Windows) wherever CLAUDE.md says `mvn clean verify`. E2-1's CI should invoke `./mvnw` for the same pinning.
- **LF line endings (E2-0):** Spotless is configured `<lineEndings>UNIX</lineEndings>` and `.gitattributes` sets `* text=auto eol=lf`. Without this the format check passes on Linux CI and fails on Windows dev machines (it did, once). `*.cmd`/`*.bat` stay CRLF.
- **Version pinning lives in the parent only (E2-0):** Spring Boot's BOM pins Jackson, Flyway, the Postgres driver and Testcontainers; the parent adds explicit pins for `kafka-clients` 3.8.1, `redisson` 3.35.0, `resilience4j-bom` 2.2.0, `wiremock-standalone` 3.9.2 (shaded, keeps WireMock's Jetty off the app classpath). Child POMs carry **no** versions.
- **Claude Code workflow (setup, 2026-11-12):** ticket state + mode live only in `tickets/status.md` (backlog headers carry no markers); load tickets via `/ticket` / `scripts/claude/ctx.py`, never the whole spec/backlog. Own-mode tickets: Claude never writes code or tests (hook-enforced under `src/`); split Own tickets (E5-7, E5-9a) reserve only their Own-scope packages, so put that ticket's core logic in those packages (amendment 1, `docs/claude-setup-plan.md`).
- **Error Prone 2.51.0 (setup, 2026-11-12):** runs on every compile via the parent POM's maven-compiler-plugin + `.mvn/jvm.config`. Setting `annotationProcessorPaths` disables classpath discovery of annotation processors: **any processor a module needs (e.g. `spring-boot-configuration-processor` in E1-5) must be added to that list in the parent POM.** Fix ERROR-level findings; never suppress them to get green.
- **Code review gate (setup, 2026-11-12):** Own/Pair tickets close only after `/review-mine` (fresh-context java-mentor) is recorded at the final fingerprint with no open must-fix/should-fix (`ctx.py review-gate`; the commit-msg hook re-checks). Findings are recorded verbatim by `ctx.py record-review`; the principle tally is `docs/learning/principles.md`.
- **JaCoCo measures, gates enforced (E2-0 → E2-1):** `prepare-agent` + `report` run in every module with `**/*Application.class` excluded. E2-1 added the gates: engine rule (≥ 0.95 branch per class in `dev.reviewbot.worker.engine.*`) via `jacoco-check-engine` in the parent POM, and overall ≥ 80% line via `.github/scripts/coverage-gate.sh` in CI. **E4 must put engine classes in that package or the rule silently matches nothing.**

- **Migrations are append-only (E2-3):** never edit an applied `Vn__*.sql` (Flyway checksum mismatch fails); add `V(n+1)`. Flyway in compose is pinned to the Boot BOM's version (10.10.0).
- **`shared` tests need Docker (E2-3):** `SchemaMigrationTest` runs in surefire (`*Test`), not failsafe, so a Docker-less machine cannot build `shared`.
- **Topics come only from `infra/kafka/provision-topics.sh` (E2-4):** broker auto-create is off; the script never alters (drift → exit 1), and its topic names/partition counts must match the `shared` constants (E1-5). Change a partition count only via the §4.4 topic swap.
- **`up -d --wait` does not wait for one-shot services (E2-4):** `kafka-init`/`flyway` may still be running when it returns; checks right after `up` must retry (`verify-stack.sh` does for topics).

---

## Deferred / noticed (out-of-scope items to NOT build now)

Things spotted mid-work that belong to a later tier/epic. Record, don't build.

- ~~CLAUDE.md still says the build command is `mvn clean verify`~~ — resolved: CLAUDE.md already says `./mvnw clean verify`.
- **No branch protection on `main`** — CI is not a required status check yet. Repo-settings change, human's call (E2-1 only builds the pipeline).
- **First failsafe integration tests (E4/E5):** confirm their coverage lands in the same `target/jacoco.exec` (the parent's `prepare-agent` argLine should cover failsafe too, unverified) and that `coverage-gate.sh` sees it.
- **Action versions:** `actions/checkout`, `cache`, `upload-artifact` @v4 only warn that Node 20 is deprecated (forced to Node 24 meanwhile); bump when convenient.
- **`shared` is a plain jar with no Spring dependency.** Spec §2 puts `processReview(event, attemptContext)` in `shared`; when E5 lands it, decide then whether `shared` needs `spring-context` (optional scope) or whether the seam stays framework-free. Not a decision for E2-0.
- **`infra/verify-stack.sh` needs mode 100755 in git** — Windows checkouts don't carry the exec bit; stage with `git add --chmod=+x infra/verify-stack.sh` (same as `coverage-gate.sh`).
- ~~No Kafka healthcheck beyond "broker answers"~~ — resolved by E2-4 (`kafka-init` + `verify-topics.sh` + the topic check in `verify-stack.sh`). WireMock's healthcheck tries `curl` then `wget` (unverified which the image provides; it passes either way).
- **Learning backfill (human, no deadline pressure):** notes for already-closed tickets per `docs/learning/ticket-notes.md` — E2-1 → concept `cicd-pipeline` + tech `github-actions`; E2-2 → concept `containers` + tech `docker`. Write them `lite` whenever a Delegate ticket leaves spare time.
- **Failsafe is configured in `pluginManagement` but not activated in any module** — it binds itself when a module first declares it, from E4/E5 integration tests onward.

- **Spec §19.15 vs ticket/O1 on app-startup migration (E2-3):** §19.15 says Flyway "runs at app startup locally"; the E2-3 ticket says apps do not self-migrate (compose service locally, k8s Job in prod). Unresolved; settle in the first ticket that adds a datasource (the spec wins on conflict).
- **Testcontainers 1.19.8 (Boot 3.3.5 BOM) vs Docker Engine 29 (E2-3):** the old docker-java client may be rejected by a newer engine. Not hit locally (engine 27.5.1); if CI or a Docker upgrade fails, override `testcontainers.version` in the parent POM. First CI run with `SchemaMigrationTest` is the check.
- **Compose Flyway mount at `/flyway/sql`** prints a harmless "not recommended" warning; use `FLYWAY_LOCATIONS=filesystem:/flyway/migrations` if it bothers anyone.
- **E2-4 follow-ups:** (1) prod RF=3/ISR=2 is asserted only via `--render`; the verify path against a real RF=3 cluster (MSK `--describe` output) is first exercised in E11. (2) `flyway` has the same `--wait` race as `kafka-init`: `verify-stack.sh`'s schema check has no retry. (3) Strict topic-level verification means a correct-but-inherited value (e.g. `cleanup.policy` from broker defaults) reads as drift; revisit if E11 meets such topics. (4) Human: the ISR line in tech note `kafka` was written by Claude at the human's request; re-explain it in your own words.
- **Concept note `sql-upsert-constraints` has two stale answers** (human, with E5-0): the ON CONFLICT answer and the "duplicate events are impossible" interview answer; also add the §7.1 step 1 Redis-down DB fallback to the index answer.

---

## Open questions for the human

Anything genuinely blocking that needs Salohiddin's decision. Empty is good.

_(none yet)_
