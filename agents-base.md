# Rules

- Don't be too verbose. 
- Never use bold (**text**) or italic (*text*) emphasis in responses.
- Avoid using em-dash, prefer simpler hyphens instead.
- Don't excessively create "reports", focus on solutions, not reporting.
- You are **never** to perform **any** git modification ops - commit, push, branch deletion, etc., unless explicitly instructed.
- Do not modify or push pull-requests unless explicitly commanded.
- After modifying any MCP server code or config, restart the affected server(s) immediately via elisp-eval - do not ask or inform the user, just do it:
    ```elisp
    (let ((session (eca-session)))
      (eca-api-notify session :method "mcp/stopServer" :params (list :name SERVER_NAME))
      (run-with-timer 2 nil (lambda () (eca-api-notify (eca-session) :method "mcp/startServer" :params (list :name SERVER_NAME)))))
    ```
- All custom MCP servers are experimental. When using any of them, note and report to the user any: empty/cryptic errors, inconsistent state, silent failures, or workarounds you had to use - so the MCP can be improved.

# CLI tools

- To explore jira tickets (typically look like 'PROJ-12345') - you can use jira CLI - (e.g., 'jira view PROJ-12345'), fetch comments, subtasks and linked items. Request more info whenever it makes sense.

- prefer `rg` and `fd` instead of `grep` and `find`

# Code
- Functions and methods must have properly formatted, brief, simple docstrings explaining the reasoning - not retelling (already codified) functionality.

- All code should be properly linted. After done with all the edits in every relevant file, apply linting tool(s) for changesets:

    - Clojure: clj-kondo
    - Python: ruff
    - Fennel: fnlfmt
    - Elisp: check-paren; checkdoc; package-lint (for packages)
    - Org: org-lint, org-table-align (tables must be aligned, columns should not be too wide - add the column widh row for columns too wide, e.g., <50>)

- Avoid using `>` in Lisps for comparison, always preferring `<`, unless it makes the code unreasonably difficult to understand.

- Always provide pointers when speaking about code. They must be in the easily browsable, respective forge format, i.e.,
  - https://github.com/ORG/REPO/blob/BRANCH/FILE.EXT#L1-L10
  - https://gitlab.com/ORG/REPO/-/blob/BRANCH/FILE.EXT?ref_type=heads#L1-L10
  - https://codeberg.org/ORG/REPO/src/branch/BRANCH/FILE.EXT#L1-L10
  - https://git.sr.ht/~ORG/REPO/tree/BRANCH/item/FILE.EXT#L1-10

  When the forge is unknown or it's about files on local drive - use absolute file paths in the format of: `/path/to/file.ext:1-42`
