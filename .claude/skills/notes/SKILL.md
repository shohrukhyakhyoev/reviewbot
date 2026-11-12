---
name: notes
description: Show which concept notes, tech pages and ADR the current ticket requires and whether each exists (Notion, or the local inbox fallback). Scaffold missing ones, check a note the human wrote against its template, or sync inbox notes to Notion.
argument-hint: "[status | scaffold <slug> | check <slug or ADR file> | sync]"
disable-model-invocation: true
---

Current ticket: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" current`

!`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" notes`

Notion config (`docs/learning/notion.json`): !`cat "${CLAUDE_PROJECT_DIR}/docs/learning/notion.json" 2>/dev/null || echo "(missing)"`
Inbox fallback (`docs/learning/inbox/`): !`ls "${CLAUDE_PROJECT_DIR}/docs/learning/inbox" 2>/dev/null || echo "(empty)"`

---

Rules: `docs/learning/ticket-notes.md`. Templates: `docs/learning/templates/`. **The human writes every
explanation.** You may only create the page/file skeleton and fill properties plus the "Where it shows
up" row for this ticket, with real file:line found by grepping the code.

## Where to look
- **Notion first:** load the Notion tools with ToolSearch (`notion query data sources`, `notion fetch`,
  `notion create pages`, `notion update page`). Query the `concepts_data_source` / `tech_data_source`
  from `notion.json` by the **Slug** property (e.g. rows mode filtered on Slug). Read **Status** and
  **Tickets** from the properties, then fetch the page to confirm "Where it shows up" mentions the ticket.
  New pages go into those data sources with Name, Slug, Status=draft, Roadmap area/Version and Tickets set.
- **Fallback:** if `notion.json` has null IDs, the tools are missing, or a call errors, use
  `docs/learning/inbox/<slug>.md` (frontmatter `notion: pending`). Say that the fallback was used.

## `status` (default)
Print one table: note · kind · exists? · status (`draft`/`lite`/`understood`) · mentions this ticket? ·
location. A note passes `/close` only with status lite or understood **and** a mention of the ticket ID.
For an ADR row: `required` → a `docs/adr/*.md` file naming the ticket; `if: …` → ask whether that choice
came up.

## `scaffold <slug>`
Create the Notion page (or inbox file) from the matching template with status `draft`. Fill only the
frontmatter/properties and one "Where it shows up" row. Leave every other section with its template
prompt. If the note already exists, append only the new "Where it shows up" row.

## `check <slug>` (or `check docs/adr/NNNN-*.md`)
Review a note **the human wrote**. **Read-only: never edit, append to or rewrite the note**, not even a typo.
1. **Get the text.** Notion page by Slug (fetch it), or the inbox file, or the ADR file. If none is reachable,
   ask the human to paste it.
2. **Template conformance.** Compare it against `docs/learning/templates/concept-note.md`, `tech-page.md` or
   `adr.md`:
   - **lite:** "In my own words", "Where it shows up" (with this ticket), and one interview question with an answer.
   - **understood:** every section.
   - **ADR:** at least 2 options with pros and cons, a decision, consequences, a relation to the spec, and a
     learning point.
   - Also flag a status that claims more than the content delivers.
3. **Facts.** Check each technical claim against the spec (`ctx.py spec <§>` / `ctx.py grep <term>`), the code,
   and the API contracts the note names. Flag claims that are wrong, imprecise or contradict the spec (spec
   wins), e.g. "Semaphore.release() blocks", or "committed offset = last processed".
4. **Link to the code.** Every "Where it shows up" row must name a real file:line that exists *now* and does what
   the row says (open the file and look). It must include this ticket's ID and the invariant or spec § it serves.
5. **Report as questions, never as corrections.** One line per problem:
   `[missing|wrong|vague|unlinked] <section>: <question that makes them find it>`. Examples:
   "Your Mechanism says the permit is released on success only. What happens to it in E4-0 when markComplete
   hits an unregistered partition?" or "Where in your code does this show up? The row has no file:line."
   Don't quote a correct version, and don't write replacement sentences.
   End with: "Status claim `<lite|understood>`: supported / not yet supported". The human changes the status
   themselves.

## `sync`
For each inbox file with `notion: pending`, create the Notion page with its content, then set
`notion: synced <url>` in the file. Report what was synced.
