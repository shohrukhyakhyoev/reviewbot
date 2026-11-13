---
name: ticket
description: Start or resume a reviewbot ticket. Injects the ticket, every spec section it cites, its mode, required notes and worklog, and sets it as the current ticket.
argument-hint: "<ticket-id, e.g. E4-0>"
disable-model-invocation: true
---

!`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" bundle $0 --set-current`

---

The bundle above is the **complete input** for this ticket (CLAUDE.md working rules 1–2). Do not read
`docs/spec.md` or `tickets/v1-backlog.md` whole. For more spec text use
`python scripts/claude/ctx.py spec <§>` (e.g. `7.1`, `8.intro`, `appendix:11`) or `ctx.py grep <term>`.

## 1. Orient (≤ 12 lines total, plain words, then stop if anything blocks)
Open with a **"What this ticket is"** paragraph (3–4 lines, no undefined jargon): what gets built, who
calls it and when (gateway / review-worker / retry-worker), where it sits in the flow
webhook → envelope → Kafka → worker, and what is explicitly out of scope. Define any term the human
might not know (e.g. serde, codec) in one clause.
Then ONE status line: `Mode · size · est · deps` (if the bundle shows ⚠ NOT ALL DONE, stop and say which).
Then only if non-empty, one line each:
- Ticket-vs-spec conflict: **the spec wins.** Name the conflict; don't silently pick.
- Unresolved references in the bundle: resolve with `ctx.py grep` and say what you found.
- Output style or model mismatch (the bundle names both; Opus for Own, Sonnet for Delegate/Pair): tell the
  human to run `/output-style <style>` / `/model`. You cannot switch either yourself. Say nothing when they match.
- Required notes (from the bundle): e.g. `concept concurrency-semaphore (new) · tech kafka (append section)`,
  ADR required / conditional / none. If there are none, say "no notes required".

## 2. Book-keeping
- In `tickets/status.md` set this ticket's **State** cell to `wip` (never touch the Mode cell).
- In `.claude/worklog/<ID>.md` fill **Sub-steps** from the ticket's scope bullets and named tests.

## 3. Work by mode
- **Delegate:** propose a plan of 15 lines or fewer: files/classes, and which test covers each acceptance criterion.
  Wait for the human's go. Implement, run `python scripts/claude/verify.py`, summarize the diff, and
  suggest `/code-review` (plus `/security-review` for E3-0, E5-1, E5-3, E9-*).
- **Pair:** propose small steps and mark which pieces are `TODO(human)`. These are the decision-bearing
  parts: orderings (C-1, I8, I9), validation, constant-time compares, state transitions, concurrency
  primitives. Wait for go. Then scaffold one step at a time and stop at each `TODO(human)` with a
  Learn-by-Doing request (context, task, guidance). Never fill a `TODO(human)` yourself.
- **Own:** don't propose an implementation or a class design. Ask the human for their plan and which
  test they will write first. Point to the spec sections and concept notes to read. Help only through
  questions and `/hint` (one level per new attempt). Remind them of `/review-mine` after green and
  `/handoff` at the end of the session.

## 4. Notes
Suggest `/notes scaffold <slug>` once the first code exists, so the "Where it shows up" rows can carry real
file:line references. While working, follow CLAUDE.md's note-nudge rule. To write a note, the human runs
`/notes write <slug>` (chat interview, their words, grammar-polished only); `/notes check <slug>` is the read-only audit.
