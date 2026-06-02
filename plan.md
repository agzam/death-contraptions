# Agentic Browser REPL - Plan & Progress

Status: in progress. This is the single pointer to resume in a fresh session.

## Goal

Build the best agentic browser REPL we can within our setup (ECA + Emacs
client, nbb, Playwright). Primary use is verifying/testing our web products;
also scraper scripts, web-UI prototyping, auth automation, bulk downloads. The
agent drives a live browser; a backend watcher (the `monitor` skill + k8s /
Splunk) can correlate.

## The three browser tools and their roles

- jxa-browser (existing MCP): drives your REAL, already-open everyday browser
  via AppleScript. Zero setup, non-invasive, no debug port. Unique niche: "the
  browser I'm using right now, as-is." Limited: no protocol-level
  network/devtools, no cross-origin iframes. Use for quick peeks.
- Playwright MCP (`@playwright/mcp`, integrated, default-disabled): powerful but
  a fixed-tool / big-snapshot interface. First cut; kept as a fallback.
  On-demand via config toggle (heavy: edit config + `bb setup.bb` + reload).
- nbb-REPL (the chosen powerhouse, in progress): nbb (ClojureScript on Node) +
  Playwright behind a live nREPL, driven via our nrepl MCP. Liveness (state in
  atoms), token economy (targeted queries vs huge snapshots), full Playwright
  API, live network capture. On-demand = start a process (the nrepl MCP
  auto-discovers it via `.nrepl-port`).

## Key decisions (and why)

- Powerhouse = nbb-REPL, not the MCP: liveness + token economy + live
  correlation; on-demand via process start, no config churn.
- jxa stays for quick peeks: Playwright-CDP cannot attach to your incidental
  Brave without a debug port + dedicated profile, so jxa's "live browser as-is"
  niche is irreplaceable.
- REPL session modes: fresh (default; wipes cookies, desired for most testing),
  persistent (login reused; tenant testing), attach-CDP (real session + network
  depth; fills jxa's gap; needs a debug-port browser).
- Streaming (progressive tool output) is a gimmick for us. In ECA's turn-based
  loop the model cannot act on mid-call output. The better primitive is
  fire-and-poll over atoms: kick work off async, stash progress in an atom, poll
  between turns. Sidesteps tool timeouts, lets the agent react, stays compact.
- ECA needs no PR. The lever is entirely ours: the nrepl MCP (await upgrade) +
  an nbb stdlib. Optional future upstream niceties (not needed): a
  `tool-call-details-after-invocation` multimethod for richer `nrepl-eval`
  rendering; a `toolProgress` streaming channel.
- Async over nREPL: nbb returns the promise object, not its value. Solved by a
  def-on-resolve + poll wrapper, now baked into the nrepl MCP
  (`eval-code-await`). promesa is "the way" in nbb.

## Target architecture

    agent -> (ECA spine) -> nrepl MCP [standing, auto-awaits cljs]
          -> nbb nREPL [holds browser/ctx/page/atoms; writes .nrepl-port]
          -> Playwright -> browser

Plus the nbb stdlib (`browser_repl.cljs`) loaded into the session: modes,
nav/click/fill, `aria` "eyes" (scoped ariaSnapshot), network+console capture
into atoms, download/wait/assert, promesa-wrapped. jxa-browser unchanged.

## Status

DONE (committed):
- nrepl MCP async upgrade: `client.clj` `eval-code-await` + `await-kickoff-code`;
  `server.bb` `await` arg + `cljs-port?` auto-detect (via discovery cache) +
  `eval-fn` branch; 2 new tests. Validated: returns "42" / "ERR boom" against a
  live nbb session; suite 32 tests green; kondo clean.
- Playwright MCP integration: `tools/playwright/server.bb` launcher (npx
  `@playwright/mcp`, modes via config), `setup.bb` registration with
  `:default-disabled?`, `local-config.example.edn` (`:servers :playwright` +
  `:qlik-verify`), `qlik-verify` skill, root `bb.edn`/`run_tests.bb`/
  `setup_test.bb`. Validated: drove the vohi DI hub end-to-end (login,
  navigation, network capture) via the MCP.

DONE (task 3 - browser-repl stdlib + launcher; validated, NOT yet committed):
- `tools/browser-repl/browser_repl.cljs` - the nbb+Playwright stdlib: modes
  fresh/persistent/attach; `configure!`/`start!`/`stop!` with idempotent lazy
  auto-start (every action ensures the page); `goto`/`click`/`fill`/`type-text`/
  `press` via target specs (`"css"` | `[:role r {:name..}]` | `[:text/:label/
  :placeholder/:testid ..]` | Locator); targeted extractors `texts`/`text`/
  `count-of`/`attrs`/`visible?`; `aria` scoped eyes (default body); opt-in
  `capture-net!`/`capture-console!` into atoms with request-header correlation
  keys + compact `net-summary`/`net-where`; `wait-url`/`wait-for`/`download!`/
  `assert!`; `run-job!`/`job` fire-and-poll; `eval-js` (gotcha-doc'd); `status`/
  `api`; all promesa-wrapped.
- `tools/browser-repl/launch.bb` - launcher: pure builders (`nbb-cmd`,
  `session-config`, `init-forms`, `await-wrap`) + spawns the pinned local nbb
  nrepl-server in the tool dir, requires the stdlib + configures the mode +
  starts the browser (all awaited via a bencode def-on-resolve/poll client),
  writes `.nrepl-port` to a discovery dir, prints the port, supervises nbb, and
  tears the whole process tree down on exit. Flags: `--port --mode --headless
  --user-data-dir --cdp-endpoint --port-file-dir`.
- `package.json` (nbb 1.3.204 + playwright 1.60.0, chromium-1223 shared cache),
  `nbb.edn` (`:paths ["."]`), `bb.edn`/`run_tests.bb`/`launch_test.bb`
  (6 tests / 25 assertions green), repo `.clj-kondo/config.edn` (promesa
  lint-as; 0 errors/0 warnings on stdlib+launcher).
- Validated e2e via the await/bencode path (= the MCP's `eval-code-await`) on a
  fresh headless session: capture-net!/console, goto example.com, `aria` eyes,
  text/texts extract, eval-js, `net-summary` -> `{200 1}`, `run-job!`/`job` ->
  `:done`, clean whole-tree teardown (no nbb/chromium orphans).

UNCOMMITTED (nrepl MCP fidelity upgrade; on disk + proto-validated + 33 tests
green; live-MCP confirmation PENDING a clean ECA restart): reworked
`client.clj` `eval-code-await` (+ `await-poll-form`) so async failures are real
errors (isError, `*e` = real error, full message/call-log) and successes set
`*1` - replacing the old `"ERR ..."` string. See gotchas for the mechanism, the
global-`*1`/`*e` finding, and the stale-MCP-process trap that blocked live
re-validation this session (ECA reload reconnected to stale processes; needs a
clean restart to load the new code).

UNCOMMITTED, separate repo: `awesome-qlik-ai/mcp/assessments/playwright-mcp.md`
(goes via that repo's branch/PR workflow, not a direct main commit).

PROTOTYPE (scratch, not committed): `~/.cache/qlik-verify/nbb-proto/` - `nre.bb`
(bb nREPL client with `--await`), `FINDINGS.md`. Proved nbb+Playwright+nREPL
liveness, token economy (20 connection names in 534 chars vs a multi-KB
snapshot), live response capture (37 `/api/v1` responses into an atom). A live
nbb nREPL may still be running on port 4321 (ephemeral bg job).

DONE:
4. browser-repl skill - `skills/browser-repl/SKILL.md`: tool routing
   (jxa/Playwright/nbb-REPL), launch + modes, `nrepl-eval` driving (`:port` +
   `:await true`), eyes/extraction, net/console capture, fire-and-poll, live
   reload, errors/gotchas, safety (local-only RCE, fresh-profile default,
   mutation gating, no echoing creds), teardown, API reference. Needs
   `bb setup.bb` to propagate to the consumers.

NEXT (tasks 5-6):
5. Wire + reconcile: nrepl MCP enabled in the gpg config + `local-config.example.edn`
   (`:nrepl {}`, always-on, documented as the browser-repl channel) - DONE.
   STILL OPEN: decide the Playwright MCP's fate (keep disabled fallback vs
   remove `tools/playwright`); run `bb setup.bb` to propagate the new skill +
   config; the nrepl MCP fidelity upgrade needs a clean ECA restart to take
   effect (stale-process trap, see gotchas).
6. Validate e2e on vohi via the new path (login + targeted extract + network
   correlation) with plain `nrepl-eval`. Blocked until the MCP runs fresh code.

## Hard-won facts & gotchas (do not relearn)

- nbb nREPL returns promises, not values -> `eval-code-await` wraps + polls.
- nbb's SCI nREPL has NO `load-file`. Load the stdlib with `(require '[browser-repl])`
  (nbb runs in the tool dir whose `nbb.edn` puts `.` on :paths). The launcher does this.
- `require`/loading an npm-module-bearing ns (playwright) is ASYNC over nREPL
  (returns a promise) -> AWAIT it, not just `start!`. The launcher awaits every
  init form (require + configure! + start!), in order.
- nbb writes its own `.nrepl-port` in its CWD on startup. nbb+playwright TRAP
  SIGTERM to close chromium gracefully and can outlive a plain `.destroy`; tear
  the whole descendant tree down (SIGTERM, brief grace, then SIGKILL survivors).
- nrepl MCP auto-discovery only finds `.nrepl-port` in an ANCESTOR of the MCP's
  CWD (the FIRST ECA workspace root, e.g. qlik-trial). A port file in a sibling
  repo is NOT found. Either pass `:port` + `:await true` to `nrepl-eval`
  explicitly (reliable), or `launch.bb --port-file-dir <that ancestor>`. Verified
  live: `nrepl-list-ports` saw nothing for a death-contraptions port while the
  MCP ran from qlik-trial; explicit `:port` + `:await true` drove it end-to-end
  (HN: 30 stories extracted, net-summary {200 7}, scoped aria) through the MCP.
- nbb/SCI `*1`/`*2`/`*3`/`*e` are GLOBAL across nREPL sessions, NOT per-session
  (verified: cloning a session does not isolate them). So poll-driven await
  cannot keep `*2`/`*3` clean (every poll eval shifts the one global history) -
  but `*1` (resolved value), `*e` (real error), and isError ARE faithful via
  capture+re-throw (see the eval-code-await upgrade below).
- eval-code-await FIDELITY UPGRADE (committed-ready, on disk, proto-validated;
  pending live-MCP confirmation): the kickoff now runs CODE inside
  `(p/then (fn [_] CODE))` so sync throws AND async rejections both land the
  REAL error object in `*nre-err*`; a single poll form re-throws it so a failure
  is a genuine error (isError + `*e` bound) and a success returns the value (->
  `*1`). Throw a FRESH `js/Error` carrying `(.-message *nre-err*)`, NOT the raw
  rejection: throwing a host error class (e.g. Playwright's TimeoutError) trips
  nbb's "nth not supported" printer quirk and loses the message; the original
  stays in `*nre-err*`. Validated via the bencode proto client on the live nbb:
  failing click -> real "locator.click: Timeout..." + call log, `*e` =
  ex-message; `(p/resolved 42)` -> 42 + `*1`; sync `(throw ...)` -> surfaced.
- "nth not supported on this type function(a,b,c,d){this.cc=a;this.name=b;...}"
  on a SUCCESS-path async eval is the tell that the result is a RAW (un-awaited)
  promesa promise being printed (G/O bitmasks => a cljs type, not a JS error) -
  i.e. the MCP is running `eval-code`, not `eval-code-await`. Means await isn't
  active: stale server.bb, or `await` arg not honored + `cljs-port?` false.
- ECA "reload" of an MCP can RECONNECT to a still-alive stale process instead of
  restarting it, so client.clj/server.bb edits silently DON'T take effect (you
  keep getting old behavior though disk is new). Tell: multiple `eca server`
  instances in `ps` (leaked across sessions) each owning duplicate tool servers.
  Reliable reload = kill ALL `tools/nrepl/server.bb` procs (the browser-repl
  nbb on its own port is untouched) so reconnect is impossible, THEN reload; or
  a clean full ECA restart. Verified the stale-process trap live this session.
- clj-kondo flags every `p/let` binding as unresolved without
  `:lint-as {promesa.core/let clojure.core/let}` (repo `.clj-kondo/config.edn`).
- The system nbb is mise/node-22-pinned and won't run under node 24; pin
  nbb+playwright as LOCAL deps and invoke `node_modules/.bin/nbb`.
- `page.evaluate` with a STRING evaluates an EXPRESSION: `"() => ..."` returns
  the function (serializes to nil). Drop the arrow or use an IIFE.
- `allowed-origins` must include `https://*.qlikcloud.com` (the CDN import-map
  the hub bootstraps from) plus `*.qlikdev.com`, `*.qlik.com`, `*.qlik-stage.com`.
  Narrower and the hub hangs on its loader.
- SDE login: keycloak at `keycloak.<sde>.pte.qlikdev.com`, creds
  `rootadmin` / `Qlik1234`; getByRole locators work ("Username or email",
  "Password", "Sign In").
- US Stage login routes through `login-staging.qlik.com` OAuth (likely SSO/MFA)
  -> use a persistent profile, manual login once.
- vohi DI hub is iframe-free at the top level; routes are deep-linkable:
  `/data-integration/{home,create,catalog/all,connections/all,projects/all,monitoring/all,...}`.
- Correlation: vohi `/api/v1` RESPONSE headers carry no `x-request-id` /
  `traceparent` (only `qlik-api-version`, `x-envoy-upstream-service-time`). The
  join key must come from REQUEST headers (`.on page "request"`) or
  tenant+endpoint+time.
- nbb's playwright pinned chromium-1223 (install via
  `node_modules/.bin/playwright install chromium`); the MCP used the chrome
  channel.
- ECA plan mode disables `edit_file` (preview only) - toggle it off to edit.
- The nrepl MCP is disabled in `local-config.edn.gpg`; enable it (`:disabled?
  false`, `bb setup.bb`, reload ECA) to drive the REPL agentically.

## File map

- `death-contraptions/tools/nrepl/` - upgraded nrepl MCP (`eval-code-await`;
  `await`/`cljs-port?`).
- `death-contraptions/tools/browser-repl/` - the nbb-REPL powerhouse:
  `browser_repl.cljs` stdlib + `launch.bb` launcher + `nbb.edn`/`package.json`
  + `bb.edn`/`run_tests.bb`/`launch_test.bb`. Pinned local nbb+playwright.
- `death-contraptions/.clj-kondo/config.edn` - promesa `p/let` lint-as.
- `death-contraptions/tools/playwright/` - Playwright MCP launcher.
- `death-contraptions/skills/qlik-verify/SKILL.md` - Qlik UI verification playbook.
- `death-contraptions/setup.bb` - servers registry (+ `:default-disabled?`).
- `death-contraptions/local-config.example.edn` - `:servers :playwright` +
  `:qlik-verify` schema.
- `death-contraptions/{bb.edn,run_tests.bb,setup_test.bb}` - root setup tests.
- `~/.cache/qlik-verify/nbb-proto/` - prototype (`nre.bb`, `FINDINGS.md`).
- `awesome-qlik-ai/mcp/assessments/playwright-mcp.md` - security assessment.

## Open decisions

- Playwright MCP fate: keep as a disabled fallback, or remove `tools/playwright`
  once the nbb-REPL is the powerhouse (task 5).
- Enable the nrepl MCP always-on (it is inert without a running nREPL).

## How to resume

Point a fresh session at this file. Task 3 (browser-repl stdlib + launcher) is
DONE and validated but NOT yet committed. Start at task 4 (browser-repl skill),
documenting the `tools/browser-repl/` stdlib API + `launch.bb` modes/flags, the
fire-and-poll pattern (`run-job!`/`job`), jxa-vs-Playwright-vs-nbb-REPL routing,
and safety (local-only RCE, fresh-profile default, mutation gating, no echoing
creds, teardown). Then 5 (wire: enable the nrepl MCP always-on + reconcile the
Playwright MCP's fate) and 6 (e2e on vohi via plain `nrepl-eval`). To drive a
session now: `bb tools/browser-repl/launch.bb --mode fresh` then `nrepl-eval`
`(require '[browser-repl :as b])` and `(b/goto ...)`, `(b/aria)`, etc.
