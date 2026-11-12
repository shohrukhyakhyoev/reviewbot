#!/usr/bin/env python3
"""commit-msg gate: a ticket-close commit ("E4-0: title") must carry a passed learning gate.

Checks the STAGED versions of PROGRESS.md and tickets/status.md:
  - PROGRESS.md has a "### [E4-0] ..." Completed entry
  - that entry has a "- Learning:" line with no unchecked "[ ]" item and "teach-back: pass"
  - tickets/status.md row for E4-0 has State "done"
/close writes all of that only after the gate passed. Other commit subjects are not checked.
"""
from __future__ import annotations

import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass


def staged(path: str) -> str:
    r = subprocess.run(["git", "show", f":{path}"], capture_output=True)
    if r.returncode == 0:
        return r.stdout.decode("utf-8", "replace")
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def fail(msg: str) -> int:
    print(f"\n✗ commit-msg gate: {msg}\n  Run /close in Claude Code (it writes the PROGRESS entry only after the "
          f"learning gate passes), or use 'wip(<ID>): ...' for an intermediate commit.\n", file=sys.stderr)
    return 1


def main(msg_file: str) -> int:
    with open(msg_file, encoding="utf-8") as f:
        subject = next((l for l in f.read().splitlines() if l.strip() and not l.startswith("#")), "")
    m = re.match(r"^(E\d+-\d+[ab]?):\s", subject)
    if not m:
        return 0
    tid = m.group(1)
    progress = staged("PROGRESS.md")
    entry = re.search(rf"^### \[{re.escape(tid)}\].*?(?=^### |^## |^---\s*$|\Z)", progress, re.M | re.S)
    if not entry:
        return fail(f"PROGRESS.md (staged) has no '### [{tid}] ...' Completed entry.")
    learning = re.search(r"^- Learning:(.*)$", entry.group(0), re.M)
    if not learning:
        return fail(f"the [{tid}] entry in PROGRESS.md has no '- Learning:' line.")
    line = learning.group(1)
    if "[ ]" in line:
        return fail(f"the [{tid}] Learning line still has unchecked items: {line.strip()}")
    if not re.search(r"teach-back:\s*pass", line, re.I):
        return fail(f"the [{tid}] Learning line does not record 'teach-back: pass'.")
    header, state = None, None
    for l in staged("tickets/status.md").splitlines():
        cells = [c.strip() for c in l.strip().strip("|").split("|")]
        if header is None and "State" in cells:
            header = cells
        elif header and cells and cells[0] == tid:
            state = dict(zip(header, cells)).get("State")
            break
    if state != "done":
        return fail(f"tickets/status.md (staged) does not mark {tid} as done (State={state}).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
