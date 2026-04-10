---
name: review-pr
description: Review a pull request by gathering full context - the PR diff, linked Jira tickets (with comments and subtasks), and relevant Slack discussions.
---

# Review a Pull Request

Given a PR (URL, number, or the current branch), perform a thorough review by gathering all available context before analyzing the code.

## Arguments

`$ARGUMENTS` - a PR URL, PR number, or leave blank to use the current branch.

## Step 1: Gather PR details

- Use `gh pr view` to get the PR title, body, linked issues, and metadata.
- Use `gh pr diff` to get the full diff.
- Note any Jira ticket IDs mentioned in the title, body, or branch name (pattern: `SAC-\d+` or similar).
- Find the local code of relevance.
- Make sure there are no staged changes; code is in main/master; call for user's attention otherwise.
- Fetch and pull the latest.

## Step 2: Gather Jira context

For Jira tickets (typically look like 'PROJ-12345') - use jira CLI.
Use `json` template, pipe it to `jq` if necessary - e.g., `jira view PROJ-12345 --template json`, 
Using json template fetches comments, subtasks and linked items.
Request more info from Jira when needed.

## Step 3: Search Slack for context
- Search Slack to find related discussions. - Focus on the default Slack workspace, check others if the PR touches open-source components.

## Step 4: Optional

- When reviewing a bug fix, identifying logs may provide additional insight. Search Splunk for the relevant logs.
- Fetch git-blame data for the functions changed, find related PRs (recursively iterate from step #1 for each). Try to gain historical context.

## Step 5: Review the code

With all gathered context, review the PR diff. Focus on:

- Correctness: Does the code do what the ticket asks for?
- Edge cases: Are there unhandled scenarios?
- Style: Does it follow the project's conventions (see AGENTS.md)?
- Testing: Are changes adequately tested?
- Dependencies: Any risky changes to shared code or configs?

## Step 6: Summarize

Provide a concise review with:

1. A one-paragraph summary of what the PR does and why.
2. Any concerns or suggestions, ranked by severity.
 - Make code talking points clickable via GitHub's PR diff anchors:
   https://github.com/ORG/REPO/pull/N/files#diff-<HASH>R<START>-R<END>
   where HASH is `printf "path/to/file.ext" | sha256sum` (no a/ or b/ prefix). R<n> is the new-side line, L<n> the old-side line.
 - Any Slack threads mentioned should have links for easier discovery
 - Jira tickets should be shown as is (no links)
 - Other PRs and Issues must show in bug-reference format -i.e., ORG/REPO#42
 - Any CI workflows, Splunk logs, metrics, etc. - all should be easily navigable.
 - When the relevant code present locally: /path/to/file.ext:1-42
 - Make it easy to reason about changes, show inlined diffs when it makes sense
3. Questions for the author if context is unclear.
