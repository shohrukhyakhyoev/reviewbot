# PROGRESS.md — reviewbot build journal

**The SessionStart hook injects "Current state"; every `/ticket` bundle injects "Key decisions log". `/close` updates this file.**
This file is how sessions stay oriented — Claude Code has no memory between sessions, so this is the memory
(plus `.claude/worklog/<ID>.md` within a ticket). Ticket state and mode live in `tickets/status.md`.

Keep entries short and factual. Newest at the top of "Completed"; keep the **last 5** here and move older
entries verbatim to the top of `docs/progress-archive.md`. The point is orientation, not prose.

---

## Current state (update this line every session)

- **Phase:** E1 — core contracts (E2 infra scaffold done).
- **Last ticket completed:** E1-2 (Failure classification + outcome types).
- **Next ticket:** E1-3 (DLQ envelope builder, mode Delegate). Execution order and modes: `tickets/status.md` (`python scripts/claude/ctx.py next`).
- **Claude Code setup:** done 2026-11-12 (`docs/claude-setup-plan.md`, branch `claude-setup`). Modes per amendment 1 (16 Own tickets: the human writes the invariant/ordering cores). **No fixed date**; order: core (E1–E5) → E6 → E8 → **first deploy**, simple-first (Docker + GitHub Actions + AWS, single host) → E7, E9, E10 → EKS/Terraform (E11). Jan–Feb: exams, no project time.
- **Build status:** `./mvnw clean verify` green locally after E1-2 — 5 reactor modules, 258 tests (coverage gates passed). E1-2's PR is the first CI run for the `failure` package.

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

### [E1-2] Failure classification + outcome types — 2026-11-14
- Built: failure vocabulary in `shared/.../failure/`: `FailureClass` (every §10.1 string; `EXHAUSTED_{class}` derived and parsed), `FailureKind` (kind → route, counters), `Outcome` (sealed: Success/Skip/Defer/Transient/Terminal/BreakerParked; each rejects a class of the wrong kind), typed exceptions (`TerminalReviewException` is E8's `ignoreExceptions`), `ProviderError` + `ProviderErrorClassifier` (status/headers → class; ambiguous 403; Retry-After seconds/HTTP-date and reset epoch → `not_before`), `FailureClassifier` (any Throwable → one Outcome).
- Decisions: names the spec lacks, now fixed: transient `GITHUB_UNAVAILABLE` / `ANTHROPIC_UNAVAILABLE` / `AMBIGUOUS_POST` / `UNEXPECTED`, terminal `UNCLASSIFIED` (a 4xx no row covers). Outage or open provider breaker = `DependencyUnavailableException(Dependency)` → `BreakerParked`; bad envelope or header → `POISON_DESERIALIZATION`; `classify` looks through wrapper exceptions. `not_before` is dropped if unparseable, past, or more than 1 day out. `Defer`/`Transient`/`Terminal` carry the exception so the DLQ envelope keeps status, body snippet and cause. Claude wrote all code at the human's request (shallow ticket, time-boxed).
- Files/modules: 12 main + 4 test files in `shared/.../failure/`.
- Tests: one table-driven case per §10.1 row; 403 both ways; Retry-After and reset parsing; wrapped and cyclic causes; body capped and kept out of `toString`. `verify.py` green: 258 tests. Five `/review-mine` rounds (R1–R17; R16 and R17 skipped by the human).
- Hours: est 3.5 / actual 1.5
- Learning: [x] concept:n/a · [x] tech:n/a · [x] adr:n/a · teach-back: pass (3/3; Q1 needed a follow-up, Q2 had a correction)

### [E1-1] PR key + Redis key builders — 2026-11-13
- Built: `PrKey` record (installation id, repo `owner/name`, PR number) with `render()` and `parse()`; `RedisKeys` builders for `delivery`, `ghcfg`, `dedup` (SHA + round), `lock` (no SHA) and the four TTL `Duration` constants (24 h / 7 d / 30 s / 5 min).
- Decisions: every `PrKey` is valid by construction (checks in the compact constructor); `parse` accepts only the canonical form (`render()` of the result must equal the input), so one PR has one key string and one Kafka partition. Ids and PR numbers must be >= 1; repo must match `owner/name` over GitHub's charset. `ghcfg` reuses `PrKey`'s package-private validity helpers. No `skip:` builder (§19.14). `LOCK_TTL` is the Redisson watchdog timeout, never a `leaseTime`.
- Files/modules: `shared/.../keys/{PrKey,RedisKeys}.java`; CLAUDE.md working rule 6 (sparse comments); comments trimmed in the E1-0 `shared` main files (behavior-neutral).
- Tests: `PrKeyTest` (golden render, round trip for dots/hyphens/underscores, malformed and non-canonical matrices incl. Arabic-Indic digits, error messages name the key, constructor bounds) and `RedisKeysTest` (golden strings written from §5.2, round differs per key, lock has no SHA, input rejection, TTLs). `verify.py` green: 163 tests, `shared` 97.5%. Five `/review-mine` rounds (R1–R10, all fixed).
- Hours: est 2 / actual 1
- Learning: [x] concept:n/a · [x] tech:n/a · [x] adr:n/a · teach-back: pass (3/3; Q1 and Q2 needed a follow-up)

### [E1-0] Event envelope, validation & header codecs — 2026-11-13
- Built: `ReviewRequestedEnvelope` (§3.1 record) + `EventType`; `EnvelopeValidator` (all fields required except force/replay, schema_version == 1, head_sha per type via an exhaustive switch expression, collects every problem); `EnvelopeSerde` (bytes ↔ envelope, bind then validate); `EnvelopeValidationException`; `HeaderCodec` + `HeaderDecodeException` (all seven §3.2 headers as typed constants, encode + decode).
- Decisions: reading is tolerant of unknown fields but strict on types: no scalar coercion (1.9, "17", 5 for a string), no enum ordinals, `occurred_at` only as an ISO string (`StrictInstantReader`). An invalid, missing or wrongly typed envelope → `EnvelopeValidationException` (E1-2: POISON_DESERIALIZATION). A bad header → `HeaderDecodeException`, deliberately a different type; E1-2 decides the routing (the DB is the source of truth for the counters). Headers are used only via `HeaderCodec.NOT_BEFORE.encode/decode` etc. (private constructor, name and format bound together); encoders reject negatives. `toBytes` does not validate (the consumer is the trust boundary).
- Files/modules: `shared/.../envelope/*`, `shared/.../headers/*`, `shared/pom.xml` (jackson-databind, jackson-datatype-jsr310; versions from the Boot BOM), `.claude/skills/ticket/SKILL.md` (Orient section rewritten at the human's request: concise "What this ticket is" paragraph).
- Tests: `EnvelopeContractTest` (48: round trip, tolerant reader, one missing-field case per required field, blank/null, schema_version, head_sha matrix, 12 strict-typing rows, malformed JSON) and `HeaderCodecTest` (39: per header the name, exact wire format, round trip incl. epoch 0 and year 9999, malformed/negative/overflow, raw value never echoed). `./mvnw clean verify` green; `shared` 96.5% line coverage. Four `/review-mine` rounds (R1–R12; R12 skipped by the human).
- Hours: est 3.5 / actual 3
- Learning: [x] concept:arch-message-vs-event (https://app.notion.com/p/417997ba8e7c81d0adcddf8d623fd853; status `lite` since 2026-11-13, written after the close once Notion recovered) · [x] tech:n/a · [x] adr:n/a · teach-back: pass (3/3)

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
- **Envelope reading (E1-0):** read envelopes only via `EnvelopeSerde.fromBytes` (bind → validate), never a raw ObjectMapper. Tolerant of unknown fields, strict on types; any failure is `EnvelopeValidationException` (E1-2: POISON_DESERIALIZATION).
- **Header codecs (E1-0):** headers only via the `HeaderCodec` constants (`HeaderCodec.NOT_BEFORE.encode/decode`); a bad header is `HeaderDecodeException`, not an envelope failure (E1-2 decides routing). Never write a second encoder (§19.13).
- **Failure classification (E1-2):** every throwable becomes one `Outcome` only via `FailureClassifier.classify` (I5, C-2); an outage or open provider breaker is `DependencyUnavailableException(Dependency)` → `BreakerParked` (E8 wraps `CallNotPermittedException`; Postgres/Redis adapters wrap driver errors); provider errors enter via `ProviderError`.
- **Failure classes we named (E1-2):** transient `GITHUB_UNAVAILABLE`/`ANTHROPIC_UNAVAILABLE`/`AMBIGUOUS_POST`/`UNEXPECTED`, terminal `UNCLASSIFIED`. **E7 must page on `UNCLASSIFIED` and `EXHAUSTED_UNEXPECTED` like `LLM_REQUEST`.**
- **PR and Redis keys (E1-1):** build keys only via `PrKey.render()` / `RedisKeys`, read Kafka keys only via `PrKey.parse` (canonical form only; repo must be `owner/name`). No `skip:` builder (§19.14).
- **Comments are sparse (setup, 2026-11-13):** CLAUDE.md working rule 6: only a non-obvious why; no restating the code, no redundant `@throws`/`@param`.

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
- **E1-0 follow-ups:** (1) review nit R12 skipped: `EnvelopeSerde.fromBytes` ignores bytes after the first JSON object; fix is `FAIL_ON_TRAILING_TOKENS` plus a test. (2) `toBytes` does not validate; E3-1 decides whether the gateway validates before producing. (3) Human's idea, post-v1: notify the user when a job lands in the DLQ. (4) Note `arch-message-vs-event` is now complete in Notion (status `lite`). The lines in it written by Claude at the human's request (the extra "In my own words" bullets and the interview answer) should be re-explained in the human's own words. (5) Teach-back Q3 was partial: the unknown-vs-missing-field direction was reversed; re-read §3.3 rules 1–2.
- **E1-1 follow-ups:** (1) ADR candidate (human to write or skip): enforce `PrKey` validity in the compact constructor (chosen) vs only in `parse`. (2) Concept-note candidates from reviews: "Fail fast on malformed input", "Actionable failure messages", "Comments match the code". (3) `verify.py` given extra Maven args (e.g. `-pl shared -am`) fails with "No goals have been specified"; pass `clean verify` explicitly. (4) Comments are not yet trimmed in the E1-0 and schema tests. (5) Teach-back: re-read §8.3 and I2 (the per-PR drainer serializes a PR on one instance; the Redisson lock covers two instances), and that a skipped SHA must never be written under `dedup:`.
- **E1-2 follow-ups:** (1) R17 skipped: `FailureClassifier.recognise` is an `instanceof` chain, so a fourth `ReviewFailureException` subtype would silently become `UNEXPECTED`; E5 extends `recognise` if it adds one. (2) E5 throws `AMBIGUOUS_POST` on timeout-after-send; the classifier cannot know that context. (3) Anthropic's `anthropic-ratelimit-*-reset` headers are not parsed, only `retry-after` and `x-ratelimit-reset`. (4) Status → class mappings beyond §10.1 (GitHub 401/403, Anthropic 401/403/404) are unverified against the real APIs (§19.10). (5) Human: read `FailureClassifier`, `ProviderErrorClassifier` and `Outcome` once, slowly; Claude wrote them. (6) Teach-back: re-read §10.1 (404 is terminal, not skip) and §7.2 (the 2 h defer SLA escalates to `COULD_NOT_SCHEDULE`; it is not the retry delay).
- **Concept note `sql-upsert-constraints` has two stale answers** (human, with E5-0): the ON CONFLICT answer and the "duplicate events are impossible" interview answer; also add the §7.1 step 1 Redis-down DB fallback to the index answer. Since 2026-11-13 the page carries a `Correction (Claude)` under each stale answer; the human should still re-answer them in their own words.
- **Notes format (setup, 2026-11-13):** notes now have two parts: the human's sections in their own words, and `(Claude)` reference sections (Mechanism, What breaks, Contrast; tech: APIs, gotchas, How we test, Linked concepts), with a `Correction (Claude)` under a wrong human answer. `lite` means no section is left as a template prompt. Backfilled: `sql-upsert-constraints`, `arch-message-vs-event`, `kafka`, `postgresql`. Known cosmetic glitch: Notion auto-links bare `CLAUDE.md` and "file:line" on older pages; new pages use backticks.

---

## Open questions for the human

Anything genuinely blocking that needs Salohiddin's decision. Empty is good.

_(none yet)_
