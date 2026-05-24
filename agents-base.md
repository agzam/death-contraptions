# Rules
- Don't be too verbose.
- Never use bold (**text**) or italic (*text*) emphasis.
- Avoid em-dash, prefer hyphens.

# Context Completeness

Strive for deep, complete analysis of all ingested context (Jira, PRs, Slack, code, etc.). When an attachment or linked resource cannot be accessed, list what was missed, state analysis is incomplete, and offer the user to provide it manually.

## Investigation specs

Past investigations live at `~/GitHub/{org}/llm-specs/`, indexed by `MANIFEST.md`.

- When starting work on a ticket or investigating a bug, read the relevant org's `MANIFEST.md` to surface related specs. Cheap, single read.
- When working a ticket that has a spec, treat it as a living document. Update Progress, status, refs, and Changelog as findings change. Don't wait to be asked.
- For the full create/discover/update workflow, load the `create-spec` skill.

## Tool routing

Use the narrowest tool for the question:
- Qlik-internal topics: `qlik-kb` search tools first. Honor source-citation templates.
- External docs, current events: `web_search`.
- Personal notes: `org-roam-mcp`.
- Workspace code: `eca__grep` and built-in tools.
- Mixed Qlik+code: `qlik-kb` first, then `eca__grep`.
- If `qlik-kb` errors, fall back to `web_search` against `qlik.dev`/`help.qlik.com` and surface the failure.

- All custom MCP servers are experimental. Report empty/cryptic errors, inconsistent state, or silent failures so the MCP can be improved.

# Git

- ABSOLUTE RULE: Never run any git/gh write command unless the user's CURRENT message EXPLICITLY names that action (commit, push, merge, rebase, reset, revert, amend, open PR, etc.). Finishing a task is NOT permission to publish. Prior permission never carries over. Ambiguity defaults to DO NOT ACT - show state and ask.

- Forbidden without explicit command in current message: `git commit/push/merge/rebase/reset/revert/cherry-pick/restore/checkout/switch/stash/clean/tag`, `git branch -d/-D/-m`, `gh pr create/merge/close/edit/comment/review`, `gh issue create/close/edit/comment`, `gh release create/edit/delete`, `gh workflow run`, `gh api` with POST/PUT/PATCH/DELETE, or any wrapper invoking the above.

- Always allowed (read-only): `git status/diff/log/show/blame/fetch/ls-files/rev-parse/for-each-ref`, `gh pr view/diff/checks/list`, `gh issue view/list`, `gh run view/list`, `gh api` GET, `git pull --ff-only` only per codebase freshness rules above.

- Never add Co-Authored-By or AI attribution to commits.

-  Each "logical operation" (e.g., "add new endpoint and update tests") gets one git commit. We do not commit things failing midway. All or nothing - no "half done". Inform the user when we're good and ask permission to commit. 

- Never push to remote without prior acknowledgment

## Codebase freshness

- Always check if repo uses worktrees and act accordingly
- Prior to anything else, run `git fetch` and `git status` in the correct branch/worktree for **every** repo involved.
- Clean main/master: `git pull --ff-only`. Other clean branch: report branch and ahead/behind, ask before pulling. 
- Dirty tree: never pull, analyze as-is and state it. 
- For reviews, also report divergence from `origin/main`. 
- Never run mutating git commands (stash, reset, checkout, merge, rebase, pull --rebase/--force, clean) without explicit user instruction.

## GitHub/GitLab/etc.

- Provide code pointers in forge format: `https://github.com/ORG/REPO/blob/BRANCH/FILE#L1-L10`, or `/path/to/file.ext:1-42` for local files.
- Never throw plain PR/Issue numbers, always show them in bug-reference style where both org/user and repo are present - ORG/REPO#42

# CLI tools

- Prefer `rg` and `fd` over `grep` and `find`.
- Jira: use `jira view PROJ-12345 --template json` (fetches comments, subtasks, links). For writes, use `jira req -M POST /rest/api/2/issue/KEY/comment` with JSON body (interactive commands hang).

# Code
- Docstrings: brief, explain reasoning, don't retell functionality.
- No explicit mentioning of ticket numbers, issues or PRs in the code - that info can be discovered via git blame.

## Lisp
- Prefer `<` over `>` in Lisp - Clojure, Elisp, Fennel, etc.
- No dangling parens/brackets

### Elisp
- After modifying `.el` files, always `load-file` them via `elisp-eval` without asking.
- In Elisp, never write `let` + `if`/`when` to bind a value and then immediately test it for non-nil. Use `if-let*` or `when-let*` instead. e.g., `(let ((x (foo))) (if x ...))` must be `(if-let* ((x (foo))) ...)`. Same for multiple bindings chained before a nil check.

## Always lint after edits

| Clojure | clj-kondo |
|---------|-----------|
| Python | ruff |
| Fennel | fnlfmt |
| Elisp | check-paren, checkdoc, package-lint |
| Org | org-lint, org-table-align with column width rows for wide columns |
| Yaml | yamllint |
| Anything ee el  else | find suitable linter or inform user of its absence |

## Test

- Add a test for every single improvement and a new feature you add.
- Every code change should be covered by test adjustment.
- Take active action in preventing future regressions. 

### elisp-eval hygiene

- Elisp expressions often return large structures (buffer lists, package alists, plists, hash-tables). Use the `print_length` and `print_level` tool parameters to cap output - default to small values (e.g., 20/5) and increase only when you actually need more.
- Never manually scan or edit code to fix unbalanced parentheses. Run `(check-parens)` first - it signals the exact mismatch location. Only then make a targeted one-character fix.

### Don't break active Emacs

- NEVER break the active Emacs session when testing with elisp-eval.
- The user's Emacs is a live working environment - not a disposable test harness. 
- Try to minimize distractions, don't pop the buffers unless you have to - create them buried, in the back of the stack. 
- When running elisp-eval for testing: save and restore any global/buffer-local variables you mutate, unbind any temporary advice or hooks you add, kill any temporary buffers you create, and undo any mode or state changes. 
- Wrap test code in `unwind-protect` or equivalent to guarantee cleanup even on error. 
- If a test requires destructive changes that cannot be safely reversed, do NOT run it in the live session - write it to a file and instruct the user to run it in a separate `emacs -Q` instead.

### buttercup tests

Failing buttercup tests throw enormously large stacktraces that quickly junk-up the context. Always limit them, e.g. `make test | rg FAILED`

## Think Before You Code

State assumptions. Ask on ambiguity instead of guessing. Present tradeoffs when multiple approaches exist. Flag mistaken or overcomplicated requests. Recommend simpler solutions. Stop and explain when confused. Do not act certain when uncertain.

## Keep It Simple

Solve with minimum code. No unasked features, one-time abstractions, speculative configurability, or defensive handling of unrealistic cases. Prefer readable over clever. Step back and simplify if the solution feels too large.

## Verifiable Outcomes

Focus on solutions, not reports. Turn requests into success criteria. For multi-step tasks, plan with verification points. **Always** prefer tests and concrete validation over verbal confidence.

## Ask When It Matters

Pause and ask if: ambiguity affects implementation, codebase has conflicting patterns, correct behavior is unclear, task needs a product/architectural decision, or you're choosing trade-offs the user should approve. Do not fabricate certainty.
