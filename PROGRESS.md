# PROGRESS.md — reviewbot build journal

**Read this at the start of every session. Update it at the end of every ticket.**
This file is how sessions stay oriented — Claude Code has no memory between sessions, so this is the memory.

Keep entries short and factual. Newest at the top of "Completed". The point is orientation, not prose.

---

## Current state (update this line every session)

- **Phase:** E2 — Infra & scaffold.
- **Last ticket completed:** E2-1 (CI pipeline).
- **Next ticket:** E2-2 (local compose stack). Execution order is E2-0 → E2-1 → E2-2 → E2-3 → E2-4 → E1-0…E1-5 → … (E1 depends on E2-0).
- **Build status:** `./mvnw clean verify` green locally and in CI (PR #2) — 5 reactor modules, 3 tests.

---

## How to update this file (instructions for the agent)

At the end of each ticket, add a "Completed" entry with: ticket ID, one line on what was built, any decision a future session must respect, and any new file/module of note. Update the "Current state" block. If you noticed something out of scope, add it to "Deferred/noticed". Do not delete history.

---

## Completed (newest first)

<!-- Template for each entry:
### [E1-1] Short title — YYYY-MM-DD
- Built: one or two lines.
- Decisions: anything that constrains future work (e.g. "chose X over Y because Z").
- Files/modules: notable additions.
- Tests: what's covered.
-->

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
- **JaCoCo measures, gates enforced (E2-0 → E2-1):** `prepare-agent` + `report` run in every module with `**/*Application.class` excluded. E2-1 added the gates: engine rule (≥ 0.95 branch per class in `dev.reviewbot.worker.engine.*`) via `jacoco-check-engine` in the parent POM, and overall ≥ 80% line via `.github/scripts/coverage-gate.sh` in CI. **E4 must put engine classes in that package or the rule silently matches nothing.**

---

## Deferred / noticed (out-of-scope items to NOT build now)

Things spotted mid-work that belong to a later tier/epic. Record, don't build.

- ~~CLAUDE.md still says the build command is `mvn clean verify`~~ — resolved: CLAUDE.md already says `./mvnw clean verify`.
- **No branch protection on `main`** — CI is not a required status check yet. Repo-settings change, human's call (E2-1 only builds the pipeline).
- **First failsafe integration tests (E4/E5):** confirm their coverage lands in the same `target/jacoco.exec` (the parent's `prepare-agent` argLine should cover failsafe too, unverified) and that `coverage-gate.sh` sees it.
- **Action versions:** `actions/checkout`, `cache`, `upload-artifact` @v4 only warn that Node 20 is deprecated (forced to Node 24 meanwhile); bump when convenient.
- **`shared` is a plain jar with no Spring dependency.** Spec §2 puts `processReview(event, attemptContext)` in `shared`; when E5 lands it, decide then whether `shared` needs `spring-context` (optional scope) or whether the seam stays framework-free. Not a decision for E2-0.
- **Failsafe is configured in `pluginManagement` but not activated in any module** — it binds itself when a module first declares it, from E4/E5 integration tests onward.

---

## Open questions for the human

Anything genuinely blocking that needs Salohiddin's decision. Empty is good.

_(none yet)_
