# Architecture Decision Records: implementation-level decisions

Template: `docs/learning/templates/adr.md`. File name: `NNNN-kebab-title.md` (4-digit, sequential).

**Three decision logs, one job each (no duplicated content, only links):**

| Log | Holds | Written by | Read by |
|---|---|---|---|
| `docs/spec.md` Appendix | Architecture decisions (why the system is shaped this way) | design session | everyone; read-only during implementation |
| `PROGRESS.md` › Key decisions log | Constraints future sessions must respect, ≤2 lines each, linking an ADR when one exists | Claude via `/close` | Claude (injected into every `/ticket` bundle) |
| `docs/adr/` (this folder) | Implementation choices between ≥2 viable options the spec left open, with the learning point | the human | the human, reviewers, Claude |

When an ADR is required: see `docs/learning/ticket-notes.md` (ADR column). `/close` checks it.

## Index

| # | Title | Ticket | Status |
|---|---|---|---|
| [0001](0001-one-deadline-for-gateway-produce.md) | Bound the gateway produce with one shared 5 s deadline | E3-3 | accepted |
| [0002](0002-seeded-random-property-tests.md) | Property-test the OffsetTracker with seeded `java.util.Random`, not jqwik | E4-0 | accepted |
| [0003](0003-poller-start-mechanism.md) | Start and stop the poller through `SmartLifecycle` | E4-3 | accepted |
| [0004](0004-poller-fault-scopes.md) | Contain a poller fault while the invariants hold; fail fast when a record would be lost | E4-3 | accepted |
