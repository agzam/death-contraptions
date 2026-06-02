---
name: qlik-verify
description: Drive the Qlik Cloud (QCDI / Talend Data Integration) UI via the Playwright MCP to validate a ticket or flow on US Stage or an SDE, while a backend watcher tails k8s (SDE) and Splunk for correlated signals. Use when asked to verify/validate/reproduce a ticket in the Qlik UI, exercise connections or data tasks end to end, or watch backend behavior during a UI flow.
---

# Qlik UI verification (qlik-verify)

Reproduce and validate a flow in the Qlik Cloud Data Integration UI while correlating what the UI does with backend signals. The UI side is the Playwright MCP driving a dedicated, already-logged-in browser profile; the backend side is the `monitor` skill tailing k8s (SDEs only) and Splunk for the same request/trace IDs.

Two environment kinds (from config `:kind`):
- `:stage` - US Stage tenant (e.g. Neel's). Splunk only; no cluster access.
- `:sde` - a personal SDE (e.g. `vohi`). k8s + Splunk. Use when you need real-time backend visibility.

## Prerequisites

- Playwright MCP enabled: in `local-config.edn.gpg`, set `:servers {:playwright {:disabled? false ...}}` with a pinned `:version`, then run `bb setup.bb`. macOS only.
- A dedicated browser profile logged into the target tenant once. Do NOT drive your everyday browser - tabs move under you and you lose the session. Use the Playwright-owned `:user-data-dir` (`:own-profile`) or a separate browser on a debug port (`:attach`).
- For SDE backend watch: download the SDE kubeconfig from devops-console and record its context in the env's `:kube-context`.

## 1. Load the environment config

Config lives encrypted under `:qlik-verify` in `local-config.edn.gpg`. Pull ONLY the non-secret fields for the chosen env, so credentials and API tokens never enter the chat context (`-- <env>` selects it; omit to use `:default-env`):

```sh
gpg --quiet --decrypt /Users/ryl/GitHub/agzam/death-contraptions/local-config.edn.gpg \
 | bb -e '(let [m (clojure.edn/read-string (slurp *in*))
                e (or (first *command-line-args*) (get-in m [:qlik-verify :default-env]))]
            (prn {:env e
                  :cfg (select-keys (get-in m [:qlik-verify :environments e])
                                    [:kind :region :ui-url :kube-context :namespaces
                                     :pod-selectors :splunk :default-space])}))' -- vohi
```

If gpg-agent is locked, decrypt via Emacs instead (`elisp-eval`): `(with-temp-buffer (insert-file-contents "~/GitHub/agzam/death-contraptions/local-config.edn.gpg") (buffer-string))` returns the EDN text (epa decrypts transparently). Never print `:credentials` or `:api-token`.

Also read `:defaults` (`:monitor`, `:report`, `:safety`) the same way; they tune the steps below.

## 2. Open the tenant

Navigate the Playwright MCP to `:ui-url` + a route. The QCDI hub is a normal SPA with stable, deep-linkable routes - prefer navigating by URL over clicking through menus:

- `/data-integration/home`, `/create`, `/connections/all`, `/projects/all`, `/monitoring/all`, `/catalog/all`

If you land on `login-staging.qlik.com` or a tenant login page, STOP and ask the user to complete login in the profile (SSO/MFA), then resume. Only type credentials when the env `:login-method` is `:rootadmin` and the user explicitly opted into automated login.

## 3. Start the backend watcher (dual-agent)

Start monitors BEFORE triggering the UI action, so events are caught live. Use the `monitor` skill (background jobs), not polling. Always `--live --max-runtime <sec>` and kill them when done.

SDE (k8s available) - watch the stitch services and pod health:

```sh
/Users/ryl/GitHub/agzam/death-contraptions/tools/monitor/monitor.bb \
  --source "stern --context <kube-context> -n <ns> '(stitch-connections|stitch-menagerie|stitch-orchestrator|stitch-bobbin|target-qlik|stitch-agent)' -o raw" \
  --regex "<correlation-id>|level=error|panic" --live --max-runtime 600
```

US Stage (no cluster) - Splunk only. Query `:splunk :index` filtered by the correlation IDs from step 4 via `splunk-search`; for a continuous watch, monitor a Splunk CLI stream the same way.

## 4. Drive the flow and correlate

- Perform the UI steps. The hub is iframe-free at the top level, so standard Playwright snapshots/selectors work. TODO (first-run recon in the dedicated profile): capture exact selectors for the create/reuse-connection dialog and the task run/monitor controls, and re-check connection dialogs and task-detail pages for embedded iframes; record them here.
  - Connections: reuse an existing connection where possible (`/connections/all`); create one only if needed, prefixing its name with `:safety :artifact-prefix`.
  - Tasks: open/run a pipeline project at `/projects/all`; watch state at `/monitoring/all`.
- Capture correlation keys from the UI side: list network requests via the Playwright MCP and extract `:correlation-headers` (e.g. `x-request-id`, `traceparent`) plus tenant/connection/task IDs from the request or response. Feed those exact IDs into the monitor `--regex` and the Splunk query so the backend watcher surfaces only matching lines. This join is the whole point of the dual-agent setup.

## 5. Safety

Honor `:defaults :safety`:
- `:mutations :ask` - confirm before any create/run/delete in a real tenant.
- Prefix created artifacts with `:artifact-prefix`; clean up per `:cleanup`.
- Redact `:redact-fields` from any captured network/console payloads.
- Keep `browser_run_code_unsafe` disabled (RCE-equivalent); use the structured Playwright tools.

## 6. Report

Per `:defaults :report`: summarize what was exercised, the UI outcome, and the correlated backend signals (with the IDs used and the monitor job ids), attaching screenshots/traces if enabled. Write to `:target` (org-roam note / file / Jira comment / Zephyr case).

## Notes

- Security review and conditions of use: `awesome-qlik-ai/mcp/assessments/playwright-mcp.md`. Keep `:allowed-origins` scoped to the Qlik domains - `*.qlikcloud.com` (the CDN/import-map the hub bootstraps from), `*.qlikdev.com`, `*.qlik.com`, `*.qlik-stage.com`. Dropping the CDN makes the hub hang on its loader.
- Teardown when the verification session ends: close the browser with `browser_close`, then kill every monitor you started (`eca__bg_job action=kill`). The dedicated profile persists your login, so reopening skips re-auth.
