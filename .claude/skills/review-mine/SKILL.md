---
name: review-mine
description: Mentor review of the code the human wrote for the current ticket (Pair/Own), run by the read-only java-mentor subagent.
argument-hint: "[git range, default: working tree vs HEAD]"
disable-model-invocation: true
---

Current ticket: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" current`
Fingerprint now: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" fingerprint`

```!
git status --short
git diff HEAD --stat
```

---

1. No current ticket → stop. Mode Delegate → this skill is for code the human wrote. Suggest
   `/code-review` for Claude-written code instead and stop.
2. Delegate to the **java-mentor** subagent (Agent tool, `subagent_type: "java-mentor"`) with only this
   message. Keep it short; it loads its own context:
   > Ticket <ID>, mode <mode>, fingerprint <fp>. Review the human-written changes:
   > <`$ARGUMENTS` if given, else "`git diff HEAD` plus untracked files under src/">.
3. Relay its report **faithfully**: same severities, same questions. Don't soften findings, don't add
   fixes, and in Own mode don't add code.
4. Append to the worklog Reviews table: `| <date> | <fingerprint> | <e.g. 0 BLOCKER, 2 MAJOR, 3 MINOR> |`.
   `/close` requires a review recorded at the final fingerprint for Pair/Own, so re-run after fixes.
