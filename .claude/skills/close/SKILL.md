---
name: close
description: Ticket close gate. Build, acceptance criteria, review, notes, ADR and teach-back; stops at the first gap. Writes PROGRESS.md and the board only when everything passes.
argument-hint: "[ticket-id, default: current]"
disable-model-invocation: true
---

!`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" close-check $0`

---

Run the gates **in order. Stop at the first failure**, say exactly what is missing, and leave the ticket
`wip`. Don't write PROGRESS.md or the board until gate 7.

1. **Build:** `python scripts/claude/verify.py` must end in BUILD SUCCESS with the coverage gate passing.
2. **Acceptance criteria:** for each criterion above, cite the evidence (test class#method, or a recorded
   manual check). If any criterion has no evidence, stop.
3. **Review (Pair/Own):** the check above must say a `/review-mine` was recorded at the current fingerprint,
   **and** `REVIEW GATE: clear`. Any open must-fix or should-fix means stop: list them, the human fixes them, then
   `/review-mine` re-reviews. Open nits don't block (the human may `skip-nit` them). Never change a finding's
   status yourself; only `ctx.py record-review` (from the mentor) or `ctx.py skip-nit` (the human's nit choice) do.
4. **Notes:** do what `/notes status` does for the required notes (Notion first, inbox fallback). Each one
   needs status `lite` or `understood` **and** must mention this ticket ID. Missing → list them and stop.
5. **ADR:** `required` → a `docs/adr/NNNN-*.md` naming this ticket must exist. `if: …` → ask whether that
   choice came up; if yes, it is required. Check it against the template (≥2 options, a decision, a
   learning point).
6. **Teach-back:** ask the number of questions shown above (Own 5 · Pair 3 · Delegate 1), **one at a
   time**. Build them from this ticket's invariants, known traps and acceptance criteria, plus one from a
   required concept note. Ask open "why / what happens if" questions, never yes/no, and don't hint at
   the answer. Grade each **pass / partial / fail** with a one-sentence reason. A partial gets one
   follow-up. A fail means stop: name the spec § or note to re-read, record it in the worklog, and keep
   the ticket `wip`.
7. **All passed:** write these. Show the PROGRESS entry before saving it.
   - `PROGRESS.md` › Completed (newest first), using the entry template there, including
     `- Hours: est <Est h> / actual <sum of worklog sessions, confirmed by the human>` and
     `- Learning: [x] concept:<slug> (<link>) · [x] tech:<slug> · [x] adr:<NNNN or n/a> · teach-back: pass (<k>/<N>)`.
     Update **Current state** (last completed, next from `python scripts/claude/ctx.py next`, build status).
     Add constraints future tickets must respect to **Key decisions log** (≤ 2 lines each, linking the ADR).
     Add anything out of scope to **Deferred / noticed**.
   - If Completed now has more than 5 entries, move the oldest verbatim to the top of `docs/progress-archive.md`.
   - `tickets/status.md`: State `done`, Actual h. Never touch Mode.
   - Worklog: append `- <date> — 0h — closed`.
   - Propose the commit subject `<ID>: <ticket title>`. The commit-msg hook re-checks the Learning line and
     the board. Commit only if the human asks.
