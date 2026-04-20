# Rules

- Don't be too verbose. 
- Never use bold (**text**) or italic (*text*) emphasis in responses.
- Avoid using em-dash, prefer simpler hyphens instead.

# MCP

- After modifying any MCP server code or config, restart the affected server(s) immediately via elisp-eval - do not ask or inform the user, just do it:
    ```elisp
    (let ((session (eca-session)))
      (eca-api-notify session :method "mcp/stopServer" :params (list :name SERVER_NAME))
      (run-with-timer 2 nil (lambda () (eca-api-notify (eca-session) :method "mcp/startServer" :params (list :name SERVER_NAME)))))
    ```
- All custom MCP servers are experimental. When using any of them, note and report to the user any: empty/cryptic errors, inconsistent state, silent failures, or workarounds you had to use - so the MCP can be improved.

# GIT

- Never perform any git modification ops - commit, push, branch deletion, etc., unless explicitly instructed.
- Never add Co-Authored-By, co-authored-by, or any AI/agent attribution lines to git commits.
- Do not modify or push pull-requests unless explicitly commanded.
- Never trigger a GHA workflow without asking

# CLI tools

- prefer `rg` and `fd` instead of `grep` and `find`

## Jira tickets

For Jira tickets (typically look like 'PROJ-12345') - use jira CLI.
Use `json` template, pipe it to `jq` if necessary - e.g., `jira view PROJ-12345 --template json`, 
Using json template fetches comments, subtasks and linked items.
Request more info from Jira when needed.

# Code

- Functions and methods must have properly formatted, brief, simple docstrings explaining the reasoning - not retelling (already codified) functionality.

- All code should be properly linted. After done with all the edits in every relevant file, apply linting tools:

    - Clojure: clj-kondo
    - Python: ruff, etc
    - Fennel: fnlfmt
    - Elisp: check-paren; checkdoc; package-lint (for packages)
    - Org: org-lint, org-table-align (tables must be aligned, columns should not be too wide - add the column widh row for columns too wide, e.g., <50>)

- Avoid using `>` in Lisp (Elisp, Clojure, Fennel, etc.) for comparison, always prefer `<`, unless it makes the code unreasonably confusing.
- Never leave dangling parens, curly or square brackets in Clojure, EDN, Fennel, Lisp.

- Never hard-wrap prose in Org-mode or Markdown files. Keep each paragraph
  on a single line, regardless of length. This applies to both new content
  you write and edits to existing content. Only insert a newline for an
  actual paragraph break (blank line), a heading, a list item, a code/quote
  block boundary, a table row, a property drawer line, or other structural
  element - never to satisfy a column width like 70/80/100.

- Always provide pointers when speaking about code. They must be in the easily browsable, respective forge format, i.e.,
  - https://github.com/ORG/REPO/blob/BRANCH/FILE.EXT#L1-L10
  - https://gitlab.com/ORG/REPO/-/blob/BRANCH/FILE.EXT?ref_type=heads#L1-L10
  - https://codeberg.org/ORG/REPO/src/branch/BRANCH/FILE.EXT#L1-L10
  - https://git.sr.ht/~ORG/REPO/tree/BRANCH/item/FILE.EXT#L1-10

  When the forge is unknown or it's about files on local drive - use absolute file paths in the format of: `/path/to/file.ext:1-42`

## Think Before You Code

Do not silently guess.

Before making changes:

- State your assumptions clearly.
- If anything is ambiguous, ask instead of choosing one interpretation silently.
- If there are multiple valid approaches, briefly present the tradeoff.
- If the request seems mistaken, inefficient, or overcomplicated, say so.
- If a simpler solution exists, recommend it before implementing.
- If you are confused, stop and explain what is unclear.

Do not act certain when you are uncertain.

## Keep the Solution Simple

Solve the requested problem with the minimum necessary code.

- Do not add features that were not asked for and not discussed.
- Do not introduce abstractions for one-time use.
- Do not add configurability, extensibility, or generalization without prior discussion.
- Do not add defensive error handling for unrealistic cases.
- Prefer simple, readable code over clever code.
- If the solution feels too large, step back and simplify it.

## Work Toward Verifiable Outcomes

Don't excessively create "reports", focus on solutions, not reporting.
Turn requests into clear success criteria whenever possible.

Examples:
- “Fix the bug” -> reproduce it, fix it, then verify the fix
- “Add validation” -> add checks for invalid input and verify behavior
- “Refactor this” -> preserve behavior and confirm tests still pass
- “Optimize this” -> improve performance without changing correctness

For multi-step tasks, make a short plan with verification points.

Example:

1. Inspect the current behavior -> verify: identify the real issue
2. Implement the minimal fix -> verify: affected behavior changes as expected
3. Run tests or checks -> verify: no regressions introduced

Prefer tests, existing checks, or concrete validation over verbal confidence.

## Ask for Help at the Right Time

Do not continue blindly when the risk is high.

Pause and ask if:

- the request is ambiguous in a way that affects implementation
- the codebase contains conflicting patterns
- the correct behavior is unclear
- the task requires a product or architectural decision
- you are choosing between trade-offs the user should approve

Do not fabricate certainty to stay moving.
