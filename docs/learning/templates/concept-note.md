---
slug: <slug from docs/learning/ticket-notes.md concept registry>
title: <Area › Concept>
roadmap-area: <e.g. Java › Concurrency & Multithreading>
status: draft            # draft → lite (December minimum) → understood (full template)
notion: pending          # inbox fallback only: pending | synced <url>
last-reviewed: YYYY-MM-DD
---

<!-- One page per roadmap concept. Technology-independent: if the note stops being true when you swap
     the technology, it belongs on a tech page instead. YOU write every section; Claude may only pre-fill
     "Where it shows up". "lite" = sections marked (lite) filled; "understood" = all sections. -->

## In my own words (lite)
<3–6 sentences, no copy-paste. What problem does this concept solve? Explain it to a junior teammate.>

## Mechanism
<How it actually works: the moving parts, the guarantee it gives, the cost it has. A small diagram is fine.>

## Where it shows up in reviewbot (lite)
| Ticket | Code (file:line) | Invariant / spec § | What it does there |
|---|---|---|---|
| E4-0 | `review-worker/.../OffsetTracker.java:42` | I4, §8.4 | releases the permit on every completion |

## What breaks if it's misused
<Link the trap or invariant: e.g. "synchronized around blocking I/O pins the carrier (CLAUDE.md trap, §19.1)".
 Describe the failure as a concrete scenario.>

## Contrast and alternatives
<What else could solve this? Why does reviewbot use this one? If the spec Appendix decided it, link "Appendix #n"
 here instead of writing an ADR.>

## Interview questions I can now answer (lite: at least one)
1. <question>: <my answer in 2–3 sentences>
2.
3.

## Sources
- spec §…
- <official docs / book chapter>
