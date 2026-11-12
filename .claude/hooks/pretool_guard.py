#!/usr/bin/env python3
"""PreToolUse guard for reviewbot (registered in .claude/settings.json).

Turns written rules into checks. deny beats ask; silence means the normal permission flow.
  1. .claude/state/** is written only by /ticket's render step, never by Claude            deny
  2. java-mentor subagent: read-only (its report file .review/<ID>.md excepted)     deny
  3. Own mode: no Claude edits under any src/, or only inside the split ticket's Own scope
     packages (file tools; shell writes best-effort)                                         deny
  4. Full Read of docs/spec.md or tickets/v1-backlog.md (use scripts/claude/ctx.py)         ask
  5. Changing a Mode or Own scope cell in tickets/status.md (the human owns them)           ask
  6. Test-weakening tripwire: @Disabled/assumptions added, @Test removed, fewer assertions,
     CI/coverage gate edits, skip flags, deleting test files                                ask
  7. Claude writing a note or ADR (docs/adr/NNNN-*, docs/learning/inbox/*, Notion page create/update) ask
     (notes: only the /notes write|scaffold|sync flow, human's own words; ADRs: never Claude's text)
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


def current_mode() -> tuple[str | None, str, list[str]]:
    """(ticket id, mode, own-scope globs; [] means the whole src/ tree)."""
    try:
        import ctx  # scripts/claude/ctx.py
        tid = ctx.current()
        if not tid:
            return None, "none", []
        row = ctx.board_row(tid) or {}
        scope = row.get("Own scope", "all")
        globs = [] if scope in ("", "all", "—") else [g.strip() for g in scope.split(",") if g.strip()]
        return tid, row.get("Mode", "?"), globs
    except Exception as e:  # never block on our own bug
        print(f"pretool_guard: could not read mode: {e}", file=sys.stderr)
        return None, "unknown", []


def glob_re(glob: str) -> re.Pattern:
    """'**/finalize/**' -> regex over a repo-relative posix path."""
    out, i = "", 0
    while i < len(glob):
        if glob.startswith("**/", i):
            out, i = out + "(?:.*/)?", i + 3
        elif glob.startswith("**", i):
            out, i = out + ".*", i + 2
        elif glob[i] == "*":
            out, i = out + "[^/]*", i + 1
        else:
            out, i = out + re.escape(glob[i]), i + 1
    return re.compile(out + r"\Z")


def status_cells(text: str) -> dict[str, tuple[str, str]]:
    """ID -> (Mode, Own scope), located by header names."""
    out, header = {}, None
    for line in text.splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if header is None and "Mode" in cells:
            header = cells
            continue
        if header and cells and re.fullmatch(r"E\d+-\d+[ab]?", cells[0]):
            row = dict(zip(header, cells))
            out[cells[0]] = (row.get("Mode", ""), row.get("Own scope", ""))
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

    # 2. java-mentor is read-only except for its report file
    if agent == "java-mentor":
        if tool in FILE_TOOLS and not re.fullmatch(r"\.review/E\d+-\d+[ab]?\.md", path):
            found.append(("deny", "java-mentor is a read-only reviewer; its only writable file is "
                                  ".review/<ID>.md (the review report)."))
        if tool in SHELL_TOOLS:
            parts = [p for p in re.split(r"&&|\|\||;|\|", cmd_w) if p.strip()]
            if writes or not all(MENTOR_SHELL_OK.match(p) for p in parts):
                found.append(("deny", "java-mentor may run only read-only commands (git diff/log/show/status, ctx.py, "
                                      "verify.py/mvnw, ls/cat/grep)."))

    # 3. Own mode: Claude never writes the human's code (whole src/, or the split ticket's own scope)
    tid, mode, globs = current_mode()
    if mode == "Own" and not globs:
        msg = (f"Own mode ({tid}): the human writes all code and tests. Don't edit under src/; give a hint at the "
               "current /hint level or point to file:line. Only the human can change the mode (tickets/status.md "
               "+ /ticket).")
        if tool in FILE_TOOLS and re.search(r"(^|/)src/", path):
            found.append(("deny", msg))
        if tool in SHELL_TOOLS and writes and re.search(r"(^|[\s\"'=/\\])src[/\\]", cmd):
            found.append(("deny", msg + " (shell write into src/ detected)"))
    elif mode == "Own":
        msg = (f"Split Own ticket ({tid}): {', '.join(globs)} belong to the human (code and tests). Claude may "
               "write only the wiring outside them. Give a hint or point to file:line instead.")
        if tool in FILE_TOOLS and any(glob_re(g).match(path) for g in globs):
            found.append(("deny", msg))
        frags = [g.replace("**/", "").replace("/**", "").strip("/") for g in globs]
        norm = cmd.replace("\\", "/")
        if tool in SHELL_TOOLS and writes and any(f and f"/{f}/" in norm for f in frags):
            found.append(("deny", msg + " (shell write into an own-scope package detected)"))

    # 4. big-doc full reads
    if tool == "Read" and path in BIG_DOCS and not ti.get("limit"):
        found.append(("ask", f"Full read of {path} (~23–37k tokens). For ticket work use "
                             "`python scripts/claude/ctx.py bundle|ticket|spec|grep`. Approve only if the human "
                             "asked for a whole-document read."))
    if tool in SHELL_TOOLS and re.search(r"spec\.md|v1-backlog\.md", cmd) and "ctx.py" not in cmd \
            and re.search(r"\b(cat|type|Get-Content|gc|less|more|bat)\b", cmd) \
            and not re.search(r"\b(head|tail|sed\s+-n|Select-Object|-TotalCount|-Tail|grep)\b", cmd):
        found.append(("ask", "Shell read of a whole big doc. Use scripts/claude/ctx.py instead."))

    # 5. Mode / Own scope cells in the board (the human's decision)
    if tool in FILE_TOOLS and path == "tickets/status.md":
        old, new = edit_texts(tool, ti, path)
        if tool == "Write":
            before, after = status_cells(old), status_cells(new)
            changed = sorted(k for k in before if k in after and before[k] != after[k])
        else:
            pat = r"\|\s*(Delegate|Pair|Own|TBD)\s*\|\s*([^|]*?)\s*\|"
            changed = ["?"] if re.findall(pat, old) != re.findall(pat, new) else []
        if changed:
            found.append(("ask", f"This edit changes a ticket's Mode or Own scope in tickets/status.md "
                                 f"({', '.join(changed)}). Those are the human's decision."))

    # 7. ADRs are the human's. Notes are written by /notes write from the human's own chat answers (Claude may
    #    only fix grammar/wording); the human approves each write.
    if tool in FILE_TOOLS and re.fullmatch(r"docs/adr/\d{4}-[^/]+\.md", path):
        found.append(("ask", f"{path} is a human-written ADR. Approve only a scaffold you asked for, "
                             "never ADR content written by Claude."))
    if tool in FILE_TOOLS and path.startswith("docs/learning/inbox/"):
        found.append(("ask", f"{path} is a note. Approve only a /notes write|scaffold|sync you asked for, with text "
                             "that is your own words from the chat (Claude may only fix grammar/wording)."))
    if tool.startswith("mcp__") and "otion" in tool and re.search(r"create-pages|update-page", tool):
        found.append(("ask", "Claude is about to write to Notion. Approve only a /notes write|scaffold|sync you asked "
                             "for, with note text that is your own words from the chat (grammar/wording fixes "
                             "only, no ideas added by Claude)."))

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
