# Spec map: topic → spec § → tickets

A navigation index over `docs/spec.md` v1.2. It holds **no spec content**, so it can't drift from the
spec. The spec is organized by § number, and tickets cite § numbers, so the spec stays one file.
Print any section with `python scripts/claude/ctx.py spec <§>` (e.g. `7.1`, `8.intro`, `appendix:11`)
and search with `ctx.py grep <term>`.

| Topic | Spec § | Tickets |
|---|---|---|
| Modules, deployables, dependency rule | §2 | E2-0, E7-1, E11-0 |
| Event envelope, headers, schema evolution | §3.1, §3.2, §3.3, §19.5, §19.13 | E1-0, E1-1, E3-1, E3-3, E6-1 |
| **Kafka**: topics, producer, consumer config, partition migration | §4.1–§4.4 | E2-4, E3-3, E4-3, E5-9a, E6-0, E11-1 |
| **Postgres**: schema, status state machine | §5.1 | E2-3, E5-0, E7-0, E9-3 |
| **Redis**: key map, never-source-of-truth | §5.2, §19.14 | E1-1, E3-2, E5-2, E9-2 |
| **Gateway**: webhook flow (HMAC → filter → dedup → produce) | §6.1, §19.6 | E3-0 … E3-3 |
| GitHub App auth (JWT → installation token) | §6.2, §19.16 | E5-1 |
| **Review pipeline** steps 0–13 | §7 intro, §7.1 | E4-1, E5-4 … E5-9a |
| Outcome handlers, produce-then-complete (I9) | §7.2 | E1-2, E5-9b, E6-1 |
| **Engine**: poller, dispatcher, drainer, OffsetTracker (I1–I7, I10) | §8 intro, §8.1–§8.4, §19.1–§19.2 | E4-0 … E4-5 |
| **Retry worker** (pause-until-due, head-of-line) | §9 | E6-0 … E6-2 |
| **DLQ**: classification, envelope, indexer, admin | §10.1–§10.3 | E1-2, E1-3, E7-0 … E7-2 |
| **Degraded modes**: breakers, pause wiring, probes, health checks | §11, §11.1, §11.2, §19.9 | E8-0 … E8-4 |
| Every number (timeouts, caps, backoffs, TTLs) | §12 | E1-5 (+ most tickets) |
| Side-effect idempotency, markers, v1 posture | §13.1–§13.4, §19.11 | E5-7, E5-8, E5-10 |
| Product features: BYOK/settings, port, incremental, commands | §14.1–§14.5, §19.7, §19.12 | E1-4, E5-3, E9-0 … E9-3 |
| Observability: metrics, alerts | §15 | E6-2, E7-2, E10-0 … E10-2 |
| Scaling guide | §16 | E10-1 |
| Failure-mode matrix | §17 | E5-9b, E5-10, E8-4, E10-2 |
| Testing strategy, coverage gate | §18 | E2-1, E4-0, E4-5, E5-10 + all integration tests |
| Implementation traps | §19 | (mirrored in CLAUDE.md › Traps) |
| **Deploy** (AWS, k8s, local dev override) | §2, §4.4, §19.15, §12 (shutdown grace) | E2-2, E11-0 … E11-2 |
| Decision log (why the architecture is this way) | Appendix #1–#21 | interview prep; link from concept notes |
