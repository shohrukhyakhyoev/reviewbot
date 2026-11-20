# 0002: Property-test the OffsetTracker with seeded `java.util.Random`, not jqwik

- **Date:** 2026-11-20
- **Ticket:** E4-0
- **Status:** accepted
- **Author:** the human (wording polished by Claude; the options table lists facts Claude gave and the human confirmed; reasoning unchanged)

## Context
The OffsetTracker must be property-tested over random offset sets and random completion orders
(spec §18, I3, I4), because bugs such as review finding R1 (a zombie completion after a reassignment)
hide in orders nobody writes by hand. The ticket allows either jqwik or seeded random.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| A: jqwik | Shrinks a failure to the smallest failing example; richer generators | One more library and test engine to pin and learn |
| B: seeded `java.util.Random` + JUnit `@ParameterizedTest` (500 seeds) | No extra library; a failing seed reruns exactly | No shrinking (a failure may show 300 offsets); hand-written generators |

## Decision
B: keep seeded `Random`, because it needs no extra library and a failing seed reruns exactly.

## Consequences
- Positive: no new dependency; `OffsetTrackerPropertyTest` runs as two plain parameterized tests
  (one registration, and a reassignment with late zombies).
- Negative / accepted cost: a failure is reported as a whole run, not shrunk to a minimal one.
- Constraint for future tickets: none beyond this test class.

## Relation to the spec
Implements §18 within the freedom the ticket leaves ("jqwik or seeded-random").

## Learning point
ADRs pay off most for architecture and backend choices I have to defend in an interview (for example
Kafka vs RabbitMQ); a test-tooling choice like this one is small, and recording it briefly is enough.

## Revisit when
A failing seed is too hard to debug without shrinking, or several engine classes (E4-2) need property
tests and a library starts to pay for itself.
