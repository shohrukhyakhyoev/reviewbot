---
name: review-mine
description: Fresh-context code review of the code the human wrote on the current Own/Pair ticket, by the java-mentor subagent. Findings are recorded verbatim; the close gate stays blocked while any must-fix or should-fix is open.
argument-hint: "[git range or files, default: working tree vs HEAD]"
disable-model-invocation: true
---

Current ticket: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" current`
Fingerprint now: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" fingerprint`

```!
git status --short
git diff HEAD --stat
```

---

1. **No current ticket:** stop. **Delegate mode:** the code is Claude's, so suggest `/code-review` instead and stop.
2. **Delegate to the `java-mentor` subagent** (Agent tool, `subagent_type: "java-mentor"`). Never a fork: the
   reviewer must not see this conversation. Send only this message:
   > Ticket <ID>, mode <mode>, fingerprint <fp>. Review the human-written changes:
   > <`$ARGUMENTS` if given, else "`git diff HEAD` plus untracked files under src/">.
   > [Pair only] Claude-written scaffolding: <files/regions you scaffolded this ticket>.
   > Write your report to .review/<ID>.md.
3. **Record it verbatim:** run `python scripts/claude/ctx.py record-review <ID>`. The script copies every finding
   row byte-for-byte into the worklog, changes only Status cells for re-reviewed findings, logs the review at
   this fingerprint, and updates `docs/learning/principles.md`. You never retype, merge, soften or drop a
   finding. If the script rejects the report (malformed row), send the mentor its error message, ask it to
   rewrite the file, and record again.
4. **Show the human the report as-is:** print `.review/<ID>.md` unchanged. Don't summarize or reword
   findings. Then add the script's gate line and any concept-note candidates.
5. **What happens next:**
   - Own mode: the human fixes; `/hint R<n>` gives escalating hints for one finding; then `/review-mine` again
     re-reviews.
   - Pair mode: findings owned by `claude` are yours to fix (show the diff); findings owned by `human` are theirs.
   - `/close` stays blocked while any must-fix or should-fix is `open`. Nits are the human's choice: if they
     decline one, run `python scripts/claude/ctx.py skip-nit <ID> R<n>`. It refuses anything that isn't a nit.
