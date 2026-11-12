# Claude Code setup: handoff for the main design session

```
reviewbot: Claude Code setup handoff (2026-11-12; setup commits 7347695…9995e38 + this note, fast-forwarded into main)
CONTEXT: spec.md + v1-backlog.md stay single and canonical. /ticket <ID> injects the ticket plus every cited § verbatim (scripts/claude/ctx.py, ~2–6k tokens); a hook asks before any full read.
  SessionStart re-injects ticket, mode, required notes and worklog (also after compaction); /handoff writes the worklog. Ticket state + mode live only in tickets/status.md.
MODES: 16 Own (human writes code AND tests; a hook blocks Claude edits under src/): E3-3, E4-0…E4-4, E5-0, E5-2, E5-5, E5-8, E5-9b, E6-0, E6-1, E8-1,
  plus per-package splits (hook-enforced): E5-7 (**/marker/**, **/post/**), E5-9a (**/finalize/**). Delegate: E2-3, E2-4, E1-3, E1-5, E5-1, E9-2, E10-*. E11: TBD. All others: Pair.
MODELS: Opus sessions for Own tickets, Sonnet for Delegate/Pair; the reviewer is pinned to Opus.
REVIEW: /review-mine → java-mentor subagent (fresh context). Order: correctness (invariants, ACs) → clean code → design (incl. YAGNI) → testability.
  Findings R1…Rn: must-fix/should-fix/nit, file:line, named principle, why; no hints (/hint R2 on request); recorded verbatim by script.
  /close AND the commit-msg hook stay blocked while any must-fix/should-fix is open. Principle tally: docs/learning/principles.md (≥ 2 tickets → concept-note candidate).
  Error Prone 2.51.0 runs on every compile. Validated on a fake ticket: 17 findings; re-review marked 3 fixed + 1 new; gate held.
NOTES: /ticket lists required notes → one-line "note-worthy" nudges during work → /notes check reviews a written note against its template (questions only, never writes it)
  → /close gate (status lite/understood, ticket link, teach-back). Concepts + Tech pages in Notion; ADRs in docs/adr/.
MATH (no fixed date): core E1–E5 137.5 h + ~12 h notes; E6 + E8 ~43 h; first deploy ~20 h ⇒ ~213 h at ~3 h/day. January and February are exam months (0 h).
  Realistic: Dec 31 ≈ through E5-7; first deploy ≈ early April (late March if every estimate holds). Re-estimate Own tickets after E4-0; never downgrade them.
OPEN DECISIONS
1. First deploy: simple-first (Docker + GitHub Actions + AWS, single host) after E5 + E6 + E8; E7, E9, E10 after deploy; EKS/Terraform E11 later.
   Open: AWS services (Kafka hosting is the cost driver) and an "interim deploy" note in spec §2/E11. ADR at E11-0.
2. Branch protection on main is set (CI required, linear history, admins included, rebase-only merges); revisit only if a reviewer joins.
3. Error Prone @GuardedBy annotations for the engine's lock discipline (adds the error_prone_annotations dependency): decide at E4-0.
4. Optional: jdtls-lsp plugin (Java code intelligence) — try on one Pair ticket.
```
