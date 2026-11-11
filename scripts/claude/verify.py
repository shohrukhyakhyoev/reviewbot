#!/usr/bin/env python3
"""verify.py: run the Maven build and print only what matters (keeps context small).

  python scripts/claude/verify.py                     # ./mvnw -B -ntp clean verify + coverage gate (80%)
  python scripts/claude/verify.py -pl review-worker   # extra Maven args appended to `clean verify`
  python scripts/claude/verify.py test -pl shared     # your own goals (no coverage gate)
  python scripts/claude/verify.py --no-gate           # skip the overall coverage gate step

Prints: reactor summary, test totals, failing tests, [ERROR] lines (capped), coverage-rule violations,
format violations, BUILD result. Full log: target/verify.log. Exit code = Maven's (or the gate's).
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ROOT = Path(__file__).resolve().parents[2]
LOG = ROOT / "target" / "verify.log"
MAX_ERRORS = 60


def maven_cmd(args: list[str]) -> list[str]:
    goals = args if any(not a.startswith("-") for a in args) else ["clean", "verify", *args]
    base = [str(ROOT / "mvnw.cmd")] if os.name == "nt" else ["./mvnw"]
    return [*base, "-B", "-ntp", *goals]


def bash() -> str | None:
    for cand in (r"C:\Program Files\Git\bin\bash.exe", shutil.which("bash")):
        if cand and Path(cand).exists() and "System32" not in cand:  # System32\bash.exe = WSL launcher
            return cand
    return None


def summarize(lines: list[str]) -> list[str]:
    out, errors, in_reactor = [], 0, False
    totals, modules = [0, 0, 0, 0], 0
    for line in lines:
        s = line.rstrip()
        if "Reactor Summary" in s:
            in_reactor = True
            out.append(f"Tests total: run {totals[0]}, failures {totals[1]}, errors {totals[2]}, "
                       f"skipped {totals[3]} (summed over {modules} module test phases)")
        if in_reactor:
            out.append(s)
            if re.search(r"BUILD (SUCCESS|FAILURE)", s):
                in_reactor = False
            continue
        m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)$", s)
        if m:  # per-module summary line (per-class lines carry "Time elapsed")
            totals = [a + int(b) for a, b in zip(totals, m.groups())]
            modules += 1
        elif "<<< FAIL" in s or "<<< ERROR" in s:
            out.append(s)
        elif re.search(r"Rule violated|coverage checks have not been met|format violations|"
                       r"Failed to execute goal|COMPILATION ERROR|BUILD (SUCCESS|FAILURE)|Total time", s, re.I):
            out.append(s)
        elif "[ERROR]" in s and errors < MAX_ERRORS and s.strip() != "[ERROR]":
            out.append(s)
            errors += 1
    # de-duplicate while preserving order
    seen, uniq = set(), []
    for s in out:
        if s not in seen:
            seen.add(s)
            uniq.append(s)
    if errors >= MAX_ERRORS:
        uniq.append(f"... [ERROR] lines capped at {MAX_ERRORS}; see {LOG.relative_to(ROOT)}")
    return uniq


def main(argv: list[str]) -> int:
    no_gate = "--no-gate" in argv
    args = [a for a in argv if a != "--no-gate"]
    cmd = maven_cmd(args)
    custom_goals = any(not a.startswith("-") for a in args)
    print("$ " + " ".join(cmd))
    LOG.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    text = proc.stdout + proc.stderr
    LOG.write_text(text, encoding="utf-8")
    print("\n".join(summarize(text.splitlines())))
    print(f"(full log: {LOG.relative_to(ROOT)}, {len(text.splitlines())} lines)")
    if proc.returncode != 0 or no_gate or custom_goals:
        return proc.returncode
    b = bash()
    gate = ROOT / ".github" / "scripts" / "coverage-gate.sh"
    if not b or not gate.exists():
        print("coverage gate: SKIPPED (no Git Bash found); CI still enforces it")
        return 0
    g = subprocess.run([b, str(gate), "80"], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    print("coverage gate (overall line ≥ 80%):")
    print((g.stdout + g.stderr).strip()[-1500:])
    return g.returncode


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
