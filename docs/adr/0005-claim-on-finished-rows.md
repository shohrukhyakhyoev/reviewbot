# 0005: A finished row is not always final: SKIPPED reopens or fails, FAILED absorbs redelivery

- **Date:** 2026-11-27
- **Ticket:** E5-0
- **Status:** accepted
- **Author:** the human (draft assembled by Claude from the human's decisions recorded in the E5-0 worklog;
  the human edits the reasoning and writes the learning point)

## Context
§5.1 says the terminal statuses (SUCCEEDED, FAILED, SKIPPED) are final, except that a DLQ replay may move
FAILED back to PROCESSING. Two other spec rules need a finished row to move again. §7.1 step 3: a PR that
was closed and is now reopened must be reviewed, but its key `(i, r, p, sha, round)` already has a
SKIPPED(PR_CLOSED) row. §7.2 C-6: an unclaimed TERMINAL (for example, a missing API key) must leave the row
FAILED(class), matching the DLQ entry it produced, even when the row is SKIPPED. A third case is redelivery:
Kafka redelivers a record after a crash before the commit (I9), so the claim and the unclaimed writes meet
a row they already finished. Following §5.1 literally makes all three throw.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| A: follow §5.1 literally: any move out of a finished row throws | one rule, matches the table | a reopened PR is never reviewed; a redelivered event throws → UNEXPECTED ×5 → a second DLQ entry and a page for something harmless |
| B: throw only on FAILED, allow SKIPPED → PROCESSING | the reopened PR works | a redelivered FAILED event still throws `IllegalStateException` in the claim; the drainer's catch-all classifies it UNEXPECTED, it retries ×5, then lands in the DLQ a second time and pages someone for a harmless duplicate |
| C (chosen): SKIPPED → PROCESSING and SKIPPED → FAILED are legal; FAILED without replay → `AlreadyFailed` → SKIP; an unclaimed verdict over a row of the same status refreshes only the reason; otherwise the earlier verdict stands | reopened PRs are reviewed; the row always matches its DLQ entry (C-6); redelivery is absorbed with no alarm | two deliberate deviations from the §5.1 table, which must be documented and tested |
| D: for C-6, keep SKIPPED and log a WARN (no-op) | SKIPPED stays claimable | the row says PR_CLOSED while the event sits in the DLQ: the row and the DLQ disagree |

## Decision
Option C. "A skip isn't done": the gates run again before the claim, so only an event whose skip reason is
gone reaches it, and it starts fresh (counters reset like a new claim). "A failed duplicate shouldn't alarm":
the DLQ record already exists, so a redelivered FAILED event is skipped. For C-6 the spec sentence wins over
the §5.1 table, so SKIPPED → FAILED is legal, but only the unclaimed path can reach it: the handler writes
(`parkForRetry`, `failClaimed`, `finalizeSucceeded`) first require a claimed row (PROCESSING or POSTING) in
`transitionClaimed`, and only then ask the table.

## Consequences
- Positive: no throw on any normal redelivery; a reopened PR reaches the claim; the `reviews` row and the DLQ
  agree.
- Negative / accepted cost: `ReviewTransitions.LEGAL` differs from the §5.1 table in two rows
  (`ReviewTransitions.java:14-38`, pinned in `ReviewTransitionsTest`). SKIPPED → FAILED must not be reachable
  from a claimed write, so the handler writes go through `transitionClaimed` (`ReviewRepository.java`, R9).
- Constraint for future tickets: the pipeline (E5-9b) maps `AlreadyFailed` to SKIP and never writes over a
  SUCCEEDED row. A change to the status set updates `ReviewTransitions` and its spec-table test together.

## Relation to the spec
Deviates from §5.1 on purpose, in two rows, to satisfy §7.1 step 3 and §7.2 C-6, which the literal table
contradicts. This is a spec self-conflict, not a free choice: it is a spec-update candidate (PROGRESS
"Deferred / noticed"), so §5.1 should list SKIPPED → PROCESSING and SKIPPED → FAILED.

## Learning point
(drafted by Claude at the human's request; the human confirmed it through the check questions)
"Terminal" describes one event's journey, not the row's life. The row is keyed by the commit, and the same
key can be reached again by a redelivered copy of the event (at-least-once delivery) or by a new event for
the same commit (a reopened PR). So the row needs rules for "meeting itself again": absorb a duplicate
quietly, reopen a skip whose reason is gone, and never overwrite a success. When two records describe the
same event, the row and the DLQ entry, the one an operator acts on (the DLQ, which a replay starts from)
decides what the other must say. A state table copied literally from the spec is only as right as the
spec; testing it against the other spec rules is what found the two conflicts.

## Revisit when
The spec is updated for §5.1, or v2 activates POSTING and the fence (the claimed-path guard and the
POSTING → PROCESSING takeover change).
