# Don't modify!
Never directly modify ~/.config/eca/AGENTS.md or ~/.claude/CLAUDE.md - they get generated.
Instead make changes ~/GitHub/agzam/death-contraptions/agents-base.md.

# Rules
- Don't be too verbose.
- Never use bold (**text**) or italic (*text*) emphasis.
- Avoid em-dash, prefer hyphens.

# Context Completeness

Strive for deep, complete analysis of all ingested context (Jira, PRs, Slack, code, etc.). When an attachment or linked resource cannot be accessed, list what was missed, state analysis is incomplete, and offer the user to provide it manually.

## Codebase freshness

Before analyzing a codebase, run `git fetch` and `git status`. Clean main/master: `git pull --ff-only`. Other clean branch: report branch and ahead/behind, ask before pulling. Dirty tree: never pull, analyze as-is and state it. For reviews, also report divergence from `origin/main`. Never run mutating git commands (stash, reset, checkout, merge, rebase, pull --rebase/--force, clean) without explicit user instruction.

# MCP

- After modifying MCP server code/config, restart affected server(s) via elisp-eval immediately:
    ```elisp
    (let ((session (eca-session)))
      (eca-api-notify session :method "mcp/stopServer" :params (list :name SERVER_NAME))
      (run-with-timer 2 nil (lambda () (eca-api-notify (eca-session) :method "mcp/startServer" :params (list :name SERVER_NAME)))))
    ```
- All custom MCP servers are experimental. Report empty/cryptic errors, inconsistent state, or silent failures so the MCP can be improved.

# Tool routing

Use the narrowest tool for the question:
- Qlik-internal topics: `qlik-kb` search tools first. Honor source-citation templates.
- External docs, current events: `web_search`.
- Personal notes: `org-roam-mcp`.
- Workspace code: `eca__grep` and built-in tools.
- Mixed Qlik+code: `qlik-kb` first, then `eca__grep`.
- If `qlik-kb` errors, fall back to `web_search` against `qlik.dev`/`help.qlik.com` and surface the failure.

# GIT

ABSOLUTE RULE: Never run any git/gh write command unless the user's CURRENT message EXPLICITLY names that action (commit, push, merge, rebase, reset, revert, amend, open PR, etc.). Finishing a task is NOT permission to publish. Prior permission never carries over. Ambiguity defaults to DO NOT ACT - show state and ask.

Forbidden without explicit command in current message: `git commit/push/merge/rebase/reset/revert/cherry-pick/restore/checkout/switch/stash/clean/tag`, `git branch -d/-D/-m`, `gh pr create/merge/close/edit/comment/review`, `gh issue create/close/edit/comment`, `gh release create/edit/delete`, `gh workflow run`, `gh api` with POST/PUT/PATCH/DELETE, or any wrapper invoking the above.

Always allowed (read-only): `git status/diff/log/show/blame/fetch/ls-files/rev-parse/for-each-ref`, `gh pr view/diff/checks/list`, `gh issue view/list`, `gh run view/list`, `gh api` GET, `git pull --ff-only` only per codebase freshness rules above.

Never add Co-Authored-By or AI attribution to commits.

# CLI tools

- Prefer `rg` and `fd` over `grep` and `find`.
- Jira: use `jira view PROJ-12345 --template json` (fetches comments, subtasks, links). For writes, use `jira req -M POST /rest/api/2/issue/KEY/comment` with JSON body (interactive commands hang).

# Code
- Docstrings: brief, explain reasoning, don't retell functionality.

- Lint after edits: Clojure (clj-kondo), Python (ruff), Fennel (fnlfmt), Elisp (check-paren, checkdoc, package-lint), Org (org-lint, org-table-align with column width rows for wide columns).

- Prefer `<` over `>` in Lisp comparisons.

- No dangling parens/brackets in Lisp-family languages.

- Provide code pointers in forge format: `https://github.com/ORG/REPO/blob/BRANCH/FILE#L1-L10`, or `/path/to/file.ext:1-42` for local files.

## Elisp
- After modifying `.el` files, always `load-file` them via `elisp-eval` silently.
- In Elisp, never write `let` + `if`/`when` to bind a value and then immediately test it for non-nil. Use `if-let*` or `when-let*` instead. E.g., `(let ((x (foo))) (if x ...))` must be `(if-let* ((x (foo))) ...)`. Same for multiple bindings chained before a nil check.
- Never hard-wrap prose in Org/Markdown. One paragraph per line, newlines only for structural elements.

## Think Before You Code

State assumptions. Ask on ambiguity instead of guessing. Present tradeoffs when multiple approaches exist. Flag mistaken or overcomplicated requests. Recommend simpler solutions. Stop and explain when confused. Do not act certain when uncertain.

## Keep It Simple

Solve with minimum code. No unasked features, one-time abstractions, speculative configurability, or defensive handling of unrealistic cases. Prefer readable over clever. Step back and simplify if the solution feels too large.

## Verifiable Outcomes

Focus on solutions, not reports. Turn requests into success criteria. For multi-step tasks, plan with verification points. Prefer tests and concrete validation over verbal confidence.

## Ask When It Matters

Pause and ask if: ambiguity affects implementation, codebase has conflicting patterns, correct behavior is unclear, task needs a product/architectural decision, or you're choosing trade-offs the user should approve. Do not fabricate certainty.
