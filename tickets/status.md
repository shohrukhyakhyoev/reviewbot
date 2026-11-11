# Ticket board: the single source of ticket state and mode

Read by `scripts/claude/ctx.py`, the hooks and `/close`. **The human owns the Mode column**: changing it is
a deliberate decision (the PreToolUse guard asks before Claude edits a Mode cell). Claude updates
State and Actual h through `/ticket` (→ `wip`) and `/close` (→ `done`).

- **State:** `todo` · `wip` · `done` · `blocked` (put the reason in the worklog)
- **Mode:** `Delegate` (Claude builds, you verify) · `Pair` (Learning style; you write the `TODO(human)` cores)
  · `Own` (you write code + tests; Claude coaches, never codes) · `TBD` (decide before starting)
- **Cut:** `Dec` = December milestone chain E2-3 → E5-10 (plan option C) · `Jan+` = after December, modes proposed
- **Est h** from the hours table in `docs/claude-setup-plan.md` §4 (S/M/L × Delegate 1/1.5/2.5 · Pair 2/3.5/5 ·
  Own 4/7/10), excluding notes (~12 h lite in Dec) and the first-deploy block (~20 h, Jan 1–10).
  Dec total: 116 h (Delegate 6 · Pair 65 · Own 45).
- **Calibration:** after E4-0, if Actual/Est > 1.3, move E5-9b to Pair or move the date (plan option E).
- Row order = execution order from the backlog.

| ID | Title | Size | Mode | State | Cut | Est h | Actual h | Depends on |
|---|---|---|---|---|---|---|---|---|
| E2-0 | Maven multi-module skeleton | S | Delegate | done | done | — | — | none |
| E2-1 | CI pipeline | S | Delegate | done | done | — | — | E2-0 |
| E2-2 | Local compose stack | M | Delegate | done | done | — | — | E2-0 |
| E2-3 | Flyway V1 migration | S | Delegate | todo | Dec | 1 | — | E2-0, E2-2 |
| E2-4 | Topic provisioning | S | Delegate | todo | Dec | 1 | — | E2-2 |
| E1-0 | Event envelope, validation & header codecs | M | Pair | todo | Dec | 3.5 | — | E2-0 |
| E1-1 | PR key + Redis key builders | S | Pair | todo | Dec | 2 | — | E2-0 |
| E1-2 | Failure classification + outcome types | M | Pair | todo | Dec | 3.5 | — | E2-0 |
| E1-3 | DLQ envelope builder | S | Delegate | todo | Dec | 1 | — | E1-0, E1-2 |
| E1-4 | ReviewGenerator port + domain types | S | Pair | todo | Dec | 2 | — | E2-0 |
| E1-5 | Config models + constants | M | Delegate | todo | Dec | 1.5 | — | E2-0, E1-4 |
| E3-0 | HMAC verification filter | S | Pair | todo | Dec | 2 | — | E2-0 |
| E3-1 | Event filter + envelope build | M | Pair | todo | Dec | 3.5 | — | E3-0, E1-0 |
| E3-2 | Delivery dedup component | S | Pair | todo | Dec | 2 | — | E1-1 |
| E3-3 | Producer + endpoint orchestration | M | Pair | todo | Dec | 3.5 | — | E3-1, E3-2, E1-5 |
| E4-0 | OffsetTracker | M | Own | todo | Dec | 7 | — | E2-0 |
| E4-1 | Coalescer (pipeline step 0) | S | Own | todo | Dec | 4 | — | E1-0, E1-2 |
| E4-2 | Dispatcher + drainer + retirement | L | Own | todo | Dec | 10 | — | E4-0, E4-1, E1-1 |
| E4-3 | Poller | M | Own | todo | Dec | 7 | — | E4-0, E4-2, E1-5 |
| E4-4 | Rebalance listener + shutdown | M | Pair | todo | Dec | 3.5 | — | E4-3 |
| E4-5 | Engine integration test | L | Pair | todo | Dec | 5 | — | E4-4 |
| E5-0 | Reviews persistence + claim state machine | L | Own | todo | Dec | 10 | — | E2-3, E1-2, E1-5 |
| E5-1 | GitHub client foundation | M | Delegate | todo | Dec | 1.5 | — | E1-2, E1-5 |
| E5-2 | Redis pipeline services: dedup + lock | M | Pair | todo | Dec | 3.5 | — | E1-1, E1-5, E5-0 |
| E5-3 | BYOK key crypto + Anthropic adapter | L | Pair | todo | Dec | 5 | — | E1-4, E1-2, E1-5, E5-0 |
| E5-4 | Pipeline steps 1–4: gates + live fetch | M | Pair | todo | Dec | 3.5 | — | E5-0, E5-1, E5-2 |
| E5-5 | Pipeline steps 5–6: lock + claim | M | Pair | todo | Dec | 3.5 | — | E5-4 |
| E5-6 | Pipeline steps 7–11: config, diff, size guard, generate, filter | M | Pair | todo | Dec | 3.5 | — | E5-5, E5-3, E5-1 |
| E5-7 | Step 12: body render, HMAC marker, blind post | M | Pair | todo | Dec | 3.5 | — | E5-6 |
| E5-8 | Post-attempt guard (§7.12b) + marker search | M | Pair | todo | Dec | 3.5 | — | E5-7 |
| E5-9a | Finalize + SUCCESS/SKIP + worker wiring + producer | M | Pair | todo | Dec | 3.5 | — | E5-8, E4-2 |
| E5-9b | Failure outcome handlers + V-2 — milestone ticket | M | Own | todo | Dec | 7 | — | E5-9a, E1-3 |
| E5-10 | End-to-end happy path + idempotency integration test | L | Pair | todo | Dec | 5 | — | E5-9b, E3-3 |
| E6-0 | Pause-until-due retry consumer loop | M | Own | todo | Jan+ | 7 | — | E2-0, E1-0, E1-5 |
| E6-1 | Retry processing: one core, both workers | M | Own | todo | Jan+ | 7 | — | E6-0, E5-9b |
| E6-2 | `retry_delay_excess` + retry-flow integration test | M | Pair | todo | Jan+ | 3.5 | — | E6-1 |
| E7-0 | DLQ indexer | M | Pair | todo | Jan+ | 3.5 | — | E5-9b, E2-3 |
| E7-1 | Admin endpoints: list / replay / discard | M | Pair | todo | Jan+ | 3.5 | — | E7-0, E3-3 |
| E7-2 | DLQ metrics + resolution integration test | M | Pair | todo | Jan+ | 3.5 | — | E7-1, E6-1, E5-10 |
| E8-0 | Provider breakers + client decoration | M | Pair | todo | Jan+ | 3.5 | — | E5-1, E5-3, E1-2 |
| E8-1 | Dependency gate: breaker → pause wiring | M | Pair | todo | Jan+ | 3.5 | — | E8-0, E4-3, E6-0 |
| E8-2 | Postgres/Redis health-check breakers | M | Pair | todo | Jan+ | 3.5 | — | E8-1, E1-5 |
| E8-3 | Recovery probes (P2-1) | M | Pair | todo | Jan+ | 3.5 | — | E8-0 |
| E8-4 | Outage integration test | L | Pair | todo | Jan+ | 5 | — | E8-1, E8-2, E8-3, E5-10 |
| E9-0 | Settings authentication: OAuth + installation-admin + signed state (D-3) | M | Pair | todo | Jan+ | 3.5 | — | E3-3, E1-5 |
| E9-1 | Settings page: key validation, storage, rotation, model | M | Pair | todo | Jan+ | 3.5 | — | E9-0, E5-3, E5-0, E5-10 |
| E9-2 | `.reviewbot.yml` loader + `ghcfg` cache | M | Delegate | todo | Jan+ | 1.5 | — | E5-6, E5-1, E5-2, E8-0 |
| E9-3 | Incremental review (§7.8) | L | Pair | todo | Jan+ | 5 | — | E5-6, E5-1, E5-0, E5-10 |
| E10-0 | Telemetry completion: remaining metrics, traces, JSON logs | L | Delegate | todo | Jan+ | 2.5 | — | E5-9b, E6-1, E7-0, E8-2 |
| E10-1 | Grafana dashboards as code | M | Delegate | todo | Jan+ | 1.5 | — | E10-0, E2-2 |
| E10-2 | Alert rules | M | Delegate | todo | Jan+ | 1.5 | — | E10-0 |
| E11-0 | Container images + EKS manifests | L | TBD | todo | Jan+ | — | — | E2-1, E9-1, E10-0 |
| E11-1 | AWS foundation (Terraform) | L | TBD | todo | Jan+ | — | — | E2-4 |
| E11-2 | Runbook, cutover checklist + README | M | TBD | todo | Jan+ | — | — | E11-0, E11-1, E10-2 |
