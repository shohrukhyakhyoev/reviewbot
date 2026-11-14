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
| Actionable failure messages | — | E1-0, E1-1, E1-2 | 3 | yes: propose a registry entry |
| Comments match the code | — | E1-0, E1-1, E1-2 | 3 | yes: propose a registry entry |
| Fail fast on malformed input | — | E1-0, E1-1 | 2 | yes: propose a registry entry |
| Canonical form (parse inverts render exactly) | — | E1-1 | 1 | — |
| DRY (one place for the key format) | — | E1-1 | 1 | — |
| I5 exactly one outcome (RetryDecision covers every failure) | — | E1-2 | 1 | — |
| Nothing lost on failure paths (§10.2 self-describing DLQ envelope) | — | E1-2 | 1 | — |
| S-4 data hygiene (never log customer content) | — | E1-2 | 1 | — |
| Spec is the closed vocabulary (§10.1 classes, §15 alerts by class) | observability | E1-2 | 1 | — |
| Types that tell the truth (closed set, not strings) | — | E1-2 | 1 | — |
| Optional never as a field or parameter | — | E1-2 | 1 | — |
| §7.1 breaker open ⇒ BREAKER_PARKED with frozen counters (§11.2) | circuit-breaker | E1-2 | 1 | — |
| Names that say intent | — | E1-2 | 1 | — |
| DRY | — | E1-2 | 1 | — |
| §10.1 route by kind first (I5 catch-all classifies correctly) | circuit-breaker | E1-2 | 1 | — |
| YAGNI | — | E1-2 | 1 | — |
