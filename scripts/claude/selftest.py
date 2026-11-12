#!/usr/bin/env python3
"""Self-test for the Claude Code setup: guard hook decisions, SessionStart output, ctx.py resolution.

Run after changing anything in .claude/hooks/ or scripts/claude/:  python scripts/claude/selftest.py
Restores .claude/state/current afterwards. Exit 1 on any failure.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "claude"))
import ctx  # noqa: E402

GUARD = ROOT / ".claude" / "hooks" / "pretool_guard.py"
SESSION = ROOT / ".claude" / "hooks" / "session_start.py"
ENV = {**os.environ, "CLAUDE_PROJECT_DIR": str(ROOT)}
W = str(ROOT)  # absolute paths, Windows-style when on Windows
fails = 0


def p(*parts: str) -> str:
    return str(Path(W, *parts))


def guard(tool: str, tool_input: dict, agent: str | None = None) -> str:
    payload = {"tool_name": tool, "tool_input": tool_input}
    if agent:
        payload["agent_type"] = agent
    r = subprocess.run([sys.executable, str(GUARD)], input=json.dumps(payload), capture_output=True,
                       text=True, encoding="utf-8", env=ENV)
    if r.returncode != 0:
        return f"crash:{r.stderr.strip()[:120]}"
    if not r.stdout.strip():
        return "allow"
    return json.loads(r.stdout)["hookSpecificOutput"]["permissionDecision"]


def expect(name: str, got: str, want: str) -> None:
    global fails
    ok = got == want
    fails += not ok
    print(f"{'PASS' if ok else 'FAIL'}  {name:46} want={want:5} got={got}")


REVIEW_1 = """# Review E97-1 · 2026-11-12 · fingerprint aaaa11112222 · mode Own

## Verdict
1 must-fix, 1 should-fix, 1 nit

## Status updates
| ID | Status | Evidence |
|---|---|---|

## New findings
| ID | Severity | Stage | Location | Principle | Why it matters here | Owner | Status |
|---|---|---|---|---|---|---|---|
| R1 | must-fix | correctness | x/src/main/java/A.java:10 | I3 contiguous watermark | commits past a gap | human | open |
| R2 | should-fix | design | x/src/main/java/A.java:20 | YAGNI | factory with one product | human | open |
| R3 | nit | clean-code | x/src/main/java/A.java:30 | Naming | `tmp` says nothing | human | open |

## Principles cited
| Principle | Registry slug |
|---|---|
| YAGNI | — |
"""
REVIEW_2 = """# Review E97-1 · 2026-11-12 · fingerprint bbbb33334444 · mode Own

## Verdict
0 must-fix, 0 should-fix open; 1 nit open

## Status updates
| ID | Status | Evidence |
|---|---|---|
| R1 | fixed | A.java:10 advances only contiguously |
| R2 | fixed | factory removed |

## New findings
| ID | Severity | Stage | Location | Principle | Why it matters here | Owner | Status |
|---|---|---|---|---|---|---|---|

## Principles cited
| Principle | Registry slug |
|---|---|
"""


def review_cycle() -> None:
    """record-review → gate → re-review → skip-nit, all in a temp dir (no repo files touched)."""
    import contextlib
    import io
    import tempfile

    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        wl, pr, rv = d / "wl.md", d / "principles.md", d / "review.md"
        quiet = lambda f: contextlib.redirect_stdout(io.StringIO())  # noqa: E731
        rv.write_text("# Review: E97-1\n\n**Verdict:** 4 BLOCKER\n\n## Findings\n1. BLOCKER: something\n", encoding="utf-8")
        with quiet(None):
            rc = ctx.record_review("E97-1", rv, wl, pr)
        expect("review: wrong-format report rejected (no silent 0)", f"{rc}/{wl.exists()}", "1/False")
        rv.write_text(REVIEW_1.replace("| R3 | nit |", "| R3 | trivial |"), encoding="utf-8")
        with quiet(None):
            rc = ctx.record_review("E97-1", rv, wl, pr)
        expect("review: malformed report rejected, nothing written", f"{rc}/{wl.exists()}", "1/False")
        rv.write_text(REVIEW_1, encoding="utf-8")
        with quiet(None):
            ctx.record_review("E97-1", rv, wl, pr)
        text = wl.read_text(encoding="utf-8")
        verbatim = all(line in text.splitlines() for line in ctx.parse_review(REVIEW_1)["new"])
        expect("review: findings copied verbatim", "yes" if verbatim else "no", "yes")
        expect("review: gate blocked by must/should", ",".join(ctx.review_gate(text)["blocking"]), "R1,R2")
        rv.write_text(REVIEW_2, encoding="utf-8")
        with quiet(None):
            ctx.record_review("E97-1", rv, wl, pr)
        g = ctx.review_gate(wl.read_text(encoding="utf-8"), "bbbb33334444")
        expect("re-review: fixed ⇒ gate clear, nit stays open", f"{g['blocking']}/{g['open_nits']}/{g['reviewed_at_fp']}", "[]/['R3']/True")
        # principle tally: YAGNI cited on a second ticket ⇒ concept-note candidate
        rv.write_text(REVIEW_1.replace("E97-1", "E98-1").replace("| R1 | must-fix | correctness | x/src/main/java/A.java:10 | I3 contiguous watermark | commits past a gap | human | open |\n", "").replace("| R2 |", "| R1 |").replace("| R3 |", "| R2 |"), encoding="utf-8")
        with quiet(None):
            ctx.record_review("E98-1", rv, d / "wl2.md", pr)
        yagni = next((l for l in pr.read_text(encoding="utf-8").splitlines() if l.startswith("| YAGNI")), "")
        expect("principles: 2 tickets ⇒ candidate", "yes" if "| 2 | yes" in yagni else yagni, "yes")
    # commit gate refuses a close commit while a must-fix is open
    sys.path.insert(0, str(ROOT / ".githooks"))
    import commit_msg as cm
    real = cm.staged
    open_wl = "## Review findings\n| ID | Severity | Stage | Location | Principle | Why | Owner | Status |\n|---|---|---|---|---|---|---|---|\n| R1 | must-fix | correctness | a:1 | I3 | x | human | open |\n"
    cm.staged = lambda path: ("### [E2-0] x\n- Learning: [x] a · teach-back: pass (1/1)\n" if path == "PROGRESS.md"
                              else open_wl if path.endswith("E2-0.md") else real(path))
    import tempfile as _t
    f = _t.NamedTemporaryFile("w", delete=False, suffix=".txt", encoding="utf-8")
    f.write("E2-0: close\n")
    f.close()
    import contextlib as _c
    import io as _io
    with _c.redirect_stderr(_io.StringIO()):
        rc = cm.main(f.name)
    os.unlink(f.name)
    cm.staged = real
    expect("commit gate: open must-fix blocks close commit", str(rc), "1")


def main() -> int:
    saved = ctx.read(ctx.STATE)
    try:
        ctx.set_current("E4-0")  # Own
        expect("Own: Edit src/main", guard("Edit", {"file_path": p("review-worker", "src", "main", "java", "X.java"), "old_string": "a", "new_string": "b"}), "deny")
        expect("Own: Write src/test", guard("Write", {"file_path": p("shared", "src", "test", "java", "FooTest.java"), "content": "x"}), "deny")
        expect("Own: Edit PROGRESS.md", guard("Edit", {"file_path": p("PROGRESS.md"), "old_string": "a", "new_string": "b"}), "allow")
        expect("Own: Edit worklog", guard("Edit", {"file_path": p(".claude", "worklog", "E4-0.md"), "old_string": "a", "new_string": "b"}), "allow")
        expect("Own: bash redirect into src", guard("Bash", {"command": "echo x > review-worker/src/main/java/X.java"}), "deny")
        expect("Own: powershell Set-Content src", guard("PowerShell", {"command": "Set-Content shared\\src\\main\\java\\X.java 'x'"}), "deny")
        expect("Own: mvnw test 2>&1 | tail", guard("Bash", {"command": "./mvnw -pl review-worker test 2>&1 | tail -40"}), "allow")
        expect("Own: git diff src", guard("Bash", {"command": "git diff HEAD -- review-worker/src"}), "allow")

        ctx.set_current("E5-5")  # newly Own (amendment 1), scope all
        expect("E5-5 Own: Edit src/main", guard("Edit", {"file_path": p("shared", "src", "main", "java", "dev", "reviewbot", "shared", "ClaimStep.java"), "old_string": "a", "new_string": "b"}), "deny")

        ctx.set_current("E5-9a")  # split Own: **/finalize/** is the human's
        fin = ("shared", "src", "main", "java", "dev", "reviewbot", "shared", "finalize")
        expect("E5-9a split: Edit finalize core", guard("Edit", {"file_path": p(*fin, "Finalizer.java"), "old_string": "a", "new_string": "b"}), "deny")
        expect("E5-9a split: Write finalize test", guard("Write", {"file_path": p("shared", "src", "test", "java", "dev", "reviewbot", "shared", "finalize", "FinalizerTest.java"), "content": "x"}), "deny")
        expect("E5-9a split: Edit wiring outside", guard("Edit", {"file_path": p("review-worker", "src", "main", "java", "dev", "reviewbot", "worker", "WorkerWiring.java"), "old_string": "a", "new_string": "b"}), "allow")
        expect("E5-9a split: shell write into finalize", guard("Bash", {"command": "echo x > shared/src/main/java/dev/reviewbot/shared/finalize/F.java"}), "deny")

        ctx.set_current("E1-0")  # Pair
        expect("Pair: Edit src/main", guard("Edit", {"file_path": p("shared", "src", "main", "java", "X.java"), "old_string": "a", "new_string": "b"}), "allow")
        expect("Write .claude/state", guard("Write", {"file_path": p(".claude", "state", "current"), "content": "{}"}), "deny")
        expect("bash write .claude/state", guard("Bash", {"command": "echo '{}' > .claude/state/current"}), "deny")
        expect("heredoc mentioning .claude/state", guard("Bash", {"command": "cat >> .gitignore <<'EOF'\n.claude/state/\nEOF"}), "allow")
        expect("Read spec.md (no limit)", guard("Read", {"file_path": p("docs", "spec.md")}), "ask")
        expect("Read spec.md (limit)", guard("Read", {"file_path": p("docs", "spec.md"), "offset": 300, "limit": 50}), "allow")
        expect("Read backlog (no limit)", guard("Read", {"file_path": p("tickets", "v1-backlog.md")}), "ask")
        expect("cat backlog", guard("Bash", {"command": "cat tickets/v1-backlog.md"}), "ask")
        expect("ctx.py bundle", guard("Bash", {"command": "python scripts/claude/ctx.py bundle E4-0"}), "allow")
        t = p("shared", "src", "test", "java", "ATest.java")
        expect("test: add @Disabled", guard("Edit", {"file_path": t, "old_string": "@Test void a(){ assertThat(x).isEqualTo(1); }", "new_string": "@Disabled @Test void a(){ assertThat(x).isEqualTo(1); }"}), "ask")
        expect("test: drop assertions", guard("Edit", {"file_path": t, "old_string": "assertThat(x).isEqualTo(1); verify(m).run();", "new_string": "assertThat(x).isNotNull();"}), "ask")
        expect("test: remove @Test", guard("Edit", {"file_path": t, "old_string": "@Test void a(){}\n@Test void b(){}", "new_string": "@Test void a(){}"}), "ask")
        expect("test: add assertion", guard("Edit", {"file_path": t, "old_string": "assertThat(x).isNotNull();", "new_string": "assertThat(x).isNotNull(); assertThat(y).isEqualTo(2);"}), "allow")
        expect("pom: lower engine gate", guard("Edit", {"file_path": p("pom.xml"), "old_string": "<jacoco.engine.branch.minimum>0.95</jacoco.engine.branch.minimum>", "new_string": "<jacoco.engine.branch.minimum>0.50</jacoco.engine.branch.minimum>"}), "ask")
        expect("pom: add dependency", guard("Edit", {"file_path": p("shared", "pom.xml"), "old_string": "</dependencies>", "new_string": "<dependency><artifactId>x</artifactId></dependency></dependencies>"}), "allow")
        expect("edit coverage-gate.sh", guard("Edit", {"file_path": p(".github", "scripts", "coverage-gate.sh"), "old_string": "a", "new_string": "b"}), "ask")
        expect("mvnw -DskipTests", guard("PowerShell", {"command": ".\\mvnw.cmd clean install -DskipTests"}), "ask")
        expect("git commit --no-verify", guard("Bash", {"command": "git commit --no-verify -m x"}), "ask")
        expect("rm a test file", guard("Bash", {"command": "rm shared/src/test/java/ATest.java"}), "ask")
        s = p("tickets", "status.md")
        expect("status.md: change Mode", guard("Edit", {"file_path": s, "old_string": "| E4-0 | OffsetTracker | M | Own | all | todo |", "new_string": "| E4-0 | OffsetTracker | M | Pair | all | todo |"}), "ask")
        expect("status.md: change Own scope", guard("Edit", {"file_path": s, "old_string": "| E5-9a | Finalize | M | Own | **/finalize/** | todo |", "new_string": "| E5-9a | Finalize | M | Own | **/none/** | todo |"}), "ask")
        expect("status.md: change State", guard("Edit", {"file_path": s, "old_string": "| E4-0 | OffsetTracker | M | Own | all | todo |", "new_string": "| E4-0 | OffsetTracker | M | Own | all | wip |"}), "allow")
        expect("mentor: git diff && status", guard("Bash", {"command": "git diff HEAD --stat && git status --short"}, "java-mentor"), "allow")
        expect("mentor: verify.py", guard("Bash", {"command": "python scripts/claude/verify.py -pl shared"}, "java-mentor"), "allow")
        expect("mentor: sed -i", guard("Bash", {"command": "sed -i s/a/b/ shared/src/main/java/X.java"}, "java-mentor"), "deny")
        expect("mentor: write its review report", guard("Write", {"file_path": p(".review", "E4-0.md"), "content": "x"}, "java-mentor"), "allow")
        expect("mentor: write anything else", guard("Write", {"file_path": p(".claude", "worklog", "E4-0.md"), "content": "x"}, "java-mentor"), "deny")
        expect("mentor: edit src", guard("Edit", {"file_path": p("shared", "src", "main", "java", "X.java"), "old_string": "a", "new_string": "b"}, "java-mentor"), "deny")

        # SessionStart produces valid JSON with the current ticket
        r = subprocess.run([sys.executable, str(SESSION)], input='{"source":"compact"}', capture_output=True,
                           text=True, encoding="utf-8", env=ENV)
        out = json.loads(r.stdout) if r.returncode == 0 and r.stdout.strip() else {}
        add = out.get("hookSpecificOutput", {}).get("additionalContext", "")
        expect("SessionStart injects current ticket", "ok" if "E1-0" in add and "Pair" in add else "missing", "ok")

        expect("notes: Claude edits an ADR", guard("Edit", {"file_path": p("docs", "adr", "0001-use-jqwik.md"), "old_string": "a", "new_string": "b"}), "ask")
        expect("notes: Claude writes an inbox note", guard("Write", {"file_path": p("docs", "learning", "inbox", "solid.md"), "content": "x"}), "ask")
        expect("notes: Claude updates a Notion page", guard("mcp__claude_ai_Notion__notion-update-page", {"page_id": "x", "command": "replace_content"}), "ask")
        expect("notes: ADR index README stays editable", guard("Edit", {"file_path": p("docs", "adr", "README.md"), "old_string": "a", "new_string": "b"}), "allow")

        review_cycle()

        # ctx.py: every ticket's spec references resolve
        unresolved = [tid for tid, t in ctx.tickets().items() if ctx.resolve_refs(t.get("spec_line", ""))[1]]
        expect("ctx: no unresolved spec refs", ",".join(unresolved) or "none", "none")
        expect("ctx: 54 tickets parsed", str(len(ctx.tickets())), "54")
    finally:
        if saved:
            ctx.STATE.write_text(saved, encoding="utf-8")
        elif ctx.STATE.exists():
            ctx.STATE.unlink()
    print(f"\n{'ALL PASS' if not fails else f'{fails} FAILURE(S)'}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
