# Sprint retro

Reconstruct one sprint's story - what landed, what slipped, and who did what with what friction - for a retrospective, or as a mid-sprint glimpse of where a team stands. The two-week window is the sprint itself; carryover from earlier sprints is surfaced explicitly.

This is the third command sharing the `jira-board` helper, alongside `find-backlog-work.md` (pickable work) and `sprint-planning-prep.md` (walk the board for planning). Same board resolution, same cache, same ticket projection - a different lens on the same machinery.

The work is deliberately sandwiched. A deterministic layer (the helper) decides which tickets belong to the sprint and classifies them exactly as Jira's own Sprint Report does, then attaches per-issue facts pulled straight from each changelog - carryover, status timeline, rework, handoffs, flags - plus a per-person rollup and the velocity trend. Nothing there is guessed. The stochastic layer is this command's job: read the comments, PR threads, Slack, and your own notes the helper points you at, and judge accomplishments, struggles, and what to do better. Do not re-derive the deterministic parts by hand, and do not fabricate the judgment parts.

`$ARGUMENTS` - how to choose the board and sprint, plus optional depth knobs:

- A board id (e.g. `3018`), a board name substring (e.g. `Pipeline`), or a project key (e.g. `SAC`) to choose the board.
- `sprint:` one of `active` (default), `last` (most recent closed - use this when the retro happens after the next sprint already started), or an explicit sprint id.
- `max:` how many tickets to deep-dive with PRs/Slack (default 10). Spillover and punted tickets are always dug into regardless of this cap.
- `focus:` a person's name to center the write-up on one teammate.
- Blank - default board, active sprint; do not prompt.

Default board: when `$ARGUMENTS` names no board, the helper resolves one (first hit wins): `BACKLOG_BOARD` env -> `:qlik-verify :jira :board` in `local-config.edn.gpg` -> `3018` fallback, cached under `~/.cache/jira-board/`. Pass a board id once to switch teams.

All discovery here is read-only. The `jira` CLI is go-jira with keyring auth: `jira req <path>` does a GET; the helper makes every Jira call for Step 1.

## Step 1: Gather the sprint (one helper call)

Run the shared helper with `--retro`:

```
bb ~/.config/eca/death-contraptions/scripts/jira-board/jira-board.bb [<board-id>] --retro [--sprint active|last|<id>] > /tmp/sprint-retro.edn
```

It makes several Jira and Greenhopper calls, so run it once and read from the saved file - do not re-run it to re-slice. It resolves the board (reusing the cached static facts), selects the sprint, pulls Jira's sprint report plus a by-key fetch with changelog and comments, and returns one EDN map:

- `:board` - `{:board-id :name :type :story-points-field :columns ...}`.
- `:sprint` - `{:id :name :goal :state :start :end :complete-date :days-total :days-elapsed :days-remaining :ended?}`. `:state` is `active` mid-sprint or `closed` after; `:ended?` is true once now passes `:end` (an active sprint whose time is spent - a retro run right before formal close). The day budget frames pacing.
- `:velocity` - the last ~6 sprints as `{:sprint :estimated :completed}` (points committed vs delivered), for trend.
- `:summary` - headline counts (`:completed-count :spillover-count :punted-count :added-during-count :carryover-count :completed-elsewhere-count`), `:pointed-count` (how many committed tickets carry an estimate - read the points burn against it), and `:points {:committed :completed :spillover}`.
- `:buckets` - the four key lists: `:completed`, `:not-completed` (spillover), `:punted` (removed mid-sprint), `:completed-elsewhere`.
- `:focus` - the deterministic deep-dive shortlist, keyed by reason (ticket keys): `:spillover`, `:punted`, `:friction` (completed but reworked, flagged, or reassigned), `:carryover-wins` (completed after migrating in), `:headline` (pointed completions, biggest first). Computed for you - start here in Steps 3-4, do not re-derive it.
- `:candidates` - every sprint ticket, completed first, each projected the same as the other commands plus retro signals: `{:key :type :priority :status :summary :points :assignee :epic :epic-summary :bucket :carryover? :carryover-from :added-during? :resolved :timeline [{:at :by :from :to}] :rework :handoffs [{:from :to :at}] :flags :description :comments {:count :items [{:who :when :body}]}}`.
- `:people` - per-assignee rollup: `{:name :completed :completed-pts :spillover :spillover-pts :carryover :added :rework :handoffs :flags}`, sorted by completed points.

Board argument: blank or numeric passes straight through (blank uses the default; do not prompt). For a NAME or PROJECT KEY, resolve to an id first (`jira req '/rest/agile/1.0/board?name=<urlencoded>'` or `?projectKeyOrId=<key>`; list `id / name / type` and ask if several match), then pass the id.

Work from this map - never re-fetch or re-classify the sprint by hand.

## Step 2: Read the deterministic frame

Orient before digging. Straight from the map, no interpretation yet:

- Delivery: `:summary` completed vs spillover vs punted, points committed vs completed, against the `:velocity` trend (is this sprint above or below the team's norm?). Read the points against `:pointed-count`: if few committed tickets were estimated, the points burn understates delivery - say so, do not call it a miss.
- Scope stability: `:added-during-count` (work injected after planning) and `:punted` (work pulled out) together tell how much the plan moved under the team's feet.
- Carryover: `:carryover-count` and the per-ticket `:carryover-from` - how much of this sprint was really last sprint's unfinished work migrating in. High carryover reframes "what we accomplished".
- People: skim `:people` - who carried the delivery, who is buried in spillover, whose tickets show `:rework`/`:handoffs`/`:flags`.
- Pacing (`:sprint :state`/`:ended?`): `closed`, or `active` with `:ended?` true (time spent, not yet formally closed) -> the retro proper; near that boundary treat the completed/spillover split as provisional (a ticket may resolve a day late). `active` with time left -> a mid-flight glimpse, judge on-track vs at-risk using `:days-remaining` against open work.

## Step 3: Choose the focus set

The helper already selected it: `:focus` groups the tickets worth stochastic digging by reason, so you never re-derive it from `:candidates`. Cap the optional groups by `max:`, but always dig into `:spillover` and `:punted` in full.

- `:focus :spillover` - every unfinished ticket: why it slipped (near-done vs untouched, blocked, re-scoped).
- `:focus :punted` - every ticket pulled out mid-sprint: why it left (deprioritized, blocked, wrong estimate).
- `:focus :friction` - completed but reworked, flagged, or handed between people: delivered, with a story.
- `:focus :carryover-wins` - completed after migrating in from an earlier sprint: the long-haul lands.
- `:focus :headline` - the pointed completions, biggest first, to seed the accomplishments narrative; also scan `:buckets :completed` for unpointed wins and `:epic-summary` for the epics that moved.
- Scope pressure: also flag `:added-during? true` tickets (from `:candidates`) that displaced planned work.

## Step 4: Deep-dive the stochastic sources

For each focus ticket, gather what the numbers cannot tell you. This is the non-deterministic core - read and judge.

- Jira comments: the `:comments` items are already in the map (clipped). When `:count` exceeds what is shown or a thread looks pivotal, pull the full ticket: `jira view <KEY> --template json`. Read for the actual struggle - blockers, disagreements, scope changes, waiting on other teams.
- Related tickets: follow the ticket's links and epic siblings (from the projection and `jira view`); a spillover often traces to a dependency or a sibling that moved first.
- Your own notes (org-roam-mcp): your first-person record, and often the only place a reflection was written down. Bound it to the deterministic sprint window (`:sprint` `:start`..`:end`); skip if org-roam-mcp is not connected.
  - Sprint log: `notes-read` your monthly work-notes for the window - by title (e.g. `"July 2026 work notes"`) or path (`~/Sync/org/daily/YYYY-MM-work-notes.org`; two files if the sprint straddles a month) - and scan the dated day-headings inside the window for standups, decisions, and gripes.
  - Per-ticket + theme: `notes-search` on each focus ticket key and on service/epic themes, filtered to work notes (`tags: ["work"]` / `["work-notes"]`) so personal-life journal entries stay out. Your ticket notes often carry `:links-to` the exact Slack thread and PR - harvest those to seed the two bullets below; `notes-search-related` from a strong hit pulls adjacent reflections.
  - Reflections: look for post-mortems, retro/1:1 prep, and "lessons" entries in the window - already-digested what-went-well / what-to-improve in your own words.
- Pull requests: find and read the code conversation.
  - Discover: `gh search prs "<KEY>" --owner qlik-trial --owner singer-io --owner stitchdata --json number,title,state,url,repository,closedAt,commentsCount,author`. (`gh search prs` has no `--state all` - omit it to search all states - and no `mergedAt` field; get merge detail from `gh pr view` below.)
  - Read the review: `gh pr view <n> --repo ORG/REPO --json title,state,reviewDecision,mergedAt,comments,reviews`. Judge how review comments were addressed or left hanging, how many review rounds, whether it merged clean or churned. This is where "how struggles were handled" lives.
- Slack: use the Slack MCP, not a hand-rolled search. `slack-search` with Slack operators - the ticket key, then service/feature keywords with `from:<assignee>` and `after:<sprint-start>` (also `in:<channel>`, `has:link`) - then `slack-fetch-thread` on a hit's permalink to read the whole exchange (it resolves user mentions). Default workspace unless the work touches an open-source component, then check the relevant one. The MCP remaps Enterprise Grid permalink domains, so `slack-fetch-thread` takes a search-result link directly; if a fetch still fails on the host, retry with the workspace's real `*.enterprise.slack.com` host. This is where the real problem-solving or frustration outside Jira surfaces.

State up front which sources were searched and which came back empty, so the retro's evidence base is clear.

## Step 5: Synthesize the retro

Write the retrospective. Ground every claim in a ticket, comment, PR, or thread - this is a mirror for the team, not a vibe.

- What went well: shipped work that mattered (name the epic/feature and who drove it), clean fast merges, good handling of a hard problem, help given across the team.
- What to improve: spillover and its root cause (under-estimation, blocked on another team, scope creep, unclear requirements), punts, rework loops, long-lived carryover, impediments that sat too long, review threads that stalled.
- People (the heart of it): for each active teammate - what they did, what they struggled with, and how it resolved. Credit quiet load-bearing work (reviews, unblocking others, carryover finally landed). Keep it constructive and specific; describe behavior and outcomes, not character. Honor `focus:` when given.
- Your own read (from your notes): fold in the reflections you already wrote - what you were proud of, what frustrated you, lessons noted mid-sprint - and your standup observations of teammates. Attribute them as your perspective, one input among the evidence, and let them raise questions for the room rather than stand as verdicts.
- Process signals: scope stability (added vs punted), commitment accuracy (committed vs completed points vs velocity norm), carryover trend, where work got stuck (status the timeline lingered in, flagged durations).
- Next-sprint hooks: the concrete carryover to expect, unresolved blockers to track, and one or two changes worth trying.

Mid-sprint variant (`:sprint :state` is `active`): drop the retro verdict framing. Report current standing instead - on track vs at risk given `:days-remaining`, what is still open and who owns it, which tickets are stuck or flagged right now, and scope that has shifted since planning.

## Step 6: Output conventions

Match the other commands. Jira keys as-is, no links. Other PRs/Issues in `ORG/REPO#42` form. Local code pointers as `/path/to/file.ext:1-42`. Slack threads as links for discovery. PR review talking points as clickable diff anchors: `https://github.com/ORG/REPO/pull/N/files#diff-<HASH>R<START>-R<END>` where HASH is `printf "path/to/file.ext" | sha256sum` (no `a/`|`b/` prefix), `R<n>` new-side, `L<n>` old-side.

Per `# Context Completeness` in AGENTS.md: list any Jira/PR/Slack/notes resource you could not access (org-roam-mcp offline included), state the retro is incomplete for it, and offer to take it manually.
