# 0006: Guard the review status machine with an enum transition table, not GoF State classes

- **Date:** 2026-11-27
- **Ticket:** E5-0
- **Status:** accepted
- **Author:** the human (draft assembled by Claude from the human's E5-0 choices and `gof-state` note
  answers; the human edits the reasoning and writes the learning point)

## Context
Every write to a `reviews` row changes its status along §5.1's state machine (PROCESSING, POSTING,
AWAITING_RETRY, SUCCEEDED, FAILED, SKIPPED). An illegal move must not happen silently: a late finalize on a
parked row would mark it SUCCEEDED, and the dedup read would then suppress that SHA for good. The guard is
shared by review-worker and retry-worker, so it lives in `shared`. I had to choose how to represent the
rules, how to handle "no row yet", how to express the one conditional exit (FAILED → PROCESSING on DLQ
replay), and what an illegal move does.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| A (chosen): plain enum + one `EnumMap<ReviewStatus, Set<ReviewStatus>>` table, a `replay` flag, a separate insert check | all rules in one place, readable as the §5.1 table; easy to test the whole from × to × replay matrix | holds rules, not behavior; the conditional exit needs a line of code outside the table |
| B: a method per enum constant (`canMoveTo`) | the rule sits next to each status | rules spread over six bodies; harder to compare with the spec table |
| C: GoF State classes, one per status | each state can carry its own behavior | six classes for what is only a set of allowed next statuses; no status in §5.1 behaves differently |

## Decision
Option A, because only the allowed moves differ and there is no per-state behavior. Inserting a new row is
a separate check (`requireInsertable`: PROCESSING, FAILED or SKIPPED). Replay is a boolean parameter, and
an illegal move throws `IllegalStateException` because it is a bug in the caller, not an outcome.

## Consequences
- Positive: `ReviewTransitions.java` is the single owner of the rules (review R2); `ReviewTransitionsTest`
  checks every move against a separately written copy of the §5.1 table.
- Negative / accepted cost: whatever a status *does* lives in `ReviewRepository` and the handlers; the claim
  uses an exhaustive `switch` over the status (`ReviewRepository.java:47`), so a new status fails compilation.
- Constraint for future tickets: change the status set only in `ReviewTransitions` together with its test.
  The `CLAIMED` set still lives in the repository (review R10, deferred to v2).

## Relation to the spec
Implements §5.1 within the freedom it leaves: the spec gives the transitions, not their representation.
The two deliberate table deviations are ADR 0005.

## Learning point
(drafted by Claude at the human's request; the human confirmed it through the check questions)
A pattern pays for itself only when the problem has its shape. GoF State is for objects whose behavior
changes with their state; this row's statuses differ only in where they may go next, which is data, so a
table is the honest model. The signal to switch is several `switch` statements over the status carrying
real logic in different classes: the behavior has started to depend on the state. Separately, a guard
only stays trustworthy if one place owns it; review R2 and R10 were both about a second, drifting copy.

## Revisit when
A status gains its own behavior, for example v2's POSTING with the fence, or a second `switch` over
`ReviewStatus` with real logic appears outside `claimExisting`.
