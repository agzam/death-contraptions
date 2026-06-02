---
name: qlik-verify
description: Drive the Qlik Cloud (QCDI / Talend Data Integration) UI via browser-repl (nbb + Playwright over an nREPL) to validate a ticket or flow on US Stage or an SDE, while a backend watcher tails k8s (SDE) and Splunk for correlated signals. Use when asked to verify/validate/reproduce a ticket in the Qlik UI, exercise connections or data tasks end to end, or watch backend behavior during a UI flow.
---

# Qlik UI verification (qlik-verify)

Reproduce and validate a flow in the Qlik Cloud Data Integration UI while correlating what the UI does with backend signals. The UI side is browser-repl (nbb + Playwright behind an nREPL, driven via the nrepl MCP) on a dedicated, already-logged-in browser profile; the backend side is the `monitor` skill tailing k8s (SDEs only) and Splunk for the same request/trace IDs. Load the `browser-repl` skill for the full driving API.

Two environment kinds (from config `:kind`):
- `:stage` - US Stage tenant (e.g. Neel's). Splunk only; no cluster access.
- `:sde` - a personal SDE (e.g. `vohi`). k8s + Splunk. Use when you need real-time backend visibility.

## Prerequisites

- nrepl MCP enabled (it is the channel browser-repl is driven through): `:servers {:nrepl {}}` in `local-config.edn.gpg`, then `bb setup.bb`, then reload the MCP.
- browser-repl deps installed once: `(cd /Users/ryl/GitHub/agzam/death-contraptions/tools/browser-repl && npm install)`.
- A dedicated browser profile logged into the target tenant once. Drive it with browser-repl `:persistent` mode (`--user-data-dir <profile>`) so the login persists across runs, or `:attach` mode (`--cdp-endpoint`) against a debug-port browser. Do NOT `:attach` to your everyday browser if losing focus/tabs would disrupt you.
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

Start a browser-repl session, then navigate. Launch (a background job; note the printed port):

```sh
bb /Users/ryl/GitHub/agzam/death-contraptions/tools/browser-repl/launch.bb \
   --mode persistent --user-data-dir ~/.cache/qlik-verify/chrome-profile
```

Drive it via `nrepl-eval` with `:port <that port>` and `:await true`. The QCDI hub is a normal SPA with stable, deep-linkable routes - navigate by URL rather than clicking through menus:

```clojure
(browser-repl/goto (str ui-url "/data-integration/connections/all"))
```

Routes: `/data-integration/{home,create,connections/all,projects/all,monitoring/all,catalog/all}`.

If you land on `login-staging.qlik.com` or a tenant login page, STOP and ask the user to complete login in the profile (SSO/MFA), then resume - `:persistent` mode keeps that login for next time. Only automate login when the env's `:login-ref` is the SDE rootadmin and the user opted in: keycloak at `keycloak.<sde>.pte.qlikdev.com`, then `(browser-repl/fill [:label "Username or email"] u)`, `(browser-repl/fill [:label "Password"] p)` (values pulled from the encrypted config; never printed), `(browser-repl/click [:role "button" {:name "Sign In"}])`.

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

- Capture network FIRST, then perform the UI steps. The hub is iframe-free at the top level, so standard selectors work. TODO (first-run recon in the dedicated profile): capture exact target specs for the create/reuse-connection dialog and the task run/monitor controls, and re-check connection dialogs and task-detail pages for embedded iframes; record them here.
  - Connections: reuse an existing connection where possible (`/connections/all`); create one only if needed, prefixing its name with `:safety :artifact-prefix`.
  - Tasks: open/run a pipeline project at `/projects/all`; watch state at `/monitoring/all`.
  - Interact with target specs: `(browser-repl/click [:role "button" {:name "..."}])`, `(browser-repl/fill [:label "..."] v)`; read with `(browser-repl/aria "<region>")` and `(browser-repl/texts "<sel>")` instead of full-page dumps.
- Capture correlation keys from the UI side. Start `(browser-repl/capture-net! {:url-filter "/api/v1"})` before the action, then `(browser-repl/net-where "<endpoint>")` to pull matching entries and their request-side `:correlation-headers` (e.g. `x-request-id`, `traceparent`) plus tenant/connection/task IDs. Feed those exact IDs into the monitor `--regex` and the Splunk query so the backend watcher surfaces only matching lines. This join is the whole point of the dual-agent setup. (Note: vohi `/api/v1` responses carry no `x-request-id`/`traceparent`; capture them from requests, or join on tenant+endpoint+time.)

## 5. Safety

Honor `:defaults :safety`:
- `:mutations :ask` - confirm before any create/run/delete in a real tenant.
- Prefix created artifacts with `:artifact-prefix`; clean up per `:cleanup`.
- Redact `:redact-fields` from any captured network/console payloads.
- browser-repl evaluates arbitrary code (RCE-equivalent). The launcher binds the nREPL to 127.0.0.1 only - never expose the port. Never echo credentials: `fill` from the encrypted config values, never print them.

## 6. Report

Per `:defaults :report`: summarize what was exercised, the UI outcome, and the correlated backend signals (with the IDs used and the monitor job ids), attaching screenshots/network captures if enabled. Write to `:target` (org-roam note / file / Jira comment / Zephyr case).

## Notes

- The hub bootstraps its import-map from the `*.qlikcloud.com` CDN, so a session with no network to that CDN hangs on its loader - expect those requests when capturing.
- Teardown when the verification session ends: kill the browser-repl launcher background job (`eca__bg_job action=kill`) - its shutdown hook closes the browser + nbb. Then kill every monitor you started. The `:persistent` profile keeps your login, so reopening skips re-auth.
