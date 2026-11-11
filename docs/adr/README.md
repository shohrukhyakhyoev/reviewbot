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
| — | (none yet) | | |
