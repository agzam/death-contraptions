---
name: create-spec
description: Capture investigation findings from a ticket or task into a structured spec optimized for LLM consumption. Also use to check for existing specs when investigating bugs that might relate to past work.
---

# create-spec

Build a structured spec document from an investigation. The output is for future LLM consumption (not humans): comprehensive enough to solve the problem, terse, progressively disclosed.

The skill covers two flows:
- Creation: turn an investigation into a spec.
- Discovery: find and use existing specs when investigating a new bug or regression.

## When to invoke

Creation:
- User asks to "create a spec", "save the plan", "capture the investigation", "write this up", etc.
- Investigation spans multiple repos or sessions and needs a durable artifact.
- Before starting implementation, to lock in the plan.

Discovery:
- Starting investigation of a bug, regression, or ticket where similar work may have happened before.
- User mentions symptoms that "feel familiar" or references a past ticket.

## Status semantics

Each `status` value has prescriptive meaning. Branch behavior on it before anything else.

- `investigating`: findings are partial. Hypotheses are hypotheses. `Progress` section is authoritative for what's been verified vs pending. Re-verify before acting on anything.
- `planned`: investigation deemed complete as of `updated`. Code pointers accurate as of `projects[].ref`. Ready to implement. Items marked `[hypothesized]` still need confirmation before action.
- `implementing`: fix in flight. `Progress` section is authoritative for which solution steps are done vs pending.
- `resolved`: historical artifact. Solution shipped. Use for regression context, not active work.

## Storage convention

Specs live at `~/GitHub/{org}/llm-specs/`, where `{org}` is the GitHub org of the repo being worked on.

Resolution rules:
1. If working in a repo under `~/GitHub/{org}/{repo}/`, use that org.
2. If multiple repos are involved, use the org of the primary one (ask user if ambiguous).
3. If no repo context, ask the user which org's specs dir to use.

Create the `llm-specs/` directory if it does not exist.

## File naming

- With ticket: `TICKET-ID.md` (e.g., `PROJ-1234.md`).
- Without ticket: kebab-case slug describing the problem (e.g., `fix-org-roam-sync.md`, `emacs-treesit-migration.md`).

## Spec document format

Use this template verbatim. Omit sections only if truly empty (e.g., no dead ends found).

````markdown
---
ticket: PROJ-1234           # omit if no ticket
title: Brief descriptive title
status: investigating | planned | implementing | resolved
created: 2024-01-15
updated: 2024-01-20         # only if changed after creation
projects:                  # every repo INVESTIGATED, including ones ruled out as bug source
  - repo: org/repo-a
    ref: abc1234           # short SHA at investigation time; staleness check compares against current HEAD
    paths: [src/foo.clj, src/bar.clj]   # files the spec makes claims about (buggy OR investigated then cleared)
  - repo: org/repo-b
    ref: def5678
    paths: [src/handler.clj]
symptoms:                  # greppable strings - specific enough to filter noise on grep
  - "NullPointerException at FooService.process"
  - "timeout on POST /api/v2/widgets"
  - "foo_processing_errors above 5/min"
related: [PROJ-1111, PROJ-999]   # other tickets or spec slugs
---

# TICKET-ID: Title

## Synopsis
2-3 sentences. What's broken, why, what fixes it. An LLM must be able to decide relevance from this alone.

## Affected areas
Subset of `projects` where the bug actually lives. Repos in `projects` that were investigated and ruled out belong in `Dead ends`, not here. One line per file. Format: `org/repo:path/to/file - one-line what`.
- org/repo-a:src/foo.clj - FooService processes events with wrong ordering
- org/repo-b:src/handler.clj - timeout too low for new payload size

## Root cause
Concise explanation with code pointers (`org/repo:path:line`), not code blocks. Reference frontmatter `projects` entries for repo context.

## Solution
Numbered steps. Each step references `file:line`. When status is `investigating` or `planned`, every item must be tagged `[confirmed]` (verified against current code) or `[hypothesized]` (not yet verified). When status is `implementing`, tag completed items `[done]`.
1. [confirmed] org/repo-a:src/foo.clj:42 - add null check before dispatch
2. [hypothesized] org/repo-b:src/handler.clj:108 - raise timeout to 30s (may mask deeper batching issue)
3. [confirmed] org/repo-b:config/prod.edn:15 - add feature flag `:new-handler?`

## Dead ends
Discarded approaches AND ruled-out hypotheses/repos. Prevents future re-exploration. Covers: fixes that failed, repos investigated and cleared, theories disproved.
- Tried X at `file:line` - failed because Y
- Considered Z but conflicts with W

## Progress
Present only when status is not `resolved`. Authoritative for in-flight state.

Workspace:
- org/repo-a @ feature/foo (worktree, 3 dirty files, pushed, PR org/repo-a#42)
- org/repo-b @ main (clean)

Confirmed:
- FooService.process receives null when upstream batches empty (verified org/repo-a:src/foo.clj:38)
- Timeout failures correlate with payloads above 2MB (verified via splunk query, see Useful commands)

Open questions:
- Does the upstream guarantee non-empty batches? (need to check protocol spec)
- Why did this only start failing after v2.3 deploy?

Next action:
- Read org/repo-a:src/bar_publisher.clj:80-120 to confirm or reject the null-origin hypothesis.

Useful commands:
- `rg "FooService.process" --type clj`
- splunk: `index=prod service=foo level=error | stats count by error_type`

## Regression signals
What an LLM should look for to suspect this issue recurring. Concrete patterns: error strings, metrics, user-visible effects. These elaborate on frontmatter `symptoms`.

## Full context
Detailed investigation notes. Edge cases, related system behavior, rationale. Read only if synopsis + root cause + solution were insufficient.

## Changelog
Append-only. One line per substantive update. Omit if doc never updated past creation.
- 2024-01-22: bumped repo-a ref to def5678 after re-verifying null path against new code
- 2024-01-20: added BarPublisher hypothesis; promoted timeout fix from hypothesized to confirmed
````

## Creation workflow

1. Gather from the current conversation: ticket data, findings, code pointers, dead ends, decisions.
2. If a ticket exists, fetch fresh data via `jira view TICKET-ID --template json` to confirm title and current status.
3. For each affected repo, capture current commit SHA: `git -C path/to/repo rev-parse --short HEAD`. Note if the tree is dirty (`git status --porcelain`).
4. Identify greppable symptom strings. Use exact error messages, log lines, endpoints, metric names. Not paraphrases like "auth fails sometimes".
5. Resolve target directory via storage convention. Create if absent.
6. If a spec already exists for this ticket/slug, ask user: update in place, replace, or create variant (`PROJ-1234-followup.md`).
7. Write the spec file.
8. Update `MANIFEST.md` in the same directory (see below). Mandatory step.

## MANIFEST.md

Each `llm-specs/` directory has a `MANIFEST.md` for fast scanning. It is the entry point for discovery.

Format:

```markdown
# Spec Manifest

| Spec | Title | Status | Keywords |
|------|-------|--------|----------|
| PROJ-1234 | FooService NPE on event processing | investigating | NPE, timeout, FooService |
| PROJ-999 | Widget timeout after v2 migration | resolved | timeout, /api/v2/widgets |
| fix-org-roam-sync | Org-roam DB lock under concurrent sync | resolved | "database is locked", sqlite |
```

Rules:
- Keywords are short hints. Real symptom strings live in spec frontmatter.
- Update after every create, status change, or deletion.
- Sort by created date descending (newest first).

## Discovery workflow

When investigating something that might relate to past work:

1. Determine relevant org(s) from current repo context.
2. Read `~/GitHub/{org}/llm-specs/MANIFEST.md` first. Cheap, single read, gives a corpus overview.
3. Identify candidate specs by keyword match in the manifest.
4. For deeper match, grep symptom strings across spec files:
   ```
   rg -l "error string or pattern" ~/GitHub/{org}/llm-specs/
   ```
5. Read candidate specs starting from the top (synopsis). Use line limits - you may not need the full file.
6. Branch on status (see Status semantics) before trusting findings.
7. Staleness check (mandatory before acting on findings):
   a. For each `projects[].repo`: compare `ref` to `git -C path/to/repo rev-parse --short HEAD`.
   b. If diverged: run `git log {ref}..HEAD -- {projects[].paths}` to see whether spec-relevant files changed. File-touching is the strong signal.
   c. Days since `updated` is a soft signal. Older means more skeptical, but file-diff trumps calendar diff.
   d. Report explicitly: "Spec is N days old. repo-a moved K commits forward, J of which touched spec paths. Claims about file X may be stale."
8. Decide based on status + staleness:
   - Fresh, status `planned` or `resolved`: trust findings, proceed.
   - Stale: re-verify affected claims against current code before acting. Apply Update workflow.
   - Status `investigating`: hypothesized items always need verification regardless of staleness.
9. If a spec is relevant, cite it explicitly (`~/GitHub/{org}/llm-specs/PROJ-1234.md`) and use it to focus investigation.

## Update workflow

When picking up an existing spec and the world has moved on:

1. Run the staleness check from Discovery workflow.
2. For each affected finding:
   - Still accurate: bump the relevant `projects[].ref`. No content change.
   - Modified by new code: rewrite the finding, bump `ref`, note in Changelog.
   - Invalidated: strike through or move to Dead ends with a brief note. Note in Changelog.
3. If status was `planned` but key findings got invalidated, demote to `investigating` until re-verified.
4. Update the `Progress` section (if status is not `resolved`): refresh workspace state, confirmed list, open questions (re-attempt each - often a single grep away), next action.
5. Bump `updated` to today.
6. Append a Changelog entry summarizing the update.
7. Update `MANIFEST.md` if title/status/keywords changed.

## Style rules

- Code pointers over code blocks. Use `org/repo:path:line` format consistently across all sections, even when the org seems redundant. Multi-project specs become ambiguous otherwise, and specs surface in grep results where the directory context is lost.
- Structured forms (tables, lists, frontmatter) beat prose.
- Frontmatter `symptoms` must be greppable: real strings copied from logs/errors/code, not descriptions.
- One topic per section, except `Full context` which may use subsections for distinct supporting topics (architecture, deployment landscape, test infrastructure, etc.). For all other sections, if you need subsections, the section is too broad - split or re-scope.
- No redundancy across sections. Synopsis, Root cause, and Solution should not repeat each other.
- Hyphens, not em-dashes. No bold or italic emphasis.
- Spec body should fit in a single screen if Full context is collapsed. If it does not, you have prose bloat.
- For status `investigating` or `planned`, every `Solution` item and material claim in `Root cause` must be tagged `[confirmed]` or `[hypothesized]`. Required, not optional - lets a reader instantly separate facts from hunches.
- For status `implementing`, additionally tag completed items `[done]`. Once `resolved`, markers can be cleaned up since the spec becomes historical.
- Open questions are investigation debt. Re-attempt at every spec update; do not carry them forward without trying.
- No absolute filesystem paths in spec content. All file references use `org/repo:path:line`. Worktree entries are descriptors - repo, branch, and state flags - not filesystem locations; consumers resolve actual paths at runtime via `git worktree list`. Shell commands in Useful commands are expressed relative to repo root, prefixed with repo context when ambiguous (e.g., `org/repo-a: clojure -M:test`). This keeps specs portable across machines with different checkout layouts.
