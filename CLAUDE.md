# CLAUDE.md — reviewbot project rules

**reviewbot** is a GitHub App that posts AI-generated code reviews on pull requests.
The design lives in `docs/spec.md` (v1.2) — it is the source of truth for behavior.
This file holds only what cannot be inferred from the code or the spec.

## Working rules

1. **Read the ticket, then the spec sections it names**, before writing code. Never
   implement from memory of this file. On any ticket-vs-spec conflict, **the spec
   wins** — say so rather than silently choosing.
2. **Load a ticket with `/ticket <ID>`** (or `python scripts/claude/ctx.py bundle <ID>`):
   it prints the ticket plus every spec section it cites, verbatim. Never read
   `docs/spec.md` (~45 pages) or `tickets/v1-backlog.md` (~60 pages) whole — a hook
   asks first. More spec text: `ctx.py spec <§>` or `ctx.py grep <term>`. The ticket
   plus its listed spec sections are the complete input.
3. **Stay in scope.** Build what the ticket asks, nothing more. Something out of
   scope that needs doing → note it in `PROGRESS.md` under "Deferred/noticed".
4. **Never weaken a test to make it pass.** Fix the code, or explain why the test is
   wrong before changing it.
5. **Close every ticket with `/close`** — it gates on build, acceptance criteria,
   review, notes and teach-back, then updates `PROGRESS.md` (what was built, decisions
   the next session must respect, anything noticed) and `tickets/status.md`, then ships it: branch → commit
   `<ID>: <title>` → push → PR → rebase-merge on green CI → back to `main`. Sessions
   start cold; PROGRESS.md and `.claude/worklog/<ID>.md` are the memory.

## Ticket modes (Mode column of `tickets/status.md` — the human's decision, never change it)

| Mode | Who writes the code | Output style | Claude's job |
|---|---|---|---|
| Delegate | Claude | default | short plan → human's go → implement → `scripts/claude/verify.py`; human reviews + `/code-review` |
| Pair | Claude scaffolds; the human writes every `TODO(human)` | `Learning` | small steps; stop at each decision-bearing point (orderings, validation, compares, transitions) |
| Own | the human — production code AND tests | `coach` | questions, `/hint` ladder (L0–L4, never the answer), `/review-mine`; test cases as a list only; **no code** — a hook blocks edits under `src/` |
| Own, split (Own scope = package list, e.g. E5-9a `**/finalize/**`) | the human writes those packages + their tests; Claude the wiring outside | `Learning` → `coach` | wiring first, then coach; the hook blocks edits inside the listed packages |

Session loop: SessionStart injects the current ticket, mode, required notes and worklog → work →
`/handoff` before ending a session → `/review-mine` (Pair/Own) → `/notes` → `/close`.
Build check with `python scripts/claude/verify.py` (summarized output, full log in `target/`).

**Note nudge (all modes):** when the work touches a concept or technology from the current ticket's required
notes, or the human chooses between ≥ 2 viable options the spec didn't dictate, end your reply with ONE line:
`note-worthy: <what> → concept note <slug>` / `→ tech page <slug>` / `→ ADR candidate`. Don't stop the work,
don't repeat a nudge in the same session, and add ADR candidates to the worklog's "Decisions pending".
**Notes have two parts, so the page is something to learn from later, not just proof of understanding.**
*The human's sections* ("In my own words", "Mental model", the interview answers) are written in chat
(`/notes write <slug>`): Claude interviews (one question at a time, questions and pointers, never the answer),
the human answers in their own words, Claude fixes **only grammar, spelling, word choice and structure** and adds
no ideas there. A wrong, vague or missing answer first gets a follow-up question. *Claude's sections* (concept
note: Mechanism, What breaks, Contrast; tech page: APIs, gotchas, How we test, Linked concepts) are written by
Claude after the interview, labelled `(Claude)`: factual, sourced from the spec, the code (real file:line) and
official docs, concrete reviewbot examples, short; anything unverified is said to be unverified, never guessed.
If the human's answer is still wrong or unknown after the follow-up, Claude adds a `Correction (Claude)` under it
so the page ends up right. **A note is `lite` only when no section is left as a template prompt**; Claude sets the
status (Notion first, inbox fallback). **ADRs stay human-written**; `/notes check <slug|ADR file>` reviews a note
or ADR with questions only.

## Tech stack (do not substitute)

- **Java 21** — virtual threads, `ReentrantLock`. No Java 24+ features (JEP 491's
  synchronized-pinning fix does not apply here).
- **Spring Boot 3.x**, **Maven** multi-module (parent POM + `shared`,
  `webhook-gateway`, `review-worker`, `retry-worker`; `infra/` is a plain directory,
  not a module). Versions and plugins managed in the parent POM, never repeated in
  children. Build: `./mvnw clean verify`.
- **PostgreSQL 16** (Flyway), **Redis 7** (Redisson), **Apache Kafka** (plain
  `KafkaConsumer`/`KafkaProducer` — the parallel consumer is HAND-BUILT; do not add
  Confluent Parallel Consumer).
- **Resilience4j** circuit breakers. **Jackson** JSON. **Testcontainers + WireMock**
  for integration tests.
- Anthropic API behind a `ReviewGenerator` port (one adapter in v1).

## INVARIANTS — correctness laws, never violate

Not inferable from the code. Violating any is a correctness bug even if tests pass.

- **I1** Exactly one thread (the poller) touches the `KafkaConsumer` (poll, commit,
  pause, resume). It is not thread-safe.
- **I2** Two events for the same PR (`installation:repo:pr`) never process
  concurrently — per-PR drainer locally, Redisson lock across instances.
- **I3** Commit only the highest offset N where every offset ≤ N has reached a
  terminal outcome (contiguous watermark). Committing past a gap silently loses the
  gapped record. The most dangerous bug in the system — property-test it.
- **I4** Semaphore permits: acquire at dispatch, release inside `markComplete`. One
  door. A permit must never leak.
- **I5** Every record reaches exactly one terminal outcome (SUCCESS | SKIP | DEFER |
  TRANSIENT | TERMINAL | BREAKER_PARKED) before `markComplete`. The drainer's
  catch-all classifies and routes — nothing falls through silently.
- **I6** Pause/resume composes two authorities:
  `wantPause = dependencyPause || availablePermits() < MAX_POLL_RECORDS`;
  `canResume = !dependencyPause && availablePermits() >= RESUME_THRESHOLD`.
  Pause if either demands; resume only if both permit.
- **I7** On partition revocation: stop dispatching to revoked partitions, brief
  grace, commit their watermark, unregister them. In-flight zombies run on; their
  `markComplete` is recorded but never committed (generation fencing) — it still
  releases the permit. The new owner reconciles.
- **I8** Finalize order: DB (`SUCCEEDED`) → Redis dedup marker → unlock. Kafka
  commit last, via watermark after `markComplete`. Reordering reopens a
  designed-out failure.
- **I9** Produce-then-complete on every hop (main→retry, retry→retry, →DLQ): produce
  to the destination topic BEFORE `markComplete`. A crash between them redelivers
  (safe); the reverse loses events.
- **I10** Drainer retirement re-checks queue emptiness INSIDE `map.compute()`, so a
  racing record either re-loops the drainer or spawns a fresh one — no lost wakeup.

## Traps — where plausible code is wrong

- **Virtual-thread pinning:** `ReentrantLock`, not `synchronized`, around anything
  that may block. The `OffsetTracker`'s tiny synchronized block is fine (pure
  computation, no I/O).
- **Commit `watermark + 1`:** Kafka's committed offset means "next record to
  deliver," not "last processed."
- **Redisson lock:** `tryLock` WITHOUT a leaseTime (enables watchdog auto-renewal).
  A leaseTime disables renewal and reintroduces the fixed-TTL zombie. Release in
  `finally`.
- **Dedup marker means reviewed-and-posted only** — never written for a skipped or
  superseded SHA, or a later revert to that SHA is silently suppressed.
- **Marker matching:** filter the GitHub review listing to the app's own bot user
  first (an unfiltered match is forgeable), verify the marker HMAC, and paginate to
  exhaustion.
- **Jackson:** `FAIL_ON_UNKNOWN_PROPERTIES = false`; explicit required-field
  validation after binding (missing required field → POISON → DLQ).
- **HMAC:** constant-time compare (`MessageDigest.isEqual`).
- **GCM nonce:** a fresh random 96-bit nonce per encryption. Reuse under one key
  forfeits confidentiality and authenticity.
- **Header codecs** live in `shared`; both workers use them. A codec mismatch is a
  silent interop bug.
- **Secrets:** BYOK keys and the App PEM — encrypted at rest, decrypted only in the
  adapter call path, never logged, never in `.reviewbot.yml`. Never log diff or
  prompt content; log sizes and hashes.

## Definition of done

- Implements the ticket's acceptance criteria and matches the referenced spec section.
- Unit tests for the logic; engine classes (poller, dispatcher, drainer,
  OffsetTracker) ~100% branch coverage; OffsetTracker property-tested over
  randomized completion orders.
- Integration tests where the ticket spans components (Testcontainers for
  Kafka/Postgres/Redis, WireMock for GitHub/Anthropic).
- `./mvnw clean verify` green, lint clean, JaCoCo ≥ 80% overall.
- `PROGRESS.md` updated; one-line summary of what changed and why.

## Repo layout

```
pom.xml                  parent POM — dependencyManagement, pluginManagement, Java 21
shared/                  envelope, DLQ envelope, PrKey + Redis key builders, header codecs,
                         ReviewGenerator port, failure classifier, config models
webhook-gateway/         HMAC verify, delivery dedup, produce, /settings/**
review-worker/           parallel consumer engine + review pipeline + DLQ indexer + admin
retry-worker/            delay-aware retry consumer (calls the same core)
infra/                   docker-compose, Flyway migrations, k8s manifests, dashboards (plain dir)
docs/spec.md             design specification v1.2 — source of truth for behavior
docs/spec-map.md         topic → § → ticket index (navigation only; no spec content)
docs/workflow.md         how the human runs a ticket (not instructions for you)
docs/claude-setup-plan.md  Claude Code setup decisions (approved 2026-11-12)
docs/adr/                implementation decision records — human-written
docs/learning/           ticket → required notes mapping, note templates, Notion-offline inbox
tickets/v1-backlog.md    54 V1 tickets in dependency order — load via /ticket, never whole
tickets/status.md        ticket board: mode, state, hours — the single source of ticket state
PROGRESS.md              build journal — current state injected at session start; /close updates it
scripts/claude/          ctx.py (ticket/spec extractor), verify.py (build summary), selftest.py
.claude/                 settings + hooks, skills, java-mentor agent, coach style, worklog/<ID>.md
```

## Local dev

Single-broker KRaft cannot host RF=3 / min.insync.replicas=2. Local compose uses
RF=1, ISR=1; production (k8s/MSK) uses RF=3 / ISR=2. Don't chase phantom
`NOT_ENOUGH_REPLICAS` errors locally.

## Compact instructions

When compacting, preserve: the current ticket ID and mode; sub-steps done and next;
hint level per sub-problem; failing test names and their causes; decisions made this
session and ADR candidates; files changed. Drop ticket/spec text and build logs —
they reload via `ctx.py` and `verify.py`.
