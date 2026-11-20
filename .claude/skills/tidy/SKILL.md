---
name: tidy
description: Behavior-preserving clean-code pass over the current ticket's new code by the fresh-context code-tidier subagent (Opus). Delegate and Pair tickets only; run it before /review-mine.
argument-hint: "[files, default: changed + untracked files under src/]"
disable-model-invocation: true
---

Current ticket: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" current`

```!
git status --short
git diff HEAD --stat
```

---

1. **No current ticket:** stop. **Own mode** (or an Own-scope package of a split ticket): stop. The human
   writes that code, and the guard blocks every edit there. Suggest `/review-mine` instead.
2. **Pick the files:** `$ARGUMENTS` if given, else every changed or untracked `.java` file under `src/`
   from the status above.
3. **Delegate to the `code-tidier` subagent** (Agent tool, `subagent_type: "code-tidier"`). Never a fork: it
   must judge the code without this conversation. Send only:
   > Ticket <ID>, mode <mode>. Tidy these files: <list>.
   > [Pair only] Human-written regions (keep their design, tidy only): <regions>. Never touch a `TODO(human)`.
4. **Show its report unchanged.** Then add one `★ Insight` block that picks the single most useful principle
   from it and ties it to this ticket.
5. **Book-keeping:** add one line to the worklog's "Open questions": `/tidy <date>: <n> changes (<files>)`.
   The fingerprint changed, so `/review-mine` must run (again) before `/close`.
