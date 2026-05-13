# Review a Pull Request

Given a PR (URL, number, or the current branch), perform a thorough review by gathering all available context before analyzing the code.

`$ARGUMENTS` - a PR URL, PR number, or leave blank to use the current branch.

## Step 1: Sync local code

Before touching local files, ensure the worktree reflects the latest remote state. Follow the codebase freshness rules in AGENTS.md:

- Run `git fetch`.
- `git status` - check branch name, clean/dirty, ahead/behind.
- Clean and behind: `git pull --ff-only`.
- Dirty tree: never pull. Analyze as-is and warn the user.
- If on main/master instead of the PR branch: switch to the PR branch (`gh pr checkout <number>`) only with user confirmation.

Do not use local code for analysis until this step is complete.

## Step 2: Gather PR details

- `gh pr view` for title, body, linked issues, metadata.
- `gh pr diff` for the full diff.
- Note Jira ticket IDs in the title, body, or branch name (pattern: `PROJ-\d+`).

## Step 3: Gather Jira context

For each Jira ticket found - use jira CLI.
Use `json` template, pipe to `jq` if necessary - e.g., `jira view PROJ-12345 --template json`.
The json template fetches comments, subtasks and linked items.
Request more info from Jira when needed.

## Step 4: Search Slack for context

Search Slack for related discussions. Focus on the default workspace, check others if the PR touches open-source components.

## Step 5: Optional extras

- Bug fixes: search Splunk for relevant logs.
- Fetch git-blame for changed functions, find related PRs (recursively gather context for each). Build historical context.

## Step 6: Review the code

With all gathered context, review the PR diff. Focus on:

- Correctness: Does the code do what the ticket asks for?
- Edge cases: Are there unhandled scenarios?
- Style: Does it follow the project's conventions (see AGENTS.md)?
- Testing: Are changes adequately tested?
- Dependencies: Any risky changes to shared code or configs?

## Step 7: Summarize

Provide a concise review with:

1. A one-paragraph summary of what the PR does and why.
2. Concerns or suggestions, ranked by severity.
   - Code talking points as clickable GitHub PR diff anchors:
     `https://github.com/ORG/REPO/pull/N/files#diff-<HASH>R<START>-R<END>`
     where HASH is `printf "path/to/file.ext" | sha256sum` (no a/ or b/ prefix). R<n> is the new-side line, L<n> the old-side line.
   - Slack threads with links for easier discovery.
   - Jira tickets shown as-is (no links).
   - Other PRs and Issues in bug-reference format - ORG/REPO#42.
   - CI workflows, Splunk logs, metrics - all easily navigable.
   - Local code pointers: /path/to/file.ext:1-42
   - Inlined diffs where they help reasoning about changes.
3. Questions for the author if context is unclear.
4. Jira/PR/Slack attachments or linked resources that could not be accessed, per `# Context Completeness` in AGENTS.md.
