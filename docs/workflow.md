# How I run a ticket (human runbook)

This file is for the human. Claude's rules are in CLAUDE.md, and the reasoning behind this setup is in
`docs/claude-setup-plan.md`.

## One-time setup
- `git config core.hooksPath .githooks`. This turns on the close-commit gate.
- Optional: the official `jdtls-lsp` plugin (`/plugin`). It needs a local Eclipse JDT LS install. Try it on
  one Pair ticket and drop it if it's flaky on Windows.

## Starting a ticket
1. `/clear`. Start each ticket with a fresh context.
2. Check the board: `python scripts/claude/ctx.py next` (or open `tickets/status.md`).
3. Set the output style for the ticket's mode: `/output-style coach` (Own) · `/output-style Learning` (Pair)
   · `/output-style default` (Delegate).
4. `/ticket E4-0`. Claude gets the ticket, every spec section it cites, the key decisions and required
   notes. It sets the ticket as current and creates `.claude/worklog/E4-0.md`.

**Model:** Opus for Own tickets, Sonnet for Delegate and Pair (`/model`). The bundle names the right one, and
`/review-mine` always runs on Opus.

**Notes flow:**
- At start, `/ticket` lists the notes this ticket needs.
- While you work, Claude adds a one-line `note-worthy: … → concept note <slug>` or `→ ADR candidate` when it
  fits. It's a nudge, not a stop.
- After you write a note, `/notes check <slug>` reads it (Notion, inbox, or paste it in). It checks it against
  the template, the spec and your code, and replies with questions only. It never writes the note.
- At close, the `/close` gate checks status and the ticket link.

## During the ticket, by mode
| Mode | You | Claude |
|---|---|---|
| Delegate | approve the plan, read the whole diff, run `/code-review` (and `/security-review` on E3-0, E5-1, E5-3, E9-*) | implements, runs `verify.py` |
| Pair | write every `TODO(human)`, ask "why" freely | scaffolds in small steps, then stops at each `TODO(human)` |
| Own | write all production code and tests, tests first | asks questions, may list test cases (never test code); `/hint [sub-problem]` raises one level per new attempt (L0 → L4, never the answer) |
| Own, split (E5-7, E5-9a) | write the packages in the board's Own scope column, plus their tests | does the wiring outside them first (`/output-style Learning`); then you switch to `/output-style coach` for the core |

- **Hint ladder:** L0 asks what you tried, L1 points to § and notes, L2 names the API/technique, L3 gives the
  shape in prose or a counterexample, L4 gives file:line + the bug class. No escalation without a new attempt.
- **Stuck at L4 for more than 45 minutes:** take a break, re-read the §, or switch the ticket to Pair yourself
  (edit the Mode cell in `tickets/status.md`). It's your call, and it shows up in the diff.
- **Side questions:** `/btw <question>`. It doesn't pollute the context.
- **Wrong direction:** Esc, then `/rewind`.
- **Long session:** `/compact focus on <sub-step>`. The SessionStart hook re-injects the ticket and worklog.
- **Context feels heavy:** `/context`.

## Ending a session (every day)
`/handoff 2.5` (the number is the hours you spent). It updates the worklog so tomorrow's session picks up
without re-reading anything. A `wip(E4-0): …` commit is fine.

## Closing a ticket
1. `/review-mine` (Pair/Own). A fresh-context reviewer (java-mentor) checks correctness (invariants,
   acceptance criteria), then clean code, design (incl. over-engineering), then testability. Each finding has an ID,
   severity (must-fix / should-fix / nit), file:line, the principle, and why it matters. There are no hints in the
   review: `/hint R2` if you want one. Fix, then `/review-mine` again re-reviews. **`/close` and the commit hook stay
   blocked while any must-fix or should-fix is open.** Declining a nit: `python scripts/claude/ctx.py skip-nit E4-0 R5`.
   Principles that keep coming up are tallied in `docs/learning/principles.md`; from the second ticket on they're
   flagged as concept-note candidates.
2. `/notes`. It shows which concept notes, tech pages and ADR this ticket needs (Notion, or
   `docs/learning/inbox/` when Notion is down), and scaffolds missing ones. **You write the
   explanations.** December minimum = status `lite`.
3. `/close`. This is the gate: build → acceptance criteria → review → notes → ADR → teach-back (Own 5 /
   Pair 3 / Delegate 1). A fail keeps the ticket `wip`. A pass writes PROGRESS.md and the board.
4. Commit `E4-0: <title>`. The commit-msg hook refuses it if the Learning line or the board says otherwise.
   `--no-verify` exists, and using it is your decision.

## Calibrating the plan
`tickets/status.md` records Est h vs Actual h (summed from `/handoff` session lines). After E4-0: if Own
tickets run more than 1.3× over, re-estimate the remaining Own tickets. Planning only: no fixed date, and Own
tickets are never downgraded to save time.

## When things break
- **Notion unavailable:** notes go to `docs/learning/inbox/<slug>.md` with `notion: pending`. Run
  `/notes sync` later.
- **A hook misbehaves:** run `python scripts/claude/selftest.py`. `/hooks` shows what's registered.
- **Guard false positive** (an `ask` prompt): read the reason, then approve or deny. A `deny` in Own mode is
  intended.
