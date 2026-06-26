# Find backlog work

Surface unassigned, not-started tickets from a board's backlog (or active sprint), grouped by relatedness, and recommend the easiest, lowest-collision work to pick up. Optimized for "what can I start right now without colliding with a teammate".

`$ARGUMENTS` - how to choose the board, plus optional filters:

- A board id (e.g. `3018`), a board name substring (e.g. `Pipeline`), or a project key (e.g. `SAC`) to choose among that project's boards.
- `scope:` one of `backlog` (default), `sprint` (active sprint), or `both`.
- `max:` how many candidates to evaluate deeply (default 8).
- `check-prs` - also search GitHub for open PRs referencing each shortlisted ticket (slower; strongest "already being worked" signal).
- Blank - use the default board; do not prompt.

Default board: when `$ARGUMENTS` names no board, the helper resolves one (first hit wins): `BACKLOG_BOARD` env -> `:qlik-verify :jira :board` in `local-config.edn.gpg` -> `3018` fallback. To switch teams: pass the board once, `export BACKLOG_BOARD=<id>`, or change `:qlik-verify :jira :board` in the encrypted config.

All discovery here is read-only. The `jira` CLI is go-jira with keyring auth: `jira req <path>` does a GET against the configured instance; URL-encode spaces in query strings as `%20`.

## Step 1: Gather board + candidates (one helper call)

Run the helper - it resolves the board (caching the static board facts under `~/.cache/find-backlog-work/`), pulls the backlog (or sprint) in rank order, and filters to pickable, all in code so nothing is hand-written at runtime:

```
bb /Users/ryl/GitHub/agzam/death-contraptions/scripts/find-backlog/find-backlog.bb [<board-id>] [--scope backlog|sprint|both] [--refresh]
```

It prints one EDN map:

- `:board` - `{:board-id :name :type :filter-id :story-points-field :blocked-status-ids :columns}`.
- `:total` / `:pickable` - issue counts before / after filtering.
- `:candidates` - the pickable tickets (unassigned, statusCategory `new`, not in a hold lane) in board rank order. Each: `{:key :rank :type :priority :status :components :labels :parent :links [{:rel :key :cat}] :created :updated :points :summary}`. `:rank` is the 1-based position in the full board order (its priority); `:links[].cat` is the linked issue's status category.

Board argument handling: pass a blank or numeric id straight through (blank uses the default; do not prompt). For a board NAME or PROJECT KEY, resolve it to an id first (`jira req '/rest/agile/1.0/board?name=<urlencoded>'` or `'/rest/agile/1.0/board?projectKeyOrId=<key>'`; if several match, list `id / name / type` and ask which), then pass the id. Use `--refresh` only after a board is reconfigured.

Work from `:candidates` for everything below - never re-fetch or re-filter the backlog by hand.

## Step 2: Cluster by relatedness

Group the candidates so related work stays together - you do not want to start one ticket only to have a teammate start its sibling:

- Strong: shared `:parent` (epic); `:links` among the set or to issues outside it.
- Secondary: shared `:components`, shared `:labels`, summary keyword overlap (service / feature / error tokens, e.g. a `[service]` prefix).

Collision lens: real conflict is same-service / same-repo, not merely same-epic. Two tickets in one epic but different services do not collide; two in the same service likely touch the same files. Form groups on the service/repo axis and order groups by their top ticket's `:rank`.

## Step 3: Assess "already being worked" (anti-collision)

For the shortlist:

- Links: each candidate's `:links` already carries the linked issue's `:cat` - flag any candidate linked to an `indeterminate` (in-progress) issue.
- Sibling heat: for each cluster's epic/component, check siblings NOT in `:candidates` - `jira req '/rest/api/2/search/jql?fields=status,assignee&jql=<parent%20=%20EPIC%20or%20Epic%20Link%20=%20EPIC>'` - and report "X of N siblings already assigned or in progress". A hot area raises collision risk for the whole cluster.
- Open PRs (only when `check-prs` is passed): reuse the `deploy-check` trick - `gh search prs "<KEY>" --json number,title,url,state` across the relevant orgs (`qlik-trial`, `singer-io`, `stitchdata`). An open or merged PR mentioning the key is the strongest signal the ticket is already taken, even when Jira does not show it.

## Step 4: Shallow evaluation

Fetch descriptions for the top `max` candidates in one call. The legacy `/rest/api/2/search` is retired - use the enhanced endpoint:

`jira req '/rest/api/2/search/jql?fields=summary,description,issuetype,priority,parent&jql=key%20in%20(KEY1,KEY2,...)'`, truncating long descriptions.

For each candidate report:

- a one-line, plain-language summary of the actual problem,
- an effort read (S / M / L), and affected files if the ticket names them,
- self-contained vs part-of-a-cluster,
- overlap status (links / sibling heat / PRs),
- age, flagging anything untouched for many months as "regroom before pickup".

## Step 5: Recommend

Output candidates grouped by cluster, rank-ordered, each line carrying: key, type, priority, age, effort, collision/overlap, and a one-line recommendation. Then call out explicitly:

- the best single low-risk pickup (self-contained, small, no entangling links or PRs),
- the best small same-service cluster to claim whole (so no sibling is left for a teammate),
- any ticket that looks small but belongs to a larger latent unit of work - warn against starting it solo.

Show Jira keys as-is, no links (per AGENTS.md and `review-pr.md`). Local code pointers as `/path/to/file.ext:1-42`; other PRs/issues in `ORG/REPO#42` form.

