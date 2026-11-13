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
| Exhaustive switch over a closed set (Java 21) | — | E1-0 | 1 | — |
| Types that tell the truth (nullability) | — | E1-0 | 1 | — |
| Actionable failure messages | — | E1-0, E1-1 | 2 | yes: propose a registry entry |
| Comments match the code | — | E1-0, E1-1 | 2 | yes: propose a registry entry |
| Fail fast on malformed input | — | E1-0, E1-1 | 2 | yes: propose a registry entry |
| Canonical form (parse inverts render exactly) | — | E1-1 | 1 | — |
| DRY (one place for the key format) | — | E1-1 | 1 | — |
