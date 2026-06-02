# Agentic Browser REPL - Plan & Progress

Status: complete - the harness is built, battle-tested, and committed. This file
is the historical record + resume pointer.

## Goal

Build the best agentic browser REPL we can within our setup (ECA + Emacs
client, nbb, Playwright). Primary use is verifying/testing our web products;
also scraper scripts, web-UI prototyping, auth automation, bulk downloads. The
agent drives a live browser; a backend watcher (the `monitor` skill + k8s /
Splunk) can correlate.

## The two browser tools and their roles

- jxa-browser (existing MCP): drives your REAL, already-open everyday browser
  via AppleScript. Zero setup, non-invasive, no debug port. Unique niche: "the
  browser I'm using right now, as-is." Limited: no protocol-level
  network/devtools, no cross-origin iframes. Use for quick peeks.
- Playwright MCP (`@playwright/mcp`): REMOVED. The nbb-REPL drives the Playwright
  library directly with the full API + liveness + token economy, making the
  curated fixed-tool / big-snapshot MCP redundant. `tools/playwright` deleted;
  qlik-verify migrated to browser-repl.
- nbb-REPL (the chosen powerhouse, DONE): nbb (ClojureScript on Node) +
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

DONE (harness hardening - battle-tested + committed):
- The nth-quirk was a REAL bug, not the stale-process trap: `eval-code-await`
  bailed whenever the kickoff returned nbb's spurious "nth not supported"
  promise-print error - which it ALWAYS does for a promise-bearing form - instead
  of polling like the launcher deliberately does. Fixed with a
  `spurious-kickoff-error?` guard: tolerate the quirk and poll; bail only on a
  REAL up-front compile error. (`client.clj`)
- nbb classification fixed (`discovery.clj` `classify-repl-type`): nbb's describe
  reports `versions {"nbb-nrepl" .. "node" ..}` with NO `"nbb"`/`"clojure"` key,
  so the old `(get versions "nbb")` check left it `:unknown` and auto-await never
  fired. Now detected -> `cljs-port?` true -> auto-await.
- Live await integration test (`integration_test.bb`, run `bb itest`): starts a
  real nbb and asserts sync / async-success / promesa-chain return their values
  and async-failure surfaces the real message - the round-trip the unit tests
  never had (it WOULD have caught the regression). Skips if nbb absent.
- `nrepl-server-info` MCP tool (`server.bb`): git sha + per-source-file mtimes +
  `stale?` (a file on disk newer than process load) so "stale process vs real
  bug" is a glance, not mtime/pid forensics.
- browser-repl `wait-load`/`wait-networkidle` (page lifecycle; `wait-for` is
  target-only) + a port REGISTRY: `launch.bb` advertises to `~/.cache/nrepl-ports/`
  (`$NREPL_MCP_PORT_DIR`) and `discovery.clj` scans it, so the MCP auto-discovers
  a session in a sibling repo.
- Skills corrected (qlik-verify + browser-repl): keycloak `#username`/`#password`/
  `#kc-login`; stern `-o default --color never`; tenant+endpoint+time as the
  PRIMARY correlation join (browser request headers are `{}`); mixed-format
  monitor filter recipe; kube-context->current-context fallback; name-filtered
  pod-lifecycle watch; the connection-flow recon.
- Green: nrepl 38/92 unit + 1/13 integration, browser-repl 6/25; kondo clean
  (the `load-file` test-script "errors" are the repo's existing convention).
- VERIFIED LIVE (after a reload): `nrepl-server-info` `stale? false`, then
  correctly flipped `true` after a later `server.bb` edit; a failing click =
  real error + `*e`; nbb classifies (63456/50323 -> nbb); a bare eval with no
  `:port`/`:await` returns the value (auto-discover + auto-await); registry
  discovery (project-root = the registry dir); `wait-load`/`wait-networkidle`;
  corrected keycloak ids re-login.
- MORE gaps found + fixed while battle-testing:
  - a browser closed underneath the session wedged it (`started?` lied) ->
    `started?`/`pg!`/`status` are liveness-aware (`page-live?` checks
    `.isClosed`), `start!` drops dead handles and relaunches; `:fresh`
    auto-recovers, a `:persistent` zombie surfaces an actionable profile-lock
    error (kill the launcher + relaunch). (browser_repl.cljs)
  - `bb itest` ran nbb in tools/browser-repl, clobbering the real session's
    `.nrepl-port` + leaving stale debris -> it runs in a throwaway temp dir now.
  - a stale/unreachable port file blocked auto-discovery -> `single-live-port`
    counts only CONNECTED ports (server.bb, unit-tested).
  - the launcher wrote `.nrepl-port` into its CWD (e.g. the qlik-trial repo
    root) -> defaults to the tool dir now; the registry handles cross-repo
    discovery (launch.bb; validated: launched from qlik-trial, repo stayed clean).
  - the `monitor` tool forked jq PER LINE, so multi-line JSON (kubectl/stern
    -o json) silently never matched - every k8s `--jq` watch saw nothing. Now
    pipes the source through ONE streaming `jq -c --unbuffered` (parses
    multi-line values); validated live (the pod-lifecycle recipe now streams
    stitch pods). (monitor.bb + monitor/qlik-verify skills; monitor suite 17/73)
- Environmental notes (not reproduced on a clean relaunch): a persistent
  relaunch immediately after an unclean crash can transiently wedge the nbb
  (chromium restore-state), and a crashed session can lose the persisted login
  (cookies not flushed before the crash).
- NOTE: `single-live-port` (GAP C) is unit-tested; its live effect loads on the
  next MCP reload, and is inert until there is stale port debris anyway.

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
- nbb's `describe` reports `versions {"nbb-nrepl" {..} "node" {..}}` - NO `"nbb"`
  or `"clojure"` key. Classify off `nbb-nrepl`/`node` (`discovery/classify-repl-type`),
  else `cljs-port?` is false and auto-await silently never fires.
- nbb's SCI nREPL has NO `load-file`. Load the stdlib with `(require '[browser-repl])`
  (nbb runs in the tool dir whose `nbb.edn` puts `.` on :paths). The launcher does this.
- `require`/loading an npm-module-bearing ns (playwright) is ASYNC over nREPL
  (returns a promise) -> AWAIT it, not just `start!`. The launcher awaits every
  init form (require + configure! + start!), in order.
- nbb writes its own `.nrepl-port` in its CWD on startup. nbb+playwright TRAP
  SIGTERM to close chromium gracefully and can outlive a plain `.destroy`; tear
  the whole descendant tree down (SIGTERM, brief grace, then SIGKILL survivors).
- nrepl MCP auto-discovery walks UP from the MCP's CWD (the FIRST ECA workspace
  root, e.g. qlik-trial) for `.nrepl-port`, so a port file in a sibling repo is
  NOT found that way. NOW ALSO scans a flat REGISTRY dir (`~/.cache/nrepl-ports/`,
  `$NREPL_MCP_PORT_DIR`) that `launch.bb` advertises its port into - so a
  browser-repl session in any repo is auto-discoverable once the MCP has the
  discovery fix loaded. Explicit `:port` + `:await true` stays the reliable
  default (and the only option until the MCP reloads the fix).
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
  stays in `*nre-err*`. Validated on the live nbb:
  failing click -> real "locator.click: Timeout..." + call log, `*e` =
  ex-message; `(p/resolved 42)` -> 42 + `*1`; sync `(throw ...)` -> surfaced.
- "nth not supported on this type function(a,b,c,d){this.cc=a;this.name=b;...}"
  is nbb's SPURIOUS print of any promise-bearing eval (G/O bitmasks => a cljs
  type). The await KICKOFF always produces a promise, so it ALWAYS trips this -
  even when its `do` returns a plain keyword (verified). The launcher/proto
  client tolerate it by ignoring the kickoff result and polling; `eval-code-await`
  now does the same (`spurious-kickoff-error?`). If you STILL see it on a
  `:await true` call: either await was omitted, or the MCP serves stale code ->
  call `nrepl-server-info` (`stale? true` => reload). It is NOT proof of the
  stale-process trap - this session it was a real bug in fresh code.
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
- SDE login: keycloak at `keycloak.<sde>.pte.qlikdev.com` (title "Sign in to
  Qlik"). Fill by ID - `#username`, `#password`, submit `#kc-login` - the
  `[:label ..]`/getByRole specs the skill once listed were unreliable for this
  theme. Creds from the env's `:credentials` if present, else the SDE default
  `rootadmin` / `Qlik1234` (vohi stores none: `:has-credentials false`).
- US Stage login routes through `login-staging.qlik.com` OAuth (likely SSO/MFA)
  -> use a persistent profile, manual login once.
- vohi DI hub is iframe-free at the top level; routes are deep-linkable:
  `/data-integration/{home,create,catalog/all,connections/all,projects/all,monitoring/all,...}`.
- Correlation: cross-layer ID join does NOT work on vohi - confirmed both ways.
  Responses carry no `x-request-id`/`traceparent`, and the captured REQUEST
  headers are `{}` too (`net-where` shows it): the `traceId` in stitch logs
  (e.g. `ae262c2a...`) is injected server-side at the gateway and never reaches
  the browser. PRIMARY join = tenant + endpoint + time. The dual-agent setup is
  still valuable, just on a time/endpoint window, not a shared id.
- nbb's playwright pinned chromium-1223 (install via
  `node_modules/.bin/playwright install chromium`); the MCP used the chrome
  channel.
- ECA plan mode disables `edit_file` (preview only) - toggle it off to edit.
- The nrepl MCP is disabled in `local-config.edn.gpg`; enable it (`:disabled?
  false`, `bb setup.bb`, reload ECA) to drive the REPL agentically.

## File map

- `death-contraptions/tools/nrepl/` - the nrepl MCP. `client.clj` `eval-code-await`
  (spurious-kickoff tolerant); `discovery.clj` `classify-repl-type` (nbb) +
  `registry-port-files`; `server.bb` `await`/`cljs-port?` + `nrepl-server-info`
  tool; `server_test.bb` unit tests; `integration_test.bb` (`bb itest`) live nbb
  round-trip.
- `~/.cache/nrepl-ports/` - flat port registry launchers advertise into so the
  MCP auto-discovers sessions outside its CWD subtree (`$NREPL_MCP_PORT_DIR`).
- `death-contraptions/tools/browser-repl/` - the nbb-REPL powerhouse:
  `browser_repl.cljs` stdlib + `launch.bb` launcher + `nbb.edn`/`package.json`
  + `bb.edn`/`run_tests.bb`/`launch_test.bb`. Pinned local nbb+playwright.
- `death-contraptions/.clj-kondo/config.edn` - promesa `p/let` lint-as.
- `death-contraptions/skills/browser-repl/SKILL.md` - browser-repl playbook.
- `death-contraptions/skills/qlik-verify/SKILL.md` - Qlik UI verification
  playbook (drives the UI via browser-repl).
- `death-contraptions/setup.bb` - servers registry.
- `death-contraptions/local-config.example.edn` - `:servers` (incl. `:nrepl`) +
  `:qlik-verify` schema.
- `death-contraptions/{bb.edn,run_tests.bb,setup_test.bb}` - root setup tests.
- `awesome-qlik-ai/mcp/assessments/playwright-mcp.md` - security assessment for
  the now-removed Playwright MCP (historical; separate repo).

## Resolved decisions

- Playwright MCP: REMOVED (task 5). nbb-REPL is the powerhouse; `tools/playwright`
  deleted, registry/`:default-disabled?`/configs/tests cleaned, qlik-verify
  migrated to browser-repl.
- nrepl MCP: always-on (`:nrepl {}` in the gpg config + example; inert without a
  running nREPL).

## How to resume

The harness is complete and committed; this file is the historical record. The
await nth-quirk was a real `eval-code-await` bug (fixed + guarded by `bb itest`),
nbb classifies correctly (auto-await), and there is a `nrepl-server-info`
staleness probe, a port registry, `wait-load`/`wait-networkidle`,
closed-session recovery, and a monitor that streams multi-line JSON - all
validated live.

To USE it: start a session with `bb tools/browser-repl/launch.bb --mode
persistent --user-data-dir ~/.cache/qlik-verify/chrome-profile`, then drive via
`nrepl-eval` (the launcher advertises its port to the registry, so a bare eval
auto-discovers + auto-awaits; pass `:port` + `:await true` explicitly if you
prefer). See the browser-repl and qlik-verify skills.

Still deliberate (side effects, run by hand): `bb setup.bb` to propagate the
corrected skills + config to the consumers (rewrites desktop/copilot configs,
pushes to claude.ai). The branch is ahead of origin and NOT pushed.
