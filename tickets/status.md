# Ticket board: the single source of ticket state and mode

Read by `scripts/claude/ctx.py`, the hooks and `/close`. **The human owns the Mode and Own scope columns**:
changing them is a deliberate decision (the PreToolUse guard asks before Claude edits either). Claude updates
State and Actual h through `/ticket` (→ `wip`) and `/close` (→ `done`).

- **State:** `todo` · `wip` · `done` · `blocked` (put the reason in the worklog)
- **Mode:** `Delegate` (Claude builds, you verify) · `Pair` (Learning style; you write the `TODO(human)` cores)
  · `Own` (you write code + tests; Claude coaches, never codes) · `TBD` (decide before starting)
  Own rule (amendment 1): the ticket's core logic *is* an invariant or load-bearing ordering (I1–I10, C-1, I8, I9),
  or a concurrency/idempotency core an interviewer would drill into.
- **Own scope:** `all` = every file under `src/` is the human's. A path list (split ticket) = only those packages,
  incl. their tests, are the human's; Claude writes the wiring outside them. The guard enforces both.
- **Phase:** `core` (through E5-10: pipeline works end to end) · `pre-deploy` (E6, E8: needed before the first
  deploy) · `post-deploy` (E7, E9, E10) · `later` (E11 EKS/Terraform). No dates; the order is what matters.
- **Est h** from the hours table in `docs/claude-setup-plan.md` §4 (S/M/L × Delegate 1/1.5/2.5 · Pair 2/3.5/5 ·
  Own 4/7/10; split Own 5.5), excluding notes and the first-deploy block. Core total 137.5 h.
- **Calibration:** after E4-0, compare Actual h with Est h. If Own tickets run > 1.3× over, re-estimate the
  remaining Own tickets. That's for planning only; Own tickets are never downgraded to save time.
- Row order = execution order from the backlog.

| ID | Title | Size | Mode | Own scope | State | Phase | Est h | Actual h | Depends on |
|---|---|---|---|---|---|---|---|---|---|
| E2-0 | Maven multi-module skeleton | S | Delegate | — | done | done | — | — | none |
| E2-1 | CI pipeline | S | Delegate | — | done | done | — | — | E2-0 |
| E2-2 | Local compose stack | M | Delegate | — | done | done | — | — | E2-0 |
| E2-3 | Flyway V1 migration | S | Delegate | — | done | core | 1 | 2 | E2-0, E2-2 |
| E2-4 | Topic provisioning | S | Delegate | — | done | core | 1 | 3 | E2-2 |
| E1-0 | Event envelope, validation & header codecs | M | Pair | — | done | core | 3.5 | 3 | E2-0 |
| E1-1 | PR key + Redis key builders | S | Pair | — | done | core | 2 | 1 | E2-0 |
| E1-2 | Failure classification + outcome types | M | Pair | — | done | core | 3.5 | 1.5 | E2-0 |
| E1-3 | DLQ envelope builder | S | Delegate | — | done | core | 1 | 1 | E1-0, E1-2 |
| E1-4 | ReviewGenerator port + domain types | S | Pair | — | todo | core | 2 | — | E2-0 |
| E1-5 | Config models + constants | M | Delegate | — | todo | core | 1.5 | — | E2-0, E1-4 |
| E3-0 | HMAC verification filter | S | Pair | — | todo | core | 2 | — | E2-0 |
| E3-1 | Event filter + envelope build | M | Pair | — | todo | core | 3.5 | — | E3-0, E1-0 |
| E3-2 | Delivery dedup component | S | Pair | — | todo | core | 2 | — | E1-1 |
| E3-3 | Producer + endpoint orchestration | M | Own | all | todo | core | 7 | — | E3-1, E3-2, E1-5 |
| E4-0 | OffsetTracker | M | Own | all | todo | core | 7 | — | E2-0 |
| E4-1 | Coalescer (pipeline step 0) | S | Own | all | todo | core | 4 | — | E1-0, E1-2 |
| E4-2 | Dispatcher + drainer + retirement | L | Own | all | todo | core | 10 | — | E4-0, E4-1, E1-1 |
| E4-3 | Poller | M | Own | all | todo | core | 7 | — | E4-0, E4-2, E1-5 |
| E4-4 | Rebalance listener + shutdown | M | Own | all | todo | core | 7 | — | E4-3 |
| E4-5 | Engine integration test | L | Pair | — | todo | core | 5 | — | E4-4 |
| E5-0 | Reviews persistence + claim state machine | L | Own | all | todo | core | 10 | — | E2-3, E1-2, E1-5 |
| E5-1 | GitHub client foundation | M | Delegate | — | todo | core | 1.5 | — | E1-2, E1-5 |
| E5-2 | Redis pipeline services: dedup + lock | M | Own | all | todo | core | 7 | — | E1-1, E1-5, E5-0 |
| E5-3 | BYOK key crypto + Anthropic adapter | L | Pair | — | todo | core | 5 | — | E1-4, E1-2, E1-5, E5-0 |
| E5-4 | Pipeline steps 1–4: gates + live fetch | M | Pair | — | todo | core | 3.5 | — | E5-0, E5-1, E5-2 |
| E5-5 | Pipeline steps 5–6: lock + claim | M | Own | all | todo | core | 7 | — | E5-4 |
| E5-6 | Pipeline steps 7–11: config, diff, size guard, generate, filter | M | Pair | — | todo | core | 3.5 | — | E5-5, E5-3, E5-1 |
| E5-7 | Step 12: body render, HMAC marker, blind post | M | Own | **/marker/**, **/post/** | todo | core | 5.5 | — | E5-6 |
| E5-8 | Post-attempt guard (§7.12b) + marker search | M | Own | all | todo | core | 7 | — | E5-7 |
| E5-9a | Finalize + SUCCESS/SKIP + worker wiring + producer | M | Own | **/finalize/** | todo | core | 5.5 | — | E5-8, E4-2 |
| E5-9b | Failure outcome handlers + V-2 — milestone ticket | M | Own | all | todo | core | 7 | — | E5-9a, E1-3 |
| E5-10 | End-to-end happy path + idempotency integration test | L | Pair | — | todo | core | 5 | — | E5-9b, E3-3 |
| E6-0 | Pause-until-due retry consumer loop | M | Own | all | todo | pre-deploy | 7 | — | E2-0, E1-0, E1-5 |
| E6-1 | Retry processing: one core, both workers | M | Own | all | todo | pre-deploy | 7 | — | E6-0, E5-9b |
| E6-2 | `retry_delay_excess` + retry-flow integration test | M | Pair | — | todo | pre-deploy | 3.5 | — | E6-1 |
| E7-0 | DLQ indexer | M | Pair | — | todo | post-deploy | 3.5 | — | E5-9b, E2-3 |
| E7-1 | Admin endpoints: list / replay / discard | M | Pair | — | todo | post-deploy | 3.5 | — | E7-0, E3-3 |
| E7-2 | DLQ metrics + resolution integration test | M | Pair | — | todo | post-deploy | 3.5 | — | E7-1, E6-1, E5-10 |
| E8-0 | Provider breakers + client decoration | M | Pair | — | todo | pre-deploy | 3.5 | — | E5-1, E5-3, E1-2 |
| E8-1 | Dependency gate: breaker → pause wiring | M | Own | all | todo | pre-deploy | 7 | — | E8-0, E4-3, E6-0 |
| E8-2 | Postgres/Redis health-check breakers | M | Pair | — | todo | pre-deploy | 3.5 | — | E8-1, E1-5 |
| E8-3 | Recovery probes (P2-1) | M | Pair | — | todo | pre-deploy | 3.5 | — | E8-0 |
| E8-4 | Outage integration test | L | Pair | — | todo | pre-deploy | 5 | — | E8-1, E8-2, E8-3, E5-10 |
| E9-0 | Settings authentication: OAuth + installation-admin + signed state (D-3) | M | Pair | — | todo | post-deploy | 3.5 | — | E3-3, E1-5 |
| E9-1 | Settings page: key validation, storage, rotation, model | M | Pair | — | todo | post-deploy | 3.5 | — | E9-0, E5-3, E5-0, E5-10 |
| E9-2 | `.reviewbot.yml` loader + `ghcfg` cache | M | Delegate | — | todo | post-deploy | 1.5 | — | E5-6, E5-1, E5-2, E8-0 |
| E9-3 | Incremental review (§7.8) | L | Pair | — | todo | post-deploy | 5 | — | E5-6, E5-1, E5-0, E5-10 |
| E10-0 | Telemetry completion: remaining metrics, traces, JSON logs | L | Delegate | — | todo | post-deploy | 2.5 | — | E5-9b, E6-1, E7-0, E8-2 |
| E10-1 | Grafana dashboards as code | M | Delegate | — | todo | post-deploy | 1.5 | — | E10-0, E2-2 |
| E10-2 | Alert rules | M | Delegate | — | todo | post-deploy | 1.5 | — | E10-0 |
| E11-0 | Container images + EKS manifests | L | TBD | — | todo | later | — | — | E2-1, E9-1, E10-0 |
| E11-1 | AWS foundation (Terraform) | L | TBD | — | todo | later | — | — | E2-4 |
| E11-2 | Runbook, cutover checklist + README | M | TBD | — | todo | later | — | — | E11-0, E11-1, E10-2 |
