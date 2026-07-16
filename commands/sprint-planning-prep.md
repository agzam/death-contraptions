# Sprint planning prep

Walk a team's backlog top-to-bottom in board rank order and give a plain-language, one-paragraph explainer for every ticket, plus a hedged point guess. Optimized for "in the planning meeting, someone points at any row and asks 'what about this one?' and you can answer in one breath".

This is the mirror of `find-backlog-work.md`: that command filters to unassigned, not-started, low-collision work; this one keeps everything - the active sprint's unfinished tickets (carryover, Validation especially) plus the whole backlog in board order - because planning discusses every row.

`$ARGUMENTS`:

- A board id (e.g. `3018`), a board name substring (e.g. `Pipeline`), or a project key (e.g. `SAC`) to choose the board.
- `max:` - explain only the top N tickets in full; list the rest as a one-line tail (default: no cap, everything).
- Blank - use the default board, explain everything, do not prompt.

Default board: when `$ARGUMENTS` names no board, the helper resolves one (first hit wins): `BACKLOG_BOARD` env -> `:qlik-verify :jira :board` in `local-config.edn.gpg` -> `3018` fallback, and caches it. Pass a board id once to switch teams.

All discovery here is read-only, and the helper makes every Jira call - this command shells out once and renders. The helper resolves and caches the board's story-points field, so nothing is hardcoded.

Keep the response to the rundown itself. Do not narrate tool calls, do not fetch point history, and do not deliberate on estimation method - just produce the list.

## Step 1: Gather board + tickets (one helper call)

Run the shared helper with `--plan`:

```
bb /Users/ryl/GitHub/agzam/death-contraptions/scripts/find-backlog/find-backlog.bb [<board-id>] --plan
```

It resolves the board (reusing the static facts - name, story-points field, columns - cached under `~/.cache/find-backlog-work/`, the same cache `find-backlog-work` fills, so repeat runs skip the board lookup), then returns the active sprint's unfinished tickets followed by the whole backlog, each projected. One EDN map:

- `:board` - `{:board-id :name :type :filter-id :story-points-field :blocked-status-ids :columns}`.
- `:total` - ticket count; `:sprint` - how many are the active-sprint carryover (listed first).
- `:candidates` - every ticket in walk order: the active sprint's unfinished work first (In Progress, Code Review, Validation, On Hold, To Do; Done and Rejected dropped), then the backlog in board rank order. Each: `{:key :rank :type :priority :status :sprint :assignee :parent :points :created :updated :summary :description}`. `:sprint` marks a current-sprint ticket; `:description` is clipped.

Board argument: blank or numeric passes straight through (blank uses the default; do not prompt). For a NAME or PROJECT KEY, resolve to an id first (`jira req '/rest/agile/1.0/board?name=<urlencoded>'` or `?projectKeyOrId=<key>`; list `id / name / type` and ask if several match), then pass the id.

Work from `:candidates` in the order returned - never re-fetch or re-sort.

## Step 2: Explain each ticket (walk order, three lines each)

Go through `:candidates` in order - sprint carryover first, then backlog. For each ticket emit exactly three lines, then a blank line:

```
# KEY - <summary verbatim from Jira>
<Type>[, <Priority>], <Status>[, current sprint][, <N> pts]. [Owner <Name>.]
<plain explanation, ending with the size clause from Step 3>
```

- Line 1 is a markdown H1 (`# ` prefix): the key, space-hyphen-space, and the ticket's summary exactly as it reads in Jira. Being a heading, it converts cleanly to an org `*` headline, and it makes a ticket findable by its words, not just its number. Do not paraphrase the summary.
- Line 2, metadata: type always; priority only when `Blocker`/`Highest` (it arrives as `N - Label`; show the label only); status always; `current sprint` when `:sprint` is true; `N pts` when pointed; end with `Owner <name>.` only when assigned.
- Line 3, the explanation: 2-4 plain sentences from the summary and description. Say what it actually is (translate jargon: tap = connector; target-qlik = writes extracted data to S3; menagerie / bobbin / orchestrator = name the service's role), the essential why, and any dependency (another team, a release handshake, blocked creds). Ignore stack traces, image refs, and link dumps. If the description is empty or too vague, say so - never invent scope.

No bullet numbers. Keys as-is, no links. Local code pointers as `/path/to/file.ext:1-42`; other PRs/issues in `ORG/REPO#42` form.

## Step 3: Size it (one short clause, no ceremony)

End the explanation with a brief, hedged size - a seed for the debate, not a verdict. A few words, carrying the one factor that drives it. Never write a preamble, never narrate, never discuss estimation methodology.

Ladder (Fibonacci): 1 = one place, known pattern; 2-3 = single service, understood; 5 = crosses services, or unknown root cause, or design unknowns; 8 = open-ended / needs splitting (say "split first"). No number when the description is empty or vague (say "groom first") or when it is blocked on external creds / another team (say "blocked").

Voice: "Small, ~2." / "Root cause unknown - hold at 5." / "Too big as one card, split first."

Already-pointed tickets carry `N pts` on line 2; keep that number and add a few words only if it looks off. Do not fetch point history or discuss who set it.

Do not size tickets that are Done, In Progress, Code Review, or Validation - they are finished or in flight. Explain them briefly (Validation especially, as carryover risk) and give no number.

## Step 4: Close with a short orientation note

A few lines, not a table:

- the active-sprint carryover to watch, Validation first (may or may not land this sprint).
- which backlog band is the live focus (top) versus the older or parked tail.
- obvious clusters that move together (shared epic via `parent`, shared service, or theme), so the room can batch them.
- tickets that can't be pointed yet, by key: empty/vague (groom) and blocked-on-external (track).
