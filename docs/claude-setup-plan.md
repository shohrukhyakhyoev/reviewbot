<!-- Approved by the human on 2026-11-12 (Phase 1 of the Claude Code setup). Source of truth for the setup decisions; docs/spec.md remains the source of truth for system behavior. -->

# reviewbot — Claude Code setup: Phase 1 decisions

## Context

reviewbot has a final spec (93 KB, ~23k tokens), a 54-ticket backlog (146 KB, ~37k tokens), and a CLAUDE.md with no agents, skills or hooks yet. Done: E2-0, E2-1, E2-2. 51 tickets remain. The setup has two jobs: (1) every session, subagent and compaction should start from the right small context and not from the whole spec and backlog; (2) it should make the human write and understand the core code, which matters for Werkstudent/junior interviews in Germany, and it should hold the rules the human asked for (no code in Own mode, never weaken a test, notes before closing a ticket).

Phase 0: no questions block the design. These facts from the repo shaped it:
- `docs/workflow.md` is referenced in CLAUDE.md but **does not exist**.
- The backlog's `[ ]/[x]` header markers have already drifted. E2-1 and E2-2 still show `[ ]`, but PROGRESS.md says they are done.
- PROGRESS.md is 9.5 KB after only 3 tickets. At that rate it reaches about 150 KB by E11, so it needs an archive split.
- The ticket modes (Delegate/Pair/Own) are not recorded anywhere yet.
- E5-0 uses explicit SQL (JdbcTemplate) by spec ruling, so JPA/Hibernate are not touched.
- Runtimes on this machine: Python 3.11, Git Bash, Java 21, Claude Code 2.1.322. No `jq` and no `node`. **Hook scripts will therefore be Python.**
- The Notion connector is already visible in this session (claude.ai MCP, deferred tools).

Docs checked (each recommendation below relies on these pages):
CLAUDE.md, rules and imports: https://code.claude.com/docs/en/memory · Skills (custom commands are now part of skills): https://code.claude.com/docs/en/skills · Commands: https://code.claude.com/docs/en/commands · Subagents: https://code.claude.com/docs/en/sub-agents · Hooks: https://code.claude.com/docs/en/hooks · Plugins: https://code.claude.com/docs/en/plugins · Context/compaction: https://code.claude.com/docs/en/context-window · Costs/context habits: https://code.claude.com/docs/en/costs · Output styles: https://code.claude.com/docs/en/output-styles

---

## 1. Context management: file structure and lookup

**Verdict on your friend's advice (topic-modular .md files): adapt it.** The goal is right: Claude should read only what a ticket needs. The mechanism doesn't fit this project, for three reasons:
- Topic files break § resolution. §5 mixes Postgres and Redis, and §12, §17 and §19 cut across every topic.
- Copies drift from the spec, and that weakens "spec wins".
- The spec is already modular **by § number**, and every ticket already says which § it needs.

What's missing is a cheap way to load exactly those sections. The same reasoning applies to splitting the backlog per epic: extracting one ticket gives the same benefit without restructuring anything.

**Decision:**
- `docs/spec.md` and `tickets/v1-backlog.md` stay **single, canonical and unchanged**. § references keep resolving, "spec wins" holds trivially, and the main design session keeps the same file.
- `scripts/claude/ctx.py` is a deterministic extractor:
  - `ctx.py ticket E4-0` prints the ticket section.
  - `ctx.py spec 8.4` prints a § section.
  - `ctx.py bundle E4-0` prints the ticket's status row and mode, a dependency-state check, the ticket verbatim, **each cited § extracted verbatim** (it parses "Spec sections to read first", maps O-rulings to their spec homes, and maps "decision log #n" to the Appendix), the required notes, and the worklog. It lists any reference it can't resolve instead of guessing.
  - A bundle is about 4–12k tokens, compared with about 60k for reading both files whole.
- `/ticket <ID>` injects that bundle through skill dynamic context (`` !`…` ``). Claude gets the ticket and its spec text in one turn. Rule 1 (read the ticket and the spec sections it names) then happens by construction instead of depending on Claude remembering it.
- `docs/spec-map.md` is the topic view your friend wanted, as an index only: Kafka → §3.2, §4, §8, §9 (+ tickets); DB → §5.1, §7.1 steps 1/2/6; gateway → §6, §14.1; deploy → §2, §4.4, §19.15, E11. Links only, no copied text. Its purpose is coaching questions that aren't tied to a ticket.
- **Enforced, not just written:** a PreToolUse hook returns `ask` on any `Read` of `docs/spec.md` or `tickets/v1-backlog.md` that has no `limit`. The prompt tells you to use `ctx.py` instead, and you can still approve a full read, as for this task.
- Ticket state and mode move to **`tickets/status.md`**: one 54-row board with ID, title, size, mode, state, estimated hours, actual hours and deps. It becomes the single source of ticket state, and the backlog header markers are removed in migration because they already drifted.
- **CLAUDE.md stays the always-loaded home** for I1–I10, the traps and the definition of done, kept verbatim. Path-scoped `.claude/rules/` was rejected because path-scoped rules are summarized away on compaction (context-window doc), which would weaken the invariants. Target: under 160 lines (the docs recommend under 200).
- CLAUDE.md changes:
  - Fix the `infra`-as-module error ("parent + 4 modules; `infra/` is a plain directory").
  - Replace "search the backlog" with `/ticket` / `ctx.py`.
  - Add a short Modes table.
  - Add a `# Compact instructions` block: keep the ticket ID, mode, hint levels, failing test names and decisions.
  - Point to `docs/workflow.md`, which will be created.

**File tree (new or changed):**
```
CLAUDE.md                          rewritten (invariants/traps/DoD verbatim; +modes, +lookup, +compact block)
PROGRESS.md                        restructured: current state, key decisions, deferred, last 5 entries
docs/progress-archive.md           older Completed entries (never auto-read)
docs/spec.md                       unchanged
docs/spec-map.md                   topic → § → ticket index
docs/workflow.md                   human runbook: daily loop per mode (fixes dangling ref)
docs/claude-setup-plan.md          these decisions (written first after approval)
docs/adr/                          decision log entries (NNNN-title.md) + README index
docs/learning/ticket-notes.md      ticket → required concept/tech/ADR mapping (machine-parsed by /close)
docs/learning/templates/           concept-note.md, tech-page.md, adr.md
docs/learning/inbox/               Notion-offline fallback notes (gitignored)
tickets/v1-backlog.md              unchanged except state markers removed + how-to line → status.md
tickets/status.md                  the board (mode, state, est/actual h)
scripts/claude/ctx.py              extractor/bundler
scripts/claude/verify.py           runs ./mvnw clean verify, prints summary + failures only
.claude/settings.json              hooks + project permissions (committed)
.claude/hooks/session_start.py     SessionStart injector
.claude/hooks/pretool_guard.py     mode guard + test-weakening tripwire + big-doc read guard
.claude/skills/{ticket,hint,review-mine,handoff,notes,close}/SKILL.md
.claude/agents/java-mentor.md
.claude/output-styles/coach.md
.claude/worklog/<ID>.md            per-ticket handoff state (committed)
.claude/state/current              current ticket pointer (written only by /ticket's render step)
.githooks/commit-msg (+ .py)       close-commit gate
```

## 2. Session and subagent handoff

**Verdict on your friend's advice: partly wrong about how Claude Code works today.** Normal subagents **start fresh**. They get the delegation message, CLAUDE.md, a git status snapshot and any preloaded skills. They don't get your conversation or your auto memory. Only forks inherit the conversation. New sessions also start fresh. `/resume` replays a whole old conversation, which is big and stale. So "picking up the previous context" has to be a small written artifact plus automatic injection. Anything else means re-reading everything or replaying everything.

Design:
- **Within a ticket, across sessions:** Own L tickets run 10 h or more, spread over 3–5 days. The `/handoff` skill writes `.claude/worklog/<ID>.md` at session end. It records:
  - mode
  - sub-steps done and next
  - **hint level per sub-problem** (otherwise the hint ladder resets)
  - open questions
  - failing tests
  - decisions pending an ADR
  - a working-tree fingerprint
- **SessionStart hook** (`startup|resume|clear|compact`) injects about 1k tokens:
  - current ticket and mode
  - PROGRESS "Current state" block
  - that worklog
  - a stale-worklog warning when the working tree changed after the worklog was written
  - an output-style mismatch warning (e.g. Own mode but not on the `coach` style)

  Because the `compact` source is included, the handoff survives auto-compaction. SessionEnd can't do this: its hooks can't make Claude write anything.
- **Across tickets:** PROGRESS.md stays the memory, but slimmer. It keeps current state, key decisions (constraints), deferred items and the last 5 entries. Older entries go to `docs/progress-archive.md`. Each new entry gets `Hours: est/actual` and `Learning:` lines.
- **Subagents:** the delegation message carries only the ticket ID, mode and diff range. `java-mentor` runs `ctx.py bundle <ID>` itself, which is deterministic and keeps the main context small. It is deliberately not a fork: a reviewer that didn't coach you is the point. It uses `memory: project`, so `.claude/agent-memory/java-mentor/` keeps a human-readable list of *your recurring mistakes* with ticket references. This is the one learning use of persistent agent memory I recommend.
- **Habits in `docs/workflow.md`:**
  - `/clear` between tickets
  - `/compact <focus>` mid-ticket
  - `/btw` for side questions that shouldn't pollute context
  - `/rewind` to undo a wrong direction
  - `/context` when a session feels slow
- Auto memory (`~/.claude/projects/.../memory`) stays for personal preferences only. It's machine-local and not in git, so project state never goes there.

## 3. Components (only what you'll use)

| Component | Problem it solves in your workflow | Cost |
|---|---|---|
| Skill `/ticket <ID>` (`disable-model-invocation: true`) | Starting cold on a ticket. Injects the bundle, sets `.claude/state/current`, says which output style to switch to and lists the required notes. | 0 tokens until used; 4–12k when used (instead of ~60k). ctx.py ~150 lines. |
| Skill `/hint` | Own mode, where you attempt first. Escalates exactly one level per call, and only after a new attempt (working-tree fingerprint changed). Persists the level in the worklog. | 0 until used; small. |
| Skill `/review-mine` → agent `java-mentor` | Reviewing *your* code (Pair/Own). Invariants that apply to this ticket, traps, clean code, SOLID, and patterns only where they really apply. Socratic, file:line references. **Tools are read-only (Read/Grep/Glob/Bash for git and mvn), so it physically can't edit.** | One subagent run per review (model `inherit`); ~60 tokens of description always loaded; memory file ≤25 KB. |
| Skill `/handoff` | Multi-day tickets (see §2). | 0 until used. |
| Skill `/notes` | Lists the notes this ticket requires and whether each exists (Notion search, or the inbox fallback). Scaffolds empty templates with the "where it shows up" section pre-filled. **The explanation is yours to write.** | 0 until used; Notion MCP calls. |
| Skill `/close` | Close gate: verify.py, required notes present and marked understood, ADR if the mapping requires one, teach-back (Own 5 / Pair 3 / Delegate 1 questions from the ticket's invariants and traps). On any gap it **stops** and leaves the ticket `[~]`. On a pass it writes the PROGRESS entry and status.md row and archives the worklog. | 0 until used. |
| Output style `coach` (custom) | Own mode needs "no code" at system-prompt level, which is stronger than a CLAUDE.md line. | ~400 tokens/request while active. |
| Built-in output style `Learning` | Revised Pair mode: Claude scaffolds and leaves `TODO(human)` at the decision points for you to write. | Longer responses by design. |
| Hook `SessionStart` | Cold start and compaction (see §2). | ~1k tokens/session. |
| Hook `PreToolUse` guard (Edit\|Write\|NotebookEdit\|Bash\|PowerShell\|Read) | Mode guard, test-weakening tripwire, big-doc read guard (details below). | ~50 ms Python per matching call; 0 context unless it fires. |
| Git `commit-msg` hook (`core.hooksPath=.githooks`) | Stops a close commit (`E4-0: …`) when the PROGRESS entry's Learning line is missing or has unchecked items. WIP commits use `wip(E4-0):` and aren't gated. | One-time `git config`. |
| `scripts/claude/verify.py` | `./mvnw clean verify` output floods context. The script prints only the summary, failing tests and coverage-gate lines. | ~60 lines. |
| Built-ins to use (no setup) | `/code-review` on Delegate diffs; `/security-review` on E3-0, E5-1, E5-3, E9-*; `/compact`, `/clear`, `/btw`, `/rewind`, `/context`, `/usage`. | — |
| Plugin `jdtls-lsp` (official), optional | Type errors right after Claude's edits without a 30 s Maven cycle; go-to-definition for the mentor. Try it once in a Pair ticket and drop it if jdtls is flaky on Windows. | Needs a local jdtls install. |

**Can the rules be enforced, or only written down?**
- **No code in Own mode:**
  - *File edits: enforced.* The guard denies Edit/Write/NotebookEdit under `**/src/**` when `.claude/state/current` is an Own ticket.
  - *Shell writes: best-effort.* A regex denies redirects, `sed -i`, `tee`, `cp` and `mv` into `src/`.
  - *Code typed in chat: not enforceable.* A Stop hook only fires after the text is already on screen. Mitigation: the `coach` style, plus a no-code clause in java-mentor's prompt.
  - *Changing the mode: enforced against Claude.* The guard denies edits to `.claude/state/**`. Only `/ticket`'s render step, which runs on your invocation, writes it. An edit that changes a Mode cell in status.md triggers `ask`.
- **Never weaken a test: partly enforced.** The guard returns `ask` (you approve) when an edit:
  - adds `@Disabled` or `assumeTrue(false)`
  - removes `@Test`/`@Property`
  - lowers the count of `assert*`/`verify(`/`assertThat` in the replaced hunk
  - touches `jacoco.engine.branch.minimum`, `coverage-gate.sh` or skip flags
  - runs mvnw with `-DskipTests`, `-Dmaven.test.skip`, `-Djacoco.skip` or `spotless.check.skip`

  CI already enforces the coverage numbers. *Semantic* weakening, such as loosening an expected value, can't be detected; your diff review covers it.
- **Read only the named ticket: enforced** (read guard plus `/ticket`).
- **Spec wins: written only.** It's reinforced because the bundle puts the spec text next to the ticket text.
- **I1–I10: enforced by tests** (DoD, engine 95% branch gate, property tests) and by java-mentor's per-ticket checklist. A hook can't check them.
- **Notes before close: enforced at commit time** (commit-msg hook). `--no-verify` is your deliberate override.

**Considered and rejected:**
- Topic-split spec: breaks § resolution and creates drift.
- Per-epic or per-ticket backlog files: the extractor makes them unnecessary.
- `.claude/rules/` path-scoped invariants: lost on compaction.
- `/resume` as the handoff: replays a stale, oversized conversation.
- Forked reviewer: inherits the coach's bias and the full context cost.
- spec-lookup subagent: a script is deterministic and cheaper.
- test-runner subagent: verify.py is cheaper.
- `spotless:apply` PostToolUse formatter: Maven start-up on every edit, and verify plus CI already gate formatting.
- Stop-hook "code in chat" detector: fires after the text is displayed.
- `prompt`/`agent`-type hooks judging tests: a model call per edit, and non-deterministic.
- Agent teams: experimental and roughly 7× the tokens.
- Worktree isolation for Delegate tickets: friction on Windows, and you're the only developer.
- Custom `/commit` skill: the commit-msg hook already does the gating.
- `hookify`, `claude-md-management`, `learning-output-style` plugins: three explicit hooks are clearer, `/close` maintains PROGRESS, and `Learning` is now a built-in style.

## 4. Learning layer

**Modes, revised to fit "write most code myself":**
- **Delegate:** Claude implements. You read the diff, run `/code-review`, and answer 1 teach-back question.
- **Pair (redefined):** on the `Learning` style. Claude writes wiring, config and test scaffolding. **You write the decision-bearing core at `TODO(human)` markers**, for example E1-0's validator, E3-0's constant-time compare, E3-3's C-1 ordering and E5-7's marker HMAC. Then `/review-mine` and 3 teach-back questions.
- **Own:** on the `coach` style. You write the production code and the tests. Claude only gives hints, never code. `/review-mine` after you're green, then 5 teach-back questions. The guard enforces it.

**Hint ladder (Own):** you attempt first. Each `/hint` raises one sub-problem by one level, and only if your code changed since the last hint.
- **L0** Restate the invariant and ask what you tried.
- **L1** Point to the spec § and the concept note.
- **L2** Name the API or technique and its contract (e.g. what `NavigableSet.remove` returns).
- **L3** Describe the algorithm shape in prose, or give a counterexample input that breaks your code.
- **L4** Give the file:line and the bug class (e.g. "off-by-one at the commit site"). **Never the fixed code.**

If you're stuck at L4 for more than 45 min, the only escape is that *you* change the ticket to Pair in status.md, and that is logged.

**Close gate:** `/close` reads `docs/learning/ticket-notes.md`. For each required note it checks:
- **Notion:** search the Concepts and Tech databases for a page with Status=understood.
- **Fallback:** if MCP is unavailable or errors, check `docs/learning/inbox/<slug>.md` with `notion: pending`. `/notes sync` pushes it to Notion later.

It also asks one question about the note's content, then runs the teach-back. A failed answer leaves the ticket `[~]` with a "re-read §x" pointer.

**Notion structure** (created in Phase 3 with your OK): one "reviewbot learning" page with two databases.
- **Concepts:** roadmap area, status, tickets, relation to Tech.
- **Tech:** version, tickets.

**Roadmap → December-cut tickets (concept notes in Notion):**

| Roadmap concept | Ticket(s) where it shows up |
|---|---|
| Concurrency: Semaphore & permit conservation | E4-0, E4-3 |
| Concurrency: monitors (synchronized vs ReentrantLock) | E4-0, E4-2 |
| JVM internals: virtual threads & carrier pinning | E4-2 |
| Concurrency: `ConcurrentHashMap.compute` & lost wakeup | E4-2 |
| Concurrency: JMM (`volatile`, happens-before) | E4-3 |
| Concurrency: thread confinement (I1) | E4-3, E4-4 |
| Message- vs event-based architecture | E1-0, E3-3 |
| WebHook (vs WebSocket/WebJob, comparison section) | E3-0, E3-1 |
| Caching strategies (cache-aside, fail-open, repopulate) | E3-2, E5-2 |
| Caching levels (in-process O7 vs Redis vs DB) | E5-4 |
| SOLID (DIP / SRP / OCP) | E1-4 (DIP); E4-2 (SRP: the sharing map); E5-6 (OCP: `RepoConfigSource` seam) |
| GoF Builder | E1-3 |
| GoF Adapter / Factory | E1-4, E5-3 |
| GoF Decorator | E5-1 (call-decoration seam) |
| GoF State | E5-0 (transition guard) |
| GoF Strategy | E4-2 (`JobExecutor`/`CompletionHandler`) |
| DI & IoC container | E1-5, E4-3, E5-9a |
| Bean lifecycle | E4-3 (start), E4-4 (shutdown) |
| AuthN vs AuthZ | E3-0 (HMAC), E5-4 (author authorization) |
| JWT | E5-1 |
| REST principles (status-code semantics only) | E3-3 |
| SQL (upsert, partial index, UNIQUE as concurrency control) | E2-3, E5-0 |
| Password hashing & salting — *contrast only*: reversible AES-GCM encryption vs hashing | E5-3 |
| Backfill (already done) | Dockerfile vs Compose & containers: E2-2; CI/CD pipeline: E2-1 |

**Tech pages, Dec cut (Notion):** Kafka (E2-4, E3-3, E4-3/4), PostgreSQL+Flyway (E2-3, E5-0), Redis+Redisson (E3-2, E5-2), Spring Boot (E1-5, E3-0, E4-3), Docker (backfill), GitHub Actions (backfill), Testcontainers+WireMock (E4-5, E5-10).

**Post-December (mapped, not in the cut):** Circuit breaker pattern + Resilience4j (E8-0..3), Observer (E8-1), Strategy (E6-1 completion strategy), OAuth2/OIDC & CSRF (E9-0), REST resource design (E7-1), Observability (E10), containers in production (E11 / deploy block).

**Not touched by this project; don't bend the design:**
- Spring Data JPA, Hibernate (E5-0 is explicit SQL by spec)
- HATEOAS
- HTTP response caching
- RabbitMQ (Kafka only)
- WebSocket, WebJob (comparison only)
- API Gateways (webhook-gateway is an ingestion edge, not an API gateway, which is a good interview distinction)
- Password hashing/salting in code
- CORS
- RBAC (E5-4's permission policy is the closest thing, but it isn't RBAC)
- JVM GC tuning, class loading, JIT
- ELK/OpenSearch/APM/DataDog
- GitLab CI/Jenkins

**December cut and the math.** Priority: pipeline end to end means the milestone chain E2-3 → E5-10, which is 30 tickets. The 21 tickets in E6–E11 come after December.

Available time: Nov 11–Dec 31 is 51 days × 3 h = **153 h nominal**, about **130 h realistic** after a 15% allowance for holidays, illness and applications.

Hours per ticket (my estimate, ±30%; calibrate after E4-0, see below). These include the bundle, review, teach-back and CI fixes, but not notes:

| Size | Delegate | Pair | Own |
|---|---|---|---|
| S | 1 | 2 | 4 |
| M | 1.5 | 3.5 | 7 |
| L | 2.5 | 5 | 10 |

Notes: about 22 concept notes and 7 tech pages. Full depth is ~24 h; lite (own-words summary + "where it shows up" + 1 question) is ~12 h. Deploy (simple Docker + GitHub Actions + AWS) is one 20 h block.

**Your split as stated:** 11 Own (E1-2, E4-0..4, E5-0, E5-2, E5-5, E5-9a, E5-9b), 14 Pair, 5 Delegate.
- Tickets: 6 Delegate + 47.5 Pair + 80 Own = 133.5 h.
- With full notes and the deploy: **177.5 h**.

**It doesn't fit.** Options:

| Option | Delivers by Dec 31 | Hours | vs 153 | vs 130 |
|---|---|---|---|---|
| A: as stated | E5-10 + deploy | 177.5 | −24.5 | −47.5 ✗ |
| B: A, deploy in January | E5-10 locally | 157.5 | −4.5 | −27.5 ✗ |
| **C (recommended): 6 Own, notes-lite in Dec, deploy Jan 1–10** | E5-10 green locally | 128.3 | +24.7 | +1.7 (thin) |
| D: C, deploy in December | E5-10 + deploy | 148.3 | +4.7 | −18.3 ✗ |
| E: A, move the date | E5-10 + deploy | 177.5 | done ~Jan 9 nominal / ~Jan 19 realistic | |

**C's mode split** (Delegate 5 → 6 h, Pair 19 → 65 h, Own 6 → 45 h; total **116 h** + 12.3 h notes):
- **Own:** E4-0, E4-1, E4-2, E4-3, E5-0, E5-9b. This is the engine plus claim plus failure handling, i.e. the interview story.
- **Own → Pair:** E1-2, E4-4, E5-2, E5-5, E5-9a. These are mostly library usage or small ordering code; the ordering is still yours at `TODO(human)`.
- **Pair:** E1-0, E1-1, E1-4, E3-0, E3-1, E3-2, E3-3, E4-5, E5-3, E5-4, E5-6, E5-7, E5-8, E5-10.
- **Delegate:** E2-3, E2-4, E1-3, E1-5, E5-1.
- After December: E6-0 and E6-1 (retry pause loop) stay Own.
- Human-written share of effort is ~60%: all of Own plus about 40% of Pair.

**Calibration rule:** status.md records actual hours. After E4-0, if actual/estimate is above 1.3, move E5-9b to Pair or take option E. Decide with data, not hope.

## 5. Note templates and when each is required

| Note | Required when | Lives in |
|---|---|---|
| **Concept note** (one per roadmap concept; broad ones split as "Concurrency › X") | The ticket's code implements or relies on a roadmap concept. Create it the first time; after that, append one "where it shows up" line per ticket. | **Notion** Concepts DB (fallback: inbox) |
| **Tech page** (one per technology) | The first ticket that calls the technology's API directly. Later tickets that use a *new* feature of it append a section (e.g. Kafka: producer E3-3, consumer E4-3, rebalance E4-4). | **Notion** Tech DB. These are personal reference that outlives the repo and links to concept notes. |
| **Decision log entry (ADR)** | Only when you chose between two or more viable options the spec/backlog did *not* dictate (e.g. jqwik vs seeded-random in E4-0, SmartLifecycle vs `@EventListener` for poller start in E4-3). Not every ticket. | **Repo** `docs/adr/NNNN-*.md`. Versioned with the code, visible to reviewers, and Claude can read it. |

Template fields (written in Phase 2):
- **Concept note:** concept/area · in my own words · mechanism · where it shows up (ticket, file:line, invariant) · what breaks if misused (link to the trap) · contrast/alternatives · 2–3 interview questions I can answer · sources (spec §, docs) · status (draft/understood).
- **Tech page:** version (as pinned in the parent POM) · problem it solves here · mental model · how reviewbot uses it (config values and why, with §) · APIs we touch · local vs prod gotchas · how we test it · linked concepts · interview questions.
- **ADR:** number/title/date/ticket/status · context · options (≥2, pros/cons) · decision · consequences · relation to the spec (must not contradict it; if it would, that's a spec question) · **learning point** · revisit trigger.

**Overlap 1: the three decision logs.**
- *Spec Appendix* = architecture decisions, made in the design session; read-only during implementation.
- *PROGRESS "Key decisions"* = constraints future sessions must respect (agent-facing, ≤2 lines each), linking an ADR when one exists.
- *Your ADRs* = implementation-level choices plus your learning point (human-facing).
- Rule: if the choice is already in the spec Appendix, don't write an ADR; link Appendix #n from the concept note instead. Content lives in one place, and the others only link to it.

**Overlap 2: tech page vs concept note.** Test: would the note still be true after swapping the technology?
- Yes → concept note. Examples: caching strategies, message vs event architecture, the circuit breaker *pattern*, the CI/CD pipeline idea, containers.
- No → tech page. Examples: Redis, Kafka, PostgreSQL, Resilience4j, GitHub Actions, Docker.
- Roadmap items that name a product (Redis, Kafka, PostgreSQL, Docker, GitHub Actions, Resilience4j) get a tech page and are ticked off via that page.

## 6. ECC and similar repos

The repo (now `affaan-m/ECC`, ~301k stars, release 2.2.3 dated 2026-10-01) currently states **71 agents / 314 skills / 98 commands**. The reel's 64/262/84 are outdated numbers. It also says plugins can't ship rules, so those are copied manually. Its continuous learning works like this:
- `observe.sh` on Pre/PostToolUse logs every tool call to `~/.local/share/ecc-homunculus`.
- A background Haiku observer (off by default) turns the log into confidence-scored "instincts", and `/evolve` clusters them into skills.
- The skill doc says nothing about git mining. "From repo history" is a separate ECC-Tools GitHub App, and the README's `/skill-create` claim isn't backed by the skill doc.
- **The observer doesn't run on native Windows**: the process is killed when the hook exits.

| Piece | Verdict | Why (for this project) |
|---|---|---|
| "Install everything" plugin | Skip | Every model-invocable skill/agent description sits in context on every turn (plugins doc). Auto-triggered skills would do work in Own mode. |
| 71 agents | Skip; **adapt** one | Read its `java-reviewer` checklist and take the Java items that fit into `java-mentor`. |
| Java/Spring/JPA skills | Skip | JPA isn't used (E5-0 is JdbcTemplate). Generic Spring advice adds nothing over the spec. |
| 98 commands | Skip | Commands are now skills. You need 6 specific ones, not 98 generic ones. |
| Formatter-after-edit hook | Skip | Spotless through Maven on every edit is slow; verify and CI already gate it. |
| Session-start/end/pre-compact memory hooks | **Adapt** | Same idea as our SessionStart + worklog, but tied to tickets and in git. |
| Memory Vault | Skip | Third memory store; PROGRESS + worklog + ADRs cover it. |
| Preset rules | Skip | Generic rules dilute adherence to I1–I10 (memory doc: specific and concise wins). |
| MCP configs | Skip | Only Notion is needed. |
| Continuous learning / instincts | Skip; **adapt** the kernel | Broken on Windows. It logs every tool input, which could include keys on settings paths. It encodes Claude's habits, not the spec. *You* should be the one extracting lessons. Kept: java-mentor's human-readable "recurring mistakes" memory. |
| AgentShield | Skip | The built-in `/security-review` on the security tickets covers it. |
| **obra/superpowers** (official marketplace, ~316k stars) | Adapt the ideas, don't install | `verification-before-completion` → our `/close`; `test-driven-development` → Own mode's tests-first in `coach`. Its auto-triggering process skills and `subagent-driven-development` write the code for you, which contradicts Own mode. |
| **anthropics/claude-plugins-official** | Adopt `jdtls-lsp` (optional); skip the rest | `learning-output-style` copies what is now a built-in style; `hookify`, `claude-md-management` and `session-report` duplicate our hooks, `/close` and `/usage`. |

---

## After approval: execution order (Phases 2–3, summarized)

1. Write these decisions to `docs/claude-setup-plan.md`.
2. Phase 2 writes:
   - CLAUDE.md (invariants, traps and DoD verbatim; diff-checked)
   - `.claude/` settings, hooks, 6 skills, the agent and the `coach` style
   - `scripts/claude/ctx.py` and `verify.py`
   - the 3 templates, `ticket-notes.md` (all 51 remaining tickets) and `status.md` with option C's modes
   - `spec-map.md` and `workflow.md`
   - `.githooks/`

   Then list everything created and stop for your diff review.
3. Phase 3 migration steps, each with a check:
   - Remove the backlog state markers and diff-check that only header lines changed.
   - Archive PROGRESS entries and check that the line count is preserved.
   - `git config core.hooksPath .githooks` and test a failing close commit.
   - Create the Notion DBs.
   - Backfill notes for E2-1 and E2-2.

   Then a handoff note of 25 lines or fewer, with open decisions: deploy approach, option C vs E, jdtls.

## Verification (Phase 2/3)

- `python scripts/claude/ctx.py bundle <ID>` for all 54 IDs: every § reference resolves or is listed as unresolved, and no bundle is over ~15k tokens.
- Guard hook tests, piping sample JSON to the script:
  - Own-mode Edit on `src/main` is denied.
  - Pair is allowed.
  - Adding `@Disabled` asks.
  - A full Read of `spec.md` asks.
  - A Write to `.claude/state/` is denied.
- SessionStart: `claude` in the repo shows the injected ticket/mode; `/compact` re-injects it.
- `/ticket E2-3` end to end in a fresh session; `/context` shows the bundle size.
- commit-msg: an `E2-3: x` commit without a Learning line fails, and a `wip(E2-3):` commit passes.
- CLAUDE.md: I1–I10, traps and DoD diff-identical to the current text, except for the `infra` fix.
- `./mvnw clean verify` still green (no Java touched).

---

## Amendment 1 (2026-11-12): the human owns the hard parts

Approved by the human; supersedes the option-C mode split in §4. Learning depth matters more than any date.

**Own rule:** a ticket is Own if its core logic *is* an invariant or a load-bearing ordering (I1–I10, C-1, I8,
I9), not just listing I5, or it is a concurrency/idempotency core an interviewer would drill into.
Boilerplate, config, clients and test harnesses stay Pair or Delegate.

**Own set (16):**
- Core phase (13): E3-3 (C-1) · E4-0 (I3/I4) · E4-1 (supersede ≠ reviewed, P1-1) · E4-2 (I2/I4/I10) · E4-3 (I1/I6) ·
  E4-4 (I7) · E5-0 (class-aware claim, P1-2) · **E5-2** (distributed lock, I2 cross-instance) · E5-5 (lock + claim) ·
  **E5-7, split** (post_attempted_at-before-POST + HMAC marker) · E5-8 (§13.2 guard) · **E5-9a, split** (I8) · E5-9b (I9, C-2, C-3)
- Later (3): E6-0 (I1/I6) · E6-1 (I9 with commitSync) · E8-1 (I6 dependency-pause half)
- Kept Pair: E1-2 (I5 vocabulary only) · E4-5, E5-10 (harnesses; the human writes the invariant assertions as
  `TODO(human)`) · E3-0, E5-3, E9-0 (security, not ordering/concurrency) · E3-2, E5-4 (cost orderings) ·
  E7-0/E7-1 (repeat of the I9 pattern) · E8-2 (feeds I6, isn't I6).

**Split tickets (hook-enforced by package):** `tickets/status.md` › Own scope lists the human's packages, which
cover their tests too. E5-9a: `**/finalize/**` (Claude does the producer config and wiring). E5-7: `**/marker/**`,
`**/post/**` (Claude does body rendering and defang in `**/render/**`). Claude does the wiring first in `Learning`
style, then the human writes the core in `coach` style. E3-3 stays whole-Own (its config is about 20 lines).

**Tests in Own tickets:** the human writes all of them, fixtures included. Claude may list test cases (names +
what each asserts), never test code. Enforced by the same guard.

**Math (hours only, no deadline):** core phase 137.5 h (Own 91 · Pair 40.5 · Delegate 6) + ~12 h lite notes.
Pre-deploy E6 + E8 ≈ 40 h + ~3 h notes, then the simple deploy (~20 h): ≈ 213 h to the first deploy. Jan–Feb
are exam months (no project time). The human chose to work as fast as possible without a fixed date. Order:
core → E6 → E8 → simple deploy → E7, E9, E10 → E11. Recalibrate the remaining Own estimates after E4-0, but
never downgrade an Own ticket to save time.
