---
name: hint
description: Own-mode hint ladder. Raises one sub-problem by exactly one level, and only after a new attempt. Never gives code or the answer.
argument-hint: "[sub-problem, e.g. 'island absorption']"
disable-model-invocation: true
---

!`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" hint-state $ARGUMENTS`

---

## The ladder (per sub-problem; the level persists in the worklog)
| Level | You may give |
|---|---|
| L0 | Restate the invariant or acceptance criterion the sub-problem serves, and ask what they tried and what they observed. |
| L1 | Point to the spec § (quote ≤ 2 lines) and the concept note to re-read. |
| L2 | Name the JDK/library API or technique and its contract, e.g. "`NavigableSet.remove(x)` returns whether x was present". No usage snippet. |
| L3 | The algorithm's shape in plain prose for this one sub-step, **or** a concrete counterexample input (offsets, interleaving) that breaks their current code. |
| L4 | Point at the exact file:line or branch in **their** code and name the bug class ("off-by-one at the commit site", "check-then-act race"). Never the corrected code. |

## Procedure
1. Identify the sub-problem: use the argument, or infer it from the last exchange and confirm in one line.
2. Find its row in the hint table above. No row means level L0.
3. **Attempt check:** compare "Fingerprint now" with the row's "Fingerprint at last hint".
   - Same fingerprint, and a row exists: **don't escalate.** Rephrase the current level from a
     different angle and ask for a concrete attempt (code change or failing test). Say why.
   - Different, or a new row: escalate to the next level, max L4.
4. Give exactly one hint at that level, then ask one question that makes them act.
5. Update the row in `.claude/worklog/<ID>.md`: `| sub-problem | Lk | <fingerprint now> | <date> |`.

## Hard rules
- No code blocks. No Java or pseudo-Java. No identifier names they could paste as a solution. You may
  quote ≤ 3 lines of **their own** code to point at it.
- "Just tell me the answer" → decline in one sentence. The options are the next `/hint` after an
  attempt, or the human switching this ticket to Pair in `tickets/status.md` (their decision; it gets logged).
- Stuck at L4 for more than 45 minutes: say so, and suggest a break, re-reading the cited § or the Pair switch.
  Never break the ladder.
- If the current mode is not Own, say so. Hints are still fine, but the ladder isn't required.
