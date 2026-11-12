#!/usr/bin/env python3
"""ctx.py: deterministic context extractor for reviewbot ticket work.

Prints exactly the slice of the canonical docs a ticket needs, so neither Claude
nor a subagent ever has to read docs/spec.md (~23k tokens) or
tickets/v1-backlog.md (~37k tokens) whole. The docs stay single and canonical;
this script only reads them.

Subcommands
  ticket ID                 the ticket section, verbatim from the backlog
  spec REF [REF...]         spec sections: 7.1 | 8 (whole) | 8.intro | appendix:11
  grep PATTERN              spec lines matching PATTERN (case-insensitive), with their section
  bundle ID [--set-current] status + ticket + every cited spec section + notes + worklog
  status [ID]               one board row, or a board summary
  next                      first todo ticket (execution order) whose deps are all done
  notes [ID]                required concept notes / tech pages / ADR for a ticket
  current                   the current ticket pointer (.claude/state/current)
  worklog [ID]              the ticket's worklog (.claude/worklog/ID.md)
  fingerprint               hash of the code working tree (attempt detection for /hint)
  hint-state [SUB]          current ticket, fingerprint, hint-ladder table
  close-check [ID]          everything /close needs to gate a ticket
  check-all                 resolve every ticket's references; report sizes + unresolved refs

Always exits 0 for bundle/hint-state/close-check (skill injection aborts on non-zero).
"""
from __future__ import annotations

import datetime as _dt
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

try:  # Windows consoles default to cp1252; the spec is full of § → ⚠.
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:  # pragma: no cover
    pass

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "docs" / "spec.md"
BACKLOG = ROOT / "tickets" / "v1-backlog.md"
STATUS = ROOT / "tickets" / "status.md"
NOTES = ROOT / "docs" / "learning" / "ticket-notes.md"
PROGRESS = ROOT / "PROGRESS.md"
WORKLOG_DIR = ROOT / ".claude" / "worklog"
STATE = ROOT / ".claude" / "state" / "current"

ID_RE = re.compile(r"(?i)\bE(\d+)-(\d+)([ab]?)\b")
EXPECTED_STYLE = {"Own": "coach", "Pair": "Learning", "Delegate": "(any; default fine)"}
TEACH_BACK = {"Own": 5, "Pair": 3, "Delegate": 1}
MODE_RULES = {
    "Own": (
        "Human writes ALL production code and tests. Claude coaches only: no code in chat or files "
        "(the PreToolUse guard denies Claude's edits under src/). Claude may list test cases (names + what "
        "they assert), never test code. Escalate help only via /hint (one level per new attempt, never the "
        "answer). /review-mine after green. Teach-back: 5 questions."
    ),
    "Pair": (
        "Output style Learning. Claude writes wiring/config/test scaffolding in small steps and leaves "
        "TODO(human) at every decision-bearing point (ordering, validation, compare, state transition). "
        "Human writes those. /review-mine before /close. Teach-back: 3 questions."
    ),
    "Delegate": (
        "Claude proposes a short plan (files, tests), waits for the human's go, implements, runs "
        "scripts/claude/verify.py. Human reviews the diff and runs /code-review. Teach-back: 1 question."
    ),
}
SPLIT_STYLE = "Learning while Claude does the wiring, then coach while the human writes the own-scope core"
SPLIT_RULES = (
    "Split Own ticket. The packages {scope} (code AND tests) are the human's: Claude never writes there, and the "
    "guard denies it. Claude writes only the wiring/config outside them, first, in small steps. Then the human "
    "writes the core with /hint help only. Claude may list test cases (names + what they assert), never test "
    "code. /review-mine after green. Teach-back: 5 questions."
)


# --------------------------------------------------------------------------- helpers
def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def norm_id(raw: str) -> str | None:
    m = ID_RE.search(raw or "")
    return f"E{m.group(1)}-{m.group(2)}{m.group(3).lower()}" if m else None


def unescape(s: str) -> str:
    """Undo Google-Docs markdown escapes (\\[ \\_ \\# \\= ...) for parsing only."""
    return re.sub(r"\\([\\`*_{}\[\]()#+\-.!=|<>~&])", r"\1", s)


def approx_tokens(text: str) -> int:
    return len(text) // 4


# --------------------------------------------------------------------------- backlog
_TICKET_HDR = re.compile(r"^###\s+\*\*(?:\[(.)\]\s+)?\[(E\d+-\d+[ab]?)\]\s+(.+?)\*\*\s*$")


def tickets() -> dict[str, dict]:
    """ID -> {title, start, end, raw_lines, meta...} parsed from the backlog."""
    lines = read(BACKLOG).splitlines()
    out: dict[str, dict] = {}
    i = 0
    while i < len(lines):
        m = _TICKET_HDR.match(unescape(lines[i]))
        if not m:
            i += 1
            continue
        tid, title = m.group(2), m.group(3).strip()
        j = i + 1
        while j < len(lines) and not re.match(r"^---\s*$", lines[j]) and not re.match(r"^#{1,3}\s", lines[j]):
            j += 1
        body = lines[i:j]
        t = {"id": tid, "title": title, "start": i + 1, "end": j, "raw": "\n".join(body).rstrip()}
        plain = [unescape(x) for x in body]
        for x in plain:
            if x.startswith("**Epic:**"):
                dm = re.search(r"\*\*Depends on:\*\*\s*(.*?)\s*(?:·|$)", x)
                sm = re.search(r"\*\*Size:\*\*\s*([SML])\b", x)
                t["deps"] = ID_RE.findall(dm.group(1)) if dm else []
                t["deps"] = [f"E{a}-{b}{c.lower()}" for a, b, c in t["deps"]]
                t["size"] = sm.group(1) if sm else "?"
            elif x.startswith("**Spec sections to read first:**"):
                t["spec_line"] = x.split(":**", 1)[1].strip()
            elif x.startswith("**Invariants that apply:**"):
                t["invariants"] = x.split(":**", 1)[1].strip()
            elif x.startswith("**Known traps:**"):
                t["traps"] = x.split(":**", 1)[1].strip()
        # acceptance criteria block
        ac, on = [], False
        for x in plain:
            if x.startswith("**Acceptance criteria:**"):
                on = True
                continue
            if on and x.startswith("**Known traps:**"):
                break
            if on and x.strip():
                ac.append(x.strip())
        t["ac"] = ac
        out[tid] = t
        i = j
    return out


def rulings_homes() -> dict[str, str]:
    """O-ruling number -> spec-home text (e.g. 'O5' -> '§8.4'), from the backlog quick reference."""
    homes = {}
    for line in read(BACKLOG).splitlines():
        cells = [c.strip() for c in unescape(line).split("|")]
        if len(cells) >= 5 and re.fullmatch(r"O\d{1,2}", cells[1] or ""):
            homes[cells[1]] = cells[3]
    return homes


# --------------------------------------------------------------------------- spec
_SPEC_HDR = re.compile(r"^(##|###)\s+(?:(\d+)(?:\.(\d+))?\.?\s+)?(.*)$")


def spec_index() -> tuple[list[str], list[dict]]:
    lines = read(SPEC).splitlines()
    heads = []
    for n, line in enumerate(lines):
        m = _SPEC_HDR.match(line)
        if not m:
            continue
        level = len(m.group(1))
        major, minor, title = m.group(2), m.group(3), m.group(4).strip()
        key = None
        if major and minor:
            key = f"{major}.{minor}"
        elif major:
            key = major
        elif title.lower().startswith("appendix"):
            key = "appendix"
        heads.append({"line": n, "level": level, "key": key, "title": line.lstrip("# ").strip()})
    return lines, heads


def _span(lines, heads, idx, stop_levels):
    start = heads[idx]["line"]
    end = len(lines)
    for h in heads[idx + 1:]:
        if h["level"] in stop_levels:
            end = h["line"]
            break
    seg = lines[start:end]
    while seg and (not seg[-1].strip() or seg[-1].strip() == "---"):
        seg.pop()
    return start, start + len(seg), "\n".join(seg)


def spec_section(ref: str) -> tuple[str, str] | None:
    """ref: '7.1' | '8' | '8.intro' | 'appendix' | 'appendix:11'. Returns (label, text)."""
    lines, heads = spec_index()
    ref = ref.strip().lstrip("§")
    if ref.startswith("appendix"):
        idx = next((i for i, h in enumerate(heads) if h["key"] == "appendix"), None)
        if idx is None:
            return None
        s, e, text = _span(lines, heads, idx, {2})
        if ":" not in ref:
            return (f"§Appendix (docs/spec.md L{s + 1}-{e})", text)
        k = ref.split(":", 1)[1]
        seg = lines[s:e]
        out, on = [], False
        for n, line in enumerate(seg):
            if re.match(rf"^{re.escape(k)}\.\s", line):
                on, first = True, s + n + 1
            elif on and (re.match(r"^\d+\.\s", line) or line.startswith("## ")):
                break
            if on:
                out.append(line)
        while out and not out[-1].strip():
            out.pop()
        return (f"§Appendix decision log #{k} (docs/spec.md L{first})", "\n".join(out)) if out else None
    intro = ref.endswith(".intro")
    key = ref[:-6] if intro else ref
    idx = next((i for i, h in enumerate(heads) if h["key"] == key), None)
    if idx is None:
        return None
    h = heads[idx]
    if h["level"] == 2:
        stops = {2, 3} if intro else {2}
    else:
        stops = {2, 3}
    s, e, text = _span(lines, heads, idx, stops)
    label = f"§{key}{' intro' if intro else ''}: {h['title']} (docs/spec.md L{s + 1}-{e})"
    return label, text


def _split_top(s: str) -> list[str]:
    parts, depth, cur = [], 0, ""
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return parts


def resolve_refs(spec_line: str) -> tuple[list[str], list[str]]:
    """Spec-sections line -> (ordered unique refs, unresolved segments)."""
    homes = rulings_homes()
    refs, unresolved = [], []

    def add(r):
        if r not in refs:
            refs.append(r)

    def sec_refs(text, allow_intro):
        found = []
        for m in re.finditer(r"§\s?(\d+)(?:\.(\d+))?", text):
            if m.group(2):
                found.append(f"{m.group(1)}.{m.group(2)}")
            elif allow_intro and re.search(r"\bintro\b", text):
                found.append(f"{m.group(1)}.intro")
            else:
                found.append(m.group(1))
        return found

    for seg in _split_top(spec_line or ""):
        got = sec_refs(seg, allow_intro=True)
        for m in re.finditer(r"decision log\s*#\s*(\d+)", seg, re.I):
            got.append(f"appendix:{m.group(1)}")
        for m in re.finditer(r"\bO(\d{1,2})\b", seg):
            got.extend(sec_refs(homes.get(f"O{m.group(1)}", ""), allow_intro=False))
        if not got and refs and re.match(r"^(steps?|items?|rows?)\b", seg, re.I):
            continue  # e.g. "§7.1 step 10 (...), step 11 (...)": continuation of the previous ref
        if not got:
            unresolved.append(seg)
        for r in got:
            add(r)
    # drop sub/intro refs already covered by a whole-section ref
    wholes = {r for r in refs if re.fullmatch(r"\d+", r)}
    refs = [r for r in refs if r in wholes or r.split(".")[0] not in wholes]
    return refs, unresolved


# --------------------------------------------------------------------------- status board / notes
def board() -> list[dict]:
    rows, header = [], None
    for line in read(STATUS).splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if header is None:
            header = cells
            continue
        if set("".join(cells)) <= set("-: "):
            continue
        rows.append(dict(zip(header, cells)))
    return rows


def board_row(tid: str) -> dict | None:
    return next((r for r in board() if r.get("ID") == tid), None)


def mode_of(tid: str) -> str:
    r = board_row(tid)
    return (r or {}).get("Mode", "?")


def notes_mapping() -> tuple[dict, dict, dict]:
    """(concepts slug->(title, area), tech slug->title, ticket->{concepts, tech, adr})."""
    concepts, tech, mapping = {}, {}, {}
    section = None
    for line in read(NOTES).splitlines():
        if line.startswith("## "):
            low = line.lower()
            section = "c" if "concept registry" in low else "t" if "tech registry" in low else "m" if "ticket mapping" in low else None
            continue
        if not line.startswith("|") or section is None:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not cells or cells[0] in ("Slug", "Ticket") or set("".join(cells)) <= set("-: "):
            continue
        if section == "c" and len(cells) >= 3:
            concepts[cells[0].strip("`")] = (cells[1], cells[2])
        elif section == "t" and len(cells) >= 2:
            tech[cells[0].strip("`")] = cells[1]
        elif section == "m" and len(cells) >= 4:
            split = lambda s: [x.strip().strip("`") for x in s.split(";") if x.strip() and x.strip() != "—"]
            mapping[cells[0]] = {"concepts": split(cells[1]), "tech": split(cells[2]), "adr": cells[3]}
    return concepts, tech, mapping


def notes_text(tid: str) -> str:
    concepts, tech, mapping = notes_mapping()
    m = mapping.get(tid)
    if not m:
        return f"(no row for {tid} in docs/learning/ticket-notes.md)"
    out = []
    for s in m["concepts"]:
        title, area = concepts.get(s, ("UNKNOWN SLUG", "?"))
        out.append(f"- concept `{s}`: {title} [{area}]")
    for s in m["tech"]:
        out.append(f"- tech `{s}`: {tech.get(s, 'UNKNOWN SLUG')}")
    out.append(f"- ADR: {m['adr']}")
    out.append(
        f"Each listed note must exist (status lite|understood) AND mention {tid} in "
        "'Where it shows up'. Notion first; fallback docs/learning/inbox/<slug>.md."
    )
    return "\n".join(out)


# --------------------------------------------------------------------------- state / worklog / fingerprint
def current() -> str | None:
    try:
        return json.loads(read(STATE) or "{}").get("id")
    except json.JSONDecodeError:
        return None


def set_current(tid: str) -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps({"id": tid, "set_at": _dt.datetime.now().isoformat(timespec="seconds")}), encoding="utf-8")


def fingerprint() -> str:
    """Hash of the code-relevant working tree vs HEAD (excludes notes/state/docs)."""
    spec = ["--", ".", ":(exclude).claude", ":(exclude)docs", ":(exclude)tickets", ":(exclude)PROGRESS.md"]
    try:
        diff = subprocess.run(["git", "diff", "HEAD", *spec], cwd=ROOT, capture_output=True, timeout=20).stdout
        st = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all", *spec],
                            cwd=ROOT, capture_output=True, timeout=20).stdout
    except Exception:
        return "unknown"
    h = hashlib.sha1(diff + st)
    for line in st.decode("utf-8", "replace").splitlines():
        if line.startswith("??"):
            p = ROOT / line[3:].strip().strip('"')
            if p.is_file() and p.stat().st_size < 2_000_000:
                h.update(p.read_bytes())
    return h.hexdigest()[:12]


def worklog_path(tid: str) -> Path:
    return WORKLOG_DIR / f"{tid}.md"


WORKLOG_SKELETON = """# Worklog — {id} {title}

mode-at-start: {mode}
fingerprint: {fp}
updated: {date}

## Sub-steps
- [ ] (break the ticket's scope bullets into steps at ticket start)

## Hint ladder (Own mode; one row per sub-problem; levels L0–L4)
| Sub-problem | Level | Fingerprint at last hint | Date |
|---|---|---|---|

## Open questions

## Failing tests

## Decisions pending (ADR candidates)

## Reviews (/review-mine)
| Date | Fingerprint | Result |
|---|---|---|

## Sessions (date — hours — one line)
- {date} — 0h — ticket started
"""


def ensure_worklog(tid: str, title: str) -> bool:
    p = worklog_path(tid)
    if p.exists():
        return False
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(WORKLOG_SKELETON.format(id=tid, title=title, mode=mode_of(tid), fp=fingerprint(),
                                         date=_dt.date.today().isoformat()), encoding="utf-8")
    return True


def session_hours(tid: str) -> float:
    text = read(worklog_path(tid))
    part = text.split("## Sessions", 1)[1] if "## Sessions" in text else ""
    return sum(float(m.group(1)) for m in re.finditer(r"—\s*(\d+(?:\.\d+)?)\s*h\b", part))


def progress_block(heading: str) -> str:
    text = read(PROGRESS)
    m = re.search(rf"^## {re.escape(heading)}.*?$(.*?)(?=^---\s*$|^## |\Z)", text, re.S | re.M)
    return m.group(1).strip() if m else ""


# --------------------------------------------------------------------------- commands
def cmd_ticket(tid):
    t = tickets().get(tid)
    print(t["raw"] if t else f"ERROR: ticket {tid} not found in {BACKLOG.relative_to(ROOT)}")


def cmd_spec(refs):
    for r in refs:
        sec = spec_section(r)
        if sec is None:
            print(f"ERROR: spec section '{r}' not found\n")
            continue
        print(f"### {sec[0]}\n\n{sec[1]}\n")


def cmd_grep(pattern):
    lines, heads = spec_index()
    rx = re.compile(pattern, re.I)
    hits = 0
    cur = "?"
    hi = 0
    for n, line in enumerate(lines):
        while hi < len(heads) and heads[hi]["line"] <= n:
            cur = heads[hi]["key"] or heads[hi]["title"]
            hi += 1
        if rx.search(line):
            print(f"§{cur} L{n + 1}: {line.strip()[:220]}")
            hits += 1
            if hits >= 40:
                print("... (40 hits shown; narrow the pattern)")
                break
    if not hits:
        print("no matches")


def deps_report(t) -> tuple[str, bool]:
    rows = {r.get("ID"): r for r in board()}
    parts, ok = [], True
    for d in t.get("deps", []):
        st = rows.get(d, {}).get("State", "?")
        parts.append(f"{d} [{st}]")
        ok &= st == "done"
    return (", ".join(parts) or "none"), ok


def cmd_bundle(tid, set_cur):
    tk = tickets()
    t = tk.get(tid)
    if not t:
        print(f"ERROR: ticket '{tid}' not found. Known IDs look like E4-0, E5-9a.")
        return
    row = board_row(tid) or {}
    mode = row.get("Mode", "?")
    deps, deps_ok = deps_report(t)
    created = False
    if set_cur:
        set_current(tid)
        created = ensure_worklog(tid, t["title"])
    out = []
    out.append(f"# Ticket bundle: {tid} — {t['title']}")
    out.append("")
    out.append("## Status (tickets/status.md)")
    scope = row.get("Own scope", "all")
    split = mode == "Own" and scope not in ("", "all", "—")
    out.append(f"- Mode: **{mode}**" + (f" (split; human-owned scope: `{scope}`)" if split else "") +
               f" · Size: {row.get('Size', t.get('size'))} · State: {row.get('State', '?')} · "
               f"Phase: {row.get('Phase', '?')} · Est: {row.get('Est h', '?')} h")
    out.append(f"- Depends on: {deps}" + ("" if deps_ok else "  ⚠ NOT ALL DONE: stop and tell the human."))
    out.append(f"- Expected output style: {SPLIT_STYLE if split else EXPECTED_STYLE.get(mode, '?')}")
    out.append(f"- Mode rules: {SPLIT_RULES.format(scope=scope) if split else MODE_RULES.get(mode, 'unknown mode: ask the human')}")
    if set_cur:
        out.append(f"- Current ticket set to {tid}" + (f"; worklog created at .claude/worklog/{tid}.md" if created else ""))
    out.append("")
    out.append(f"## Ticket (verbatim, tickets/v1-backlog.md L{t['start']}-{t['end']})")
    out.append("")
    out.append(t["raw"])
    out.append("")
    refs, unresolved = resolve_refs(t.get("spec_line", ""))
    out.append("## Cited spec sections (verbatim, docs/spec.md v1.2: the spec wins any conflict)")
    out.append("")
    for r in refs:
        sec = spec_section(r)
        if sec is None:
            unresolved.append(f"§{r} (not found)")
            continue
        out.append(f"### {sec[0]}")
        out.append("")
        out.append(sec[1])
        out.append("")
    if unresolved:
        out.append("## Unresolved references (look up with `python scripts/claude/ctx.py grep <term>`)")
        out.extend(f"- {u}" for u in unresolved)
        out.append("")
    kd = progress_block("Key decisions log")
    if kd:
        out.append("## PROGRESS.md: key decisions (constraints you must respect)")
        out.append(kd)
        out.append("")
    epic = tid.split("-")[0]
    deferred = [l for l in progress_block("Deferred / noticed").splitlines()
                if re.search(rf"{re.escape(tid)}\b|\b{epic}\b|{epic}/|/{epic}\b", l)]
    if deferred:
        out.append("## PROGRESS.md: deferred items touching this ticket/epic")
        out.extend(deferred)
        out.append("")
    out.append("## Required notes (docs/learning/ticket-notes.md)")
    out.append(notes_text(tid))
    out.append("")
    wl = read(worklog_path(tid))
    out.append(f"## Worklog (.claude/worklog/{tid}.md)")
    out.append(wl.strip() if wl else "(none yet)")
    text = "\n".join(out)
    print(text)
    print(f"\n<!-- bundle ≈ {approx_tokens(text)} tokens -->")


def cmd_status(tid):
    if tid:
        r = board_row(tid)
        print(json.dumps(r, ensure_ascii=False) if r else f"ERROR: {tid} not on the board")
        return
    rows = board()
    counts = {}
    for r in rows:
        counts[r.get("State", "?")] = counts.get(r.get("State", "?"), 0) + 1
    print("Board:", ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    cmd_next()


def cmd_next():
    rows = board()
    state = {r.get("ID"): r.get("State") for r in rows}
    tk = tickets()
    for r in rows:
        if r.get("State") in ("todo", "wip"):
            deps = tk.get(r["ID"], {}).get("deps", [])
            if all(state.get(d) == "done" for d in deps):
                print(f"Next: {r['ID']} {r.get('Title', '')} [{r.get('State')}] mode={r.get('Mode')} est={r.get('Est h')}h")
                return
    print("Next: none ready (all done or blocked by deps)")


def cmd_current():
    tid = current()
    if not tid:
        print("No current ticket. Run /ticket <ID>.")
        return
    r = board_row(tid) or {}
    print(f"{tid} — {r.get('Title', '?')} · mode={r.get('Mode', '?')} · state={r.get('State', '?')}")


def cmd_hint_state(sub):
    tid = current()
    if not tid:
        print("No current ticket. /hint needs one: run /ticket <ID> first.")
        return
    mode = mode_of(tid)
    print(f"Current ticket: {tid} · mode: {mode}")
    print(f"Fingerprint now: {fingerprint()}")
    print(f"Sub-problem requested: {sub or '(none given: ask which one, or infer from the last exchange)'}")
    wl = read(worklog_path(tid))
    m = re.search(r"## Hint ladder.*?(?=\n## )", wl, re.S)
    print(m.group(0) if m else "(no hint table in worklog yet)")


def cmd_close_check(tid):
    tid = tid or current()
    if not tid:
        print("No current ticket and no ID given.")
        return
    t = tickets().get(tid)
    if not t:
        print(f"ERROR: {tid} not found")
        return
    row = board_row(tid) or {}
    mode = row.get("Mode", "?")
    fp = fingerprint()
    wl = read(worklog_path(tid))
    reviewed = bool(re.search(rf"\|\s*[\d-]+\s*\|\s*{fp}\s*\|", wl))
    print(f"# Close check: {tid} — {t['title']}")
    print(f"- Mode: {mode} · teach-back questions required: {TEACH_BACK.get(mode, '?')}")
    print(f"- Estimate: {row.get('Est h', '?')} h · hours logged in worklog sessions: {session_hours(tid):g} h")
    print(f"- Fingerprint now: {fp} · /review-mine recorded at this fingerprint: "
          f"{'yes' if reviewed else 'NO'}" + ("" if mode != "Delegate" else " (not required for Delegate)"))
    print(f"- Invariants: {t.get('invariants', '?')}")
    print(f"- Known traps: {t.get('traps', '?')}")
    print("\n## Acceptance criteria (verify each with evidence)")
    print("\n".join(t["ac"]))
    print("\n## Required notes")
    print(notes_text(tid))


def cmd_check_all():
    tk = tickets()
    bad = 0
    for tid, t in tk.items():
        refs, unresolved = resolve_refs(t.get("spec_line", ""))
        size = sum(len(spec_section(r)[1]) for r in refs if spec_section(r)) + len(t["raw"])
        missing = [r for r in refs if spec_section(r) is None]
        flag = " UNRESOLVED: " + " | ".join(unresolved + missing) if (unresolved or missing) else ""
        bad += bool(flag)
        print(f"{tid:6} refs={','.join(refs):40} ≈{size // 4:5} tok{flag}")
    print(f"\n{len(tk)} tickets parsed; {bad} with unresolved references.")


def main(argv):
    if not argv:
        print(__doc__)
        return 0
    cmd, args = argv[0], argv[1:]
    flags = {a for a in args if a.startswith("--")}
    args = [a for a in args if not a.startswith("--")]
    tid = norm_id(args[0]) if args else None
    if cmd == "ticket" and tid:
        cmd_ticket(tid)
    elif cmd == "spec" and args:
        cmd_spec(args)
    elif cmd == "grep" and args:
        cmd_grep(" ".join(args))
    elif cmd == "bundle":
        if not tid:
            print("ERROR: usage /ticket <ID>, e.g. /ticket E4-0")
        else:
            cmd_bundle(tid, "--set-current" in flags)
    elif cmd == "status":
        cmd_status(tid)
    elif cmd == "next":
        cmd_next()
    elif cmd == "notes":
        t = tid or current()
        print(notes_text(t) if t else "No current ticket and no ID given.")
    elif cmd == "current":
        cmd_current()
    elif cmd == "worklog":
        t = tid or current()
        print((read(worklog_path(t)) or f"(no worklog for {t})") if t else "No current ticket.")
    elif cmd == "fingerprint":
        print(fingerprint())
    elif cmd == "hint-state":
        cmd_hint_state(" ".join(args))
    elif cmd == "close-check":
        cmd_close_check(tid)
    elif cmd == "check-all":
        cmd_check_all()
    else:
        print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
