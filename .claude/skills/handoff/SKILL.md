---
name: handoff
description: End-of-session handoff. Updates the current ticket's worklog so the next session (or a compaction) resumes without re-reading everything.
argument-hint: "[hours spent this session, e.g. 2.5]"
disable-model-invocation: true
---

Current ticket: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" current`
Fingerprint now: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" fingerprint`

!`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" worklog`

---

Update `.claude/worklog/<ID>.md`. The next session's SessionStart hook injects this file verbatim,
so write it for a reader with **zero** memory of today:

1. `fingerprint:` → the value above. `updated:` → today.
2. **Sub-steps:** tick what is done; make the next step the first unchecked item, specific enough to
   start without asking ("write the gap-park test for offsets 5,7 then 6").
3. **Hint ladder:** leave the rows as `/hint` wrote them. Don't reset levels.
4. **Open questions**, **Failing tests** (names and one-line cause, only from what was actually run this
   session; otherwise "not run since <change>"), **Decisions pending** (ADR candidates).
5. **Sessions:** append `- <date> — <hours>h — <one-line summary>`. Hours come from the argument; if none
   was given, ask the human. These sum into Actual h at `/close`.
6. Keep the file under ~80 lines: fold older session lines into one summary line if needed.

Then say in one line that it's safe to `/clear` or exit. Don't commit unless asked (if asked, use a
`wip(<ID>): …` subject).
