---
name: notes
description: Show which concept notes, tech pages and ADR the current ticket requires and whether each exists (Notion, or the local inbox fallback). Scaffold missing ones, write a note by interviewing the human in chat (their words, grammar-polished only), check a note or ADR against its template, or sync inbox notes to Notion.
argument-hint: "[status | scaffold <slug> | write <slug> | check <slug or ADR file> | sync]"
disable-model-invocation: true
---

Current ticket: !`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" current`

!`python "${CLAUDE_PROJECT_DIR}/scripts/claude/ctx.py" notes`

Notion config (`docs/learning/notion.json`): !`cat "${CLAUDE_PROJECT_DIR}/docs/learning/notion.json" 2>/dev/null || echo "(missing)"`
Inbox fallback (`docs/learning/inbox/`): !`ls "${CLAUDE_PROJECT_DIR}/docs/learning/inbox" 2>/dev/null || echo "(empty)"`

---

Rules: `docs/learning/ticket-notes.md`. Templates: `docs/learning/templates/`. **Every idea in a note is the
human's.** `scaffold` creates only the skeleton, properties and the "Where it shows up" row (real file:line found
by grepping the code). `write` is the interview that fills the rest: the human answers in chat in their own
words, and you may fix only grammar, spelling, word choice and structure. Never add a fact, example, analogy or
explanation the human did not say. ADRs are not written by you (use `check`).

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

## `write <slug>`
Interview the human and write the note from their answers. Concept notes and tech pages only.
1. **Get the note.** Find it (Notion by Slug, or the inbox file). Missing → run `scaffold` first. Fetch it, read the
   matching template, and list the sections still holding template prompts (angle-bracket placeholders). Work in
   this order: **lite sections first**: tech page: "What problem it solves here", "Mental model", this ticket's
   "How reviewbot uses it" cells (Value, Why), one interview question + answer; concept note: "In my own words",
   "Where it shows up" prose, one interview question + answer. Then, only if the human wants `understood`, the
   remaining sections. Tell the human the plan in one line ("5 questions to lite").
2. **Ask one question at a time**, grounded in this ticket (name the file:line or spec § to look at, never the
   answer). Wait for the human's answer in chat. If they have forgotten a term, point to where to look (spec §,
   file:line, their earlier answer) and let them try again; after two failed tries give a one-line conceptual
   nudge in prose, still not a sentence they can paste.
3. **Fact-check the answer** against the spec (`ctx.py spec <§>` / `ctx.py grep <term>`), the code, and the
   contracts it names (spec wins). Wrong, vague or missing → ask a follow-up question that makes them find it
   (same style as `check`: `[wrong|vague|missing] <question>`), at most two rounds. Do not write that part.
   Still unresolved → leave the section as the template prompt and keep the status `draft`. Never fill the gap.
4. **Polish, then write.** Fix grammar, spelling, punctuation, word choice, and turn chat fragments into
   sentences or table cells. Keep their meaning, first person, vocabulary level and amount of detail; do not
   merge in your own phrasing of ideas. If a fix would change what they meant, ask. Write the section to the
   note (`update-page`, targeted `update_content`; batch the lite sections of one answer round into one write to
   keep approvals few), then show the exact text you wrote in one short block and say: "Written to <section>.
   Tell me if I shifted your meaning." Apply their correction the same way.
5. **Decide done.** When every lite requirement is filled from the human's own answers, nothing is left wrong or
   vague, and "Where it shows up" names this ticket ID with a real file:line (open the file and look), set
   Status `lite` and Last reviewed to today, and say "Notes writing done: `<slug>` is `lite`". Set `understood`
   only when every template section is filled and verified. If the bar is not met, say which sections are missing
   and stay `draft`. Do not mark done to be nice.
6. Notion unreachable → write `docs/learning/inbox/<slug>.md` instead (`notion: pending`) and say so.

## `check <slug>` (or `check docs/adr/NNNN-*.md`)
Review a note or ADR **the human wrote**. **Read-only: never edit, append to or rewrite it**, not even a typo
(use `write` to have a note written from interview answers).
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
   End with: "Status claim `<lite|understood>`: supported / not yet supported". In `check` the human changes
   the status themselves (only `write` lets you set it).

## `sync`
For each inbox file with `notion: pending`, create the Notion page with its content, then set
`notion: synced <url>` in the file. Report what was synced.
