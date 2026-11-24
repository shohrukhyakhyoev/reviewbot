# 0004: Contain a poller fault while the invariants hold; fail fast when a record would be lost

- **Date:** 2026-11-24
- **Ticket:** E4-3
- **Status:** accepted
- **Author:** the human (written down by Claude from interview answers; Claude added only code locations,
  spec links and facts the human confirmed; reasoning unchanged)

## Context
The poller is the only thread that touches the `KafkaConsumer` (I1), so every exception that reaches it
needs one answer: keep polling, or stop. Some failures are normal (a commit fails during a rebalance);
others leave a record tracked but never completed. The rule I chose: contain the fault if the invariants
still hold; fail fast if continuing would lose a record or leave a partition stuck.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| A: one catch-all around the poll loop, log and keep polling (the poller never dies) | the worker never restarts | a record that is tracked but never completed (for example `Dispatcher.route` rejected by a closed executor) holds the watermark, so the partition never commits again, even when the next jobs succeed. Nothing crashes, so it fails silently and only lag grows |
| B: fail fast on every exception | simple; nothing is ever hidden | a failed `commitAsync` is normal during a rebalance, so every rebalance would relaunch the worker |
| C: scoped: contain when the invariants hold, fail fast otherwise | normal faults cost nothing; faults that would lose a record end in a restart that Kafka recovers from | each throw site has to be classified and tested |

## Decision
C. Contained: a failed commit (`Poller.java:153`, retried by the next commit pass), a throwing outcome
handler or `markComplete` (`RecordCompleter.java:27`, `:39`, one record's scope), and a record whose key is
not a PR key (`Dispatcher.java:59`, completed as poison). Everything that escapes the loop fails fast:
`pollerDied` and `onDeath` (`Poller.java:68-70`) exit the worker with code 1 (ADR 0003).

## Consequences
- Positive: no silent freeze; a partition is either moving or the worker restarts.
- Negative / accepted cost: on fail-fast, in-flight work is lost and redelivered from the last commit.
  The redelivered records are redone, not reviewed twice, because of the dedup marker (I8).
- Constraint for future tickets (E4-4 rebalance, E5 real handler): classify each new throw site as
  contain or fail fast against the invariants, and pin it with a test.

## Relation to the spec
Implements I3, I4 and I5 within the freedom the spec leaves; no spec section prescribes the poller's
failure handling.

## Learning point
Containing a fault doesn't mean the thread dies: we can catch `Throwable` or `Exception`, log it, and let
the loop proceed. The rule is not "the poller never dies" but "contain only if the state is still safe".

## Revisit when
`pollerDied` events spike (today a log line from `LoggingEngineMetrics`; not yet a §15 metric).
