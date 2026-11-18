# 0001: Bound the gateway produce with one shared 5 s deadline

- **Date:** 2026-11-18
- **Ticket:** E3-3
- **Status:** accepted
- **Author:** the human (wording polished by Claude; reasoning unchanged)

## Context
GitHub expects an answer within 10 s. When Kafka is down, `send()` can block for up to 60 s
(`max.block.ms` default) before the ack wait even starts. Capping `send()` at 5 s and keeping
`get(5 s)` would still allow 10 s in the worst case. Long waits also make request threads pile up,
until the gateway stops responding.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| A: `max.block.ms` = 5 s, plus `get(5 s)` | Simple | Worst case is 10 s |
| B: one shared 5 s deadline for `send()` and `get` | Exactly 5 s in total | Needs deadline arithmetic |
| C: `send` + `get` on another thread, one overall timeout | One overall timeout | Needs an extra thread per request |

## Decision
B: one shared 5 s deadline for `send()` and the ack wait. It is the only option that keeps the
total at exactly 5 s without an extra thread.

## Consequences
- Positive: a hard 5 s bound on the whole produce.
- Negative / accepted cost: the deadline arithmetic is extra code. A late success after a `503`
  produces a duplicate; that applies to every bounded option, and the spec accepts it (§12): the
  worker's dedup absorbs it.
- Constraint for future tickets: `max.block.ms` must not exceed `produceDeadline`. Both come from
  `ReviewbotProperties.timeouts().gatewayProduce()`, never a second literal. The deadline is taken
  before `send()`; taken after it, the block time is not counted and the worst case returns to
  10 s. E6's retry/DLQ producers follow the same rule.

Code: `WebhookEnqueuer.producedInTime`, `ProducerSettings.forGateway` (`max.block.ms`), tests
`WebhookEnqueuerTest.timeBlockedInSendCountsAgainstTheSameDeadline` and
`aSendThatUsedTheWholeBudgetLeavesNothingToWaitFor`, `WebhookEndToEndTest` (Kafka paused → 503 in about 5 s).

## Relation to the spec
Implements §6.1 step 4b and §12 (gateway produce-await 5 s → 503, P4-1) within the freedom they
leave. The spec fixes the 5 s bound but not how to enforce it.

## Learning point
A requirement like "answer within 5 s" constrains the whole operation, not each step. So first
find every place the code waits on I/O, especially in the failure cases (Kafka down: `send()`
blocks on metadata before the ack wait even starts). A timeout restarts for each wait, so
sequential timeouts add up; a deadline is one fixed end time that every wait shares. When several
waits must fit inside one promise, use a deadline.

## Revisit when
- Webhook latency approaches 10 s: the bound is leaking somewhere.
- `503`s rise while Kafka is healthy, just slow: the 5 s value itself is too tight.
