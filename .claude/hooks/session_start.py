#!/usr/bin/env python3
"""SessionStart hook (startup|resume|clear|compact): the handoff injector.

Puts the small, curated state a cold session needs into context: current ticket + mode,
the PROGRESS.md "Current state" block, and the ticket's worklog. It warns when the worklog
is stale (code changed after the last /handoff) or the output style doesn't match the mode.
Because it also runs on `compact`, the handoff survives auto-compaction.
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ROOT = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2])
sys.path.insert(0, str(ROOT / "scripts" / "claude"))
import ctx  # noqa: E402

STYLE_FOR_MODE = {"Own": "coach", "Pair": "Learning"}
WORKLOG_CAP = 3500


def output_style() -> str:
    style = "default"
    for name in ("settings.json", "settings.local.json"):  # local overrides project
        p = ROOT / ".claude" / name
        try:
            s = json.loads(p.read_text(encoding="utf-8")).get("outputStyle") if p.exists() else None
        except Exception:
            s = None
        if s:
            style = s
    return style


def main() -> int:
    data = json.loads(sys.stdin.read() or "{}")
    source = data.get("source", "startup")
    ctx_lines, warnings = [], []

    tid = ctx.current()
    if tid:
        row = ctx.board_row(tid) or {}
        mode = row.get("Mode", "?")
        ctx_lines.append(f"reviewbot current ticket: {tid} — {row.get('Title', '?')}; mode {mode}; "
                         f"state {row.get('State', '?')} (tickets/status.md; pointer set by the human via /ticket).")
        ctx_lines.append(f"Mode rules for {mode}: {ctx.MODE_RULES.get(mode, 'unknown mode; ask the human.')}")
        scope = row.get("Own scope", "all")
        split = mode == "Own" and scope not in ("", "all", "—")
        if split:
            ctx_lines.append(f"{tid} is a split Own ticket: {ctx.SPLIT_RULES.format(scope=scope)}")
        want = STYLE_FOR_MODE.get(mode)
        have = output_style()
        if split and have not in ("coach", "Learning"):
            warnings.append(f"{tid} is a split Own ticket; use /output-style Learning for the wiring, "
                            f"then /output-style coach for the core (current: '{have}').")
        elif not split and want and have != want:
            warnings.append(f"{tid} is {mode} mode but the output style is '{have}'. Run: /output-style {want}")
        ctx_lines.append(f"Required notes for {tid} (for the note-nudge rule in CLAUDE.md):\n{ctx.notes_text(tid)}")
        wl = ctx.read(ctx.worklog_path(tid))
        if wl:
            m = re.search(r"^fingerprint:\s*(\S+)", wl, re.M)
            now = ctx.fingerprint()
            if m and m.group(1) != now:
                warnings.append(f"Worklog for {tid} predates the current code (fingerprint {m.group(1)} → {now}); "
                                "run /handoff before ending this session.")
            body = wl if len(wl) <= WORKLOG_CAP else wl[:WORKLOG_CAP] + "\n…(truncated; full file: .claude/worklog/" + tid + ".md)"
            ctx_lines.append(f"Worklog .claude/worklog/{tid}.md (written by /handoff):\n{body.strip()}")
        else:
            ctx_lines.append(f"No worklog yet for {tid}.")
        ctx_lines.append("Full ticket + cited spec sections: `python scripts/claude/ctx.py bundle " + tid + "` "
                         "(the human's /ticket injects it).")
    else:
        ctx_lines.append("reviewbot: no current ticket is set (.claude/state/current). The human starts one with /ticket <ID>.")
        try:
            import io, contextlib
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                ctx.cmd_next()
            ctx_lines.append(buf.getvalue().strip())
        except Exception:
            pass

    cs = ctx.progress_block("Current state")
    if cs:
        ctx_lines.append("PROGRESS.md current state:\n" + cs)
    ctx_lines.append(f"Session source: {source}. Ticket loop: /ticket → work → /handoff (end of session) → "
                     "/review-mine → /notes → /close.")
    if warnings:
        ctx_lines.append("Warnings for the human: " + " ".join(warnings))

    out = {"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": "\n\n".join(ctx_lines)}}
    if warnings:
        out["systemMessage"] = "reviewbot: " + " · ".join(warnings)
    print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"session_start hook failed: {exc!r}", file=sys.stderr)
        sys.exit(1)
