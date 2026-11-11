---
name: notes
description: Show which concept notes, tech pages and ADR the current ticket requires and whether each exists (Notion, or the local inbox fallback). Scaffold missing ones, or sync inbox notes to Notion.
argument-hint: "[status | scaffold <slug> | sync]"
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

## `sync`
For each inbox file with `notion: pending`, create the Notion page with its content, then set
`notion: synced <url>` in the file. Report what was synced.
