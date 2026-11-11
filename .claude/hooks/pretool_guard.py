#!/usr/bin/env python3
"""PreToolUse guard for reviewbot (registered in .claude/settings.json).

Turns written rules into checks. deny beats ask; silence means the normal permission flow.
  1. .claude/state/** is written only by /ticket's render step, never by Claude            deny
  2. java-mentor subagent: read-only (its own memory dir excepted)                         deny
  3. Own mode: no Claude edits under any src/ (file tools; shell writes best-effort)         deny
  4. Full Read of docs/spec.md or tickets/v1-backlog.md (use scripts/claude/ctx.py)         ask
  5. Changing a Mode cell in tickets/status.md (the human owns modes)                       ask
  6. Test-weakening tripwire: @Disabled/assumptions added, @Test removed, fewer assertions,
     CI/coverage gate edits, skip flags, deleting test files                                ask
Fails open with a stderr notice (exit 1 = non-blocking) if the guard itself crashes.
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

ROOT = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2])
sys.path.insert(0, str(ROOT / "scripts" / "claude"))

FILE_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
SHELL_TOOLS = {"Bash", "PowerShell"}
BIG_DOCS = {"docs/spec.md", "tickets/v1-backlog.md"}

WRITE_OPS = re.compile(
    r"(?<![-=<])>{1,2}(?!&)|\bsed\s+-i|\btee\b|\bcp\b|\bmv\b|\brm\b|\btouch\b|\bdd\b|\bpatch\b"
    r"|\bgit\s+(checkout|restore|apply|stash|reset|clean|mv|rm)\b"
    r"|Set-Content|Add-Content|Out-File|New-Item|Remove-Item|Copy-Item|Move-Item|Rename-Item"
    r"|\bpython3?\b[^|;&]*\s-c\b",
    re.I,
)
HARMLESS_REDIRECTS = re.compile(r"\d?>&\d|\d?>\s*/dev/null|\d?>\s*\$null|2>\s*nul\b", re.I)
SKIP_FLAGS = re.compile(
    r"-DskipTests\b|-Dmaven\.test\.skip|-Djacoco\.skip|-Dspotless\.(check\.)?skip|"
    r"-Djacoco\.haltOnFailure=false|-Dmaven\.test\.failure\.ignore|--no-verify\b",
    re.I,
)
DISABLED = re.compile(r"@Disabled\b|@Ignore\b|assumeTrue\(\s*false|assumeFalse\(\s*true")
TEST_ANN = re.compile(r"@(Test|Property|ParameterizedTest|RepeatedTest|Example|TestFactory|TestTemplate)\b")
ASSERTS = re.compile(
    r"\bassert\w*\s*\(|\bverify\s*\(|\bfail\s*\(|"
    r"\.(is|has|contains|matches|satisfies|doesNot|startsWith|endsWith|extracting)\w*\s*\("
)
GATE_TOKENS = re.compile(
    r"jacoco|<minimum>|haltOnFailure|skipTests|maven\.test\.skip|testFailureIgnore|<skip>|"
    r"spotless|<excludes?>|failIfNoTests|<rules>|<rule>|<limit>|coverage",
    re.I,
)
GATE_FILES = {".github/scripts/coverage-gate.sh", ".github/workflows/ci.yml"}
MENTOR_SHELL_OK = re.compile(
    r"^\s*(git\s+(diff|log|show|status|blame|rev-parse|ls-files)\b|python3?\s+\S*scripts/claude/(ctx|verify)\.py\b"
    r"|ls\b|cat\b|head\b|tail\b|grep\b|rg\b|wc\b|find\b(?!.*(-delete|-exec))|\.?/?mvnw(\.cmd)?\b"
    r"|Get-Content\b|Select-String\b|Get-ChildItem\b)",
    re.I,
)


def rel(path: str | None) -> str:
    if not path:
        return ""
    p = path.replace("\\", "/")
    root = str(ROOT).replace("\\", "/").rstrip("/")
    if p.lower().startswith(root.lower() + "/"):
        p = p[len(root) + 1:]
    return p[2:] if p.startswith("./") else p


def is_test_file(r: str) -> bool:
    return "/src/test/" in f"/{r}" or bool(re.search(r"(Test|Tests|IT)\.java$", r))


def disk(r: str) -> str:
    p = ROOT / r
    try:
        return p.read_text(encoding="utf-8") if p.is_file() else ""
    except Exception:
        return ""


def edit_texts(tool: str, ti: dict, r: str) -> tuple[str, str]:
    """(old, new) text for the region being changed."""
    if tool == "Write":
        return disk(r), ti.get("content", "") or ""
    if tool == "MultiEdit":
        edits = ti.get("edits") or []
        return "\n".join(e.get("old_string", "") for e in edits), "\n".join(e.get("new_string", "") for e in edits)
    if tool == "NotebookEdit":
        return "", ti.get("new_source", "") or ""
    return ti.get("old_string", "") or "", ti.get("new_string", "") or ""


def current_mode() -> tuple[str | None, str]:
    try:
        import ctx  # scripts/claude/ctx.py
        tid = ctx.current()
        return tid, (ctx.mode_of(tid) if tid else "none")
    except Exception as e:  # never block on our own bug
        print(f"pretool_guard: could not read mode: {e}", file=sys.stderr)
        return None, "unknown"


def status_modes(text: str) -> dict[str, str]:
    out = {}
    for line in text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 4 and re.fullmatch(r"E\d+-\d+[ab]?", cells[0]):
            out[cells[0]] = cells[3]
    return out


def check(data: dict) -> list[tuple[str, str]]:
    tool = data.get("tool_name", "")
    ti = data.get("tool_input") or {}
    agent = data.get("agent_type")
    found: list[tuple[str, str]] = []
    path = rel(ti.get("file_path") or ti.get("notebook_path"))
    raw_cmd = ti.get("command", "") or ""
    # heredoc bodies are data, not commands: "cat >> .gitignore <<'EOF' ... .claude/state/ ... EOF" must not trip
    cmd = re.sub(r"<<-?\s*(['\"]?)(\w+)\1[^\n]*\n.*?\n\s*\2\s*(?=\n|$)", "<<HEREDOC", raw_cmd, flags=re.S)
    cmd_w = HARMLESS_REDIRECTS.sub(" ", cmd)
    writes = bool(WRITE_OPS.search(cmd_w))

    # 1. state pointer
    if tool in FILE_TOOLS and path.startswith(".claude/state/"):
        found.append(("deny", ".claude/state/ is written only by the human's /ticket invocation, never by Claude."))
    if tool in SHELL_TOOLS and ".claude/state" in cmd.replace("\\", "/") and writes:
        found.append(("deny", ".claude/state/ is written only by the human's /ticket invocation, never by Claude."))

    # 2. java-mentor is read-only
    if agent == "java-mentor":
        if tool in FILE_TOOLS and not path.startswith(".claude/agent-memory/java-mentor/"):
            found.append(("deny", "java-mentor is a read-only reviewer; it may write only its own memory directory."))
        if tool in SHELL_TOOLS:
            parts = [p for p in re.split(r"&&|\|\||;|\|", cmd_w) if p.strip()]
            if writes or not all(MENTOR_SHELL_OK.match(p) for p in parts):
                found.append(("deny", "java-mentor may run only read-only commands (git diff/log/show/status, ctx.py, "
                                      "verify.py/mvnw, ls/cat/grep)."))

    # 3. Own mode: Claude never writes code
    tid, mode = current_mode()
    if mode == "Own":
        msg = (f"Own mode ({tid}): the human writes all code and tests. Don't edit under src/; give a hint at the "
               "current /hint level or point to file:line. Only the human can change the mode (tickets/status.md "
               "+ /ticket).")
        if tool in FILE_TOOLS and re.search(r"(^|/)src/", path):
            found.append(("deny", msg))
        if tool in SHELL_TOOLS and writes and re.search(r"(^|[\s\"'=/\\])src[/\\]", cmd):
            found.append(("deny", msg + " (shell write into src/ detected)"))

    # 4. big-doc full reads
    if tool == "Read" and path in BIG_DOCS and not ti.get("limit"):
        found.append(("ask", f"Full read of {path} (~23–37k tokens). For ticket work use "
                             "`python scripts/claude/ctx.py bundle|ticket|spec|grep`. Approve only if the human "
                             "asked for a whole-document read."))
    if tool in SHELL_TOOLS and re.search(r"spec\.md|v1-backlog\.md", cmd) and "ctx.py" not in cmd \
            and re.search(r"\b(cat|type|Get-Content|gc|less|more|bat)\b", cmd) \
            and not re.search(r"\b(head|tail|sed\s+-n|Select-Object|-TotalCount|-Tail|grep)\b", cmd):
        found.append(("ask", "Shell read of a whole big doc. Use scripts/claude/ctx.py instead."))

    # 5. Mode cells in the board
    if tool in FILE_TOOLS and path == "tickets/status.md":
        old, new = edit_texts(tool, ti, path)
        if tool == "Write":
            before, after = status_modes(old), status_modes(new)
            changed = sorted(k for k in before if k in after and before[k] != after[k])
        else:
            pat = r"\|\s*(Delegate|Pair|Own|TBD)\s*\|"
            changed = ["?"] if re.findall(pat, old) != re.findall(pat, new) else []
        if changed:
            found.append(("ask", f"This edit changes a ticket Mode in tickets/status.md ({', '.join(changed)}). "
                                 "Modes are the human's decision."))

    # 6. test-weakening tripwire
    if tool in FILE_TOOLS and path.endswith(".java") and is_test_file(path):
        old, new = edit_texts(tool, ti, path)
        if len(DISABLED.findall(new)) > len(DISABLED.findall(old)):
            found.append(("ask", f"{path}: adds @Disabled/@Ignore or a always-false assumption (CLAUDE.md rule 4: "
                                 "never weaken a test; explain why the test is wrong first)."))
        if len(TEST_ANN.findall(new)) < len(TEST_ANN.findall(old)):
            found.append(("ask", f"{path}: removes test method(s) (rule 4)."))
        if len(ASSERTS.findall(new)) < len(ASSERTS.findall(old)):
            found.append(("ask", f"{path}: fewer assertions/verifications than before "
                                 f"({len(ASSERTS.findall(old))} → {len(ASSERTS.findall(new))}) (rule 4)."))
    if tool in FILE_TOOLS and path in GATE_FILES:
        found.append(("ask", f"{path} is a CI/coverage gate. Changing it can weaken the definition of done."))
    if tool in FILE_TOOLS and path.endswith("pom.xml"):
        old, new = edit_texts(tool, ti, path)
        if tool == "Write":
            new_lines = set(new.splitlines())
            touched = [l for l in old.splitlines() if GATE_TOKENS.search(l) and l not in new_lines]
        else:
            touched = [l for l in old.splitlines() if GATE_TOKENS.search(l)]
        if touched:
            found.append(("ask", f"{path}: modifies existing test/coverage/lint gate config "
                                 f"(e.g. `{touched[0].strip()[:80]}`)."))
    if tool in SHELL_TOOLS and SKIP_FLAGS.search(cmd):
        found.append(("ask", f"Command skips tests/coverage/lint/hooks (`{SKIP_FLAGS.search(cmd).group(0)}`)."))
    if tool in SHELL_TOOLS and re.search(r"\b(rm|del|Remove-Item|git\s+rm)\b[^|;&]*(Test|Tests|IT)\.java", cmd):
        found.append(("ask", "Command deletes a test file (rule 4)."))
    return found


def main() -> int:
    data = json.loads(sys.stdin.read() or "{}")
    found = check(data)
    if not found:
        return 0
    decision = "deny" if any(d == "deny" for d, _ in found) else "ask"
    reasons = " | ".join(r for d, r in found if d == decision)
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                             "permissionDecision": decision,
                                             "permissionDecisionReason": f"[reviewbot guard] {reasons}"}}))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # fail open, but visibly
        print(f"pretool_guard crashed (guard inactive for this call): {exc!r}", file=sys.stderr)
        sys.exit(1)
