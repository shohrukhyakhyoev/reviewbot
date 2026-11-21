# Principles tally (from /review-mine)

Written by `python scripts/claude/ctx.py record-review` from each java-mentor report; read by the mentor
before every review. A principle cited in reviews of **≥ 2 different tickets** becomes a concept-note
candidate: append a "Where it shows up" line to its concept note (registry slug), or propose a new registry
entry in `docs/learning/ticket-notes.md`.

## Tally

| Principle | Registry slug | Tickets | Count | Concept-note candidate |
|---|---|---|---|---|
| §3.3 rule 1 never retype (contract enforcement) | — | E1-0 | 1 | — |
| Precise exception types | — | E1-0 | 1 | — |
| Encapsulation (§19.13 codecs in one place) | — | E1-0 | 1 | — |
| Fail fast (codec symmetry) | — | E1-0 | 1 | — |
| Exhaustive switch over a closed set (Java 21) | — | E1-0, E1-2 | 2 | yes: propose a registry entry |
| Types that tell the truth (nullability) | — | E1-0 | 1 | — |
| Actionable failure messages | — | E1-0, E1-1, E1-2, E4-2 | 4 | yes: propose a registry entry |
| Comments match the code | — | E1-0, E1-1, E1-2, E3-1, E3-2, E4-2 | 6 | yes: propose a registry entry |
| Fail fast on malformed input | — | E1-0, E1-1, E1-4, E4-0 | 4 | yes: propose a registry entry |
| Canonical form (parse inverts render exactly) | — | E1-1 | 1 | — |
| DRY (one place for the key format) | — | E1-1 | 1 | — |
| I5 exactly one outcome (RetryDecision covers every failure) | — | E1-2 | 1 | — |
| Nothing lost on failure paths (§10.2 self-describing DLQ envelope) | — | E1-2 | 1 | — |
| S-4 data hygiene (never log customer content) | — | E1-2 | 1 | — |
| Spec is the closed vocabulary (§10.1 classes, §15 alerts by class) | observability | E1-2 | 1 | — |
| Types that tell the truth (closed set, not strings) | — | E1-2, E3-1 | 2 | yes: propose a registry entry |
| Optional never as a field or parameter | — | E1-2 | 1 | — |
| §7.1 breaker open ⇒ BREAKER_PARKED with frozen counters (§11.2) | circuit-breaker | E1-2 | 1 | — |
| Names that say intent | — | E1-2, E1-4, E3-0, E3-3 | 4 | yes: propose a registry entry |
| DRY | — | E1-2, E4-2 | 2 | yes: propose a registry entry |
| §10.1 route by kind first (I5 catch-all classifies correctly) | circuit-breaker | E1-2 | 1 | — |
| YAGNI | — | E1-2, E4-2 | 2 | yes: propose a registry entry |
| Acceptance criteria met as written | — | E1-4, E4-2 | 2 | yes: propose a registry entry |
| Comments for the non-obvious why (CLAUDE.md rule 6) | — | E1-4, E3-3, E4-0 | 3 | yes: propose a registry entry |
| Precise exception types (one contract per package) | — | E1-4 | 1 | — |
| One behaviour per test, in the test class named for it | — | E1-4 | 1 | — |
| Bound untrusted input at the trust boundary | — | E3-0 | 1 | — |
| Encapsulation (least visibility) | — | E3-0 | 1 | — |
| A test must fail when the property breaks | — | E3-0, E3-2, E4-0, E4-1, E4-2 | 5 | yes: propose a registry entry |
| ISP (depend only on what you use) and acyclic package dependencies | solid | E3-0 | 1 | — |
| Consistency (one vocabulary per method) | — | E3-0 | 1 | — |
| Design for extension or prohibit it | — | E3-0 | 1 | — |
| Test isolation (fixtures must not collide with production wiring) | — | E3-0 | 1 | — |
| A test must fail when the property breaks (AC action matrix exact) | — | E3-1 | 1 | — |
| Types that tell the truth (a `field` parameter carries a field name) | observability | E3-1 | 1 | — |
| Consistency (one typing rule per reader) | — | E3-1 | 1 | — |
| Explicit contracts (no hidden coupling between reader and validator) | — | E3-1 | 1 | — |
| Comments match the code (no unverified claims) | — | E3-1, E4-2 | 2 | yes: propose a registry entry |
| SRP (counting vs diagnostic logging in one port) | solid | E3-1 | 1 | — |
| §6.1 4d every produce failure is a 503 (closed outcome set) | rest-principles | E3-3 | 1 | — |
| Nothing lost on failure paths | observability | E3-3, E4-2 | 2 | yes: append to `observability` |
| Injectable clock (no hidden time source) | — | E3-3 | 1 | — |
| Fail fast on malformed input (one definition of valid at the trust boundary) | — | E3-3 | 1 | — |
| I7 / O5 zombie completions are never committed and never contaminate a re-registration | kafka | E4-0 | 1 | — |
| I3 contiguous watermark | — | E4-0 | 1 | — |
| Spec wins (§8.4 O5 dirty tracking since last commit) | — | E4-0 | 1 | — |
| DRY (one classification door, C-2) | — | E4-1 | 1 | — |
| Narrow try scope | — | E4-1 | 1 | — |
| I4 permit conservation | concurrency-semaphore | E4-2 | 1 | — |
| I5 nothing falls through (outermost catch-all) | — | E4-2 | 1 | — |
| Tests without sleeps or real time | — | E4-2 | 1 | — |
| A test must fail when the property breaks (I10) | concurrency-chm-compute | E4-2 | 1 | — |
| No swallowed exceptions | — | E4-2 | 1 | — |
| I9 produce-then-complete | — | E4-2 | 1 | — |
| Interrupt policy (who owns cancellation) | jvm-virtual-threads | E4-2 | 1 | — |
