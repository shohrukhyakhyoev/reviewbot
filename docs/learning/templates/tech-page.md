---
slug: <slug from docs/learning/ticket-notes.md tech registry>
title: <Technology>
version: <as pinned in the parent pom.xml / compose file, e.g. kafka-clients 3.8.1>
status: draft            # draft → lite → understood
notion: pending          # inbox fallback only: pending | synced <url>
last-reviewed: YYYY-MM-DD
---

<!-- One page per technology. Product-specific: config knobs, APIs, operational gotchas. The general
     idea (e.g. "caching strategies", "circuit breaker pattern") lives in a concept note: link it.
     Later tickets that use a NEW feature of this technology append a section under "How reviewbot uses it".
     TWO PARTS: the human writes "What problem it solves", "Mental model", the "Why" cells and the interview
     answers (Claude fixes grammar only, and adds a "Correction (Claude)" under a wrong answer). Claude writes the
     sections headed "(Claude)" after the interview: facts only, from the spec, the code (real file:line) and
     official docs, short, one concrete reviewbot example each, "unverified" if unchecked.
     "lite" = every section filled, none left as a prompt. -->

## What problem it solves here (lite)
<Why reviewbot needs it, in 2–3 sentences. What would break or get harder without it?>

## Mental model (lite)
<The 4–6 core concepts you must hold in your head (e.g. Kafka: partition, offset, consumer group,
 rebalance, ISR). One line each, in your own words.>

## How reviewbot uses it (lite: at least the first ticket's section)
### <Ticket ID>: <feature used>
| Setting / API | Value | Why (spec §) |
|---|---|---|
| `acks` | `all` | survives a broker loss, §4.2 |

## APIs and classes we touch (Claude)
<Class / method → what we rely on in its contract (e.g. `KafkaConsumer` is not thread-safe → I1).>

## Local vs production gotchas (Claude)
<e.g. local RF=1/ISR=1 vs prod RF=3/ISR=2 (§4.4); Windows path quirks; image tags.>

## How we test it (Claude)
<Testcontainers module, WireMock stubs, what an integration test asserts.>

## Linked concept notes (Claude)
- <concept slug> (why it relates)

## Interview questions I can now answer (lite: at least one)
1. <question>: <answer>
