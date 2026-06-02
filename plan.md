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

UNCOMMITTED, separate repo: `awesome-qlik-ai/mcp/assessments/playwright-mcp.md`
(goes via that repo's branch/PR workflow, not a direct main commit).

PROTOTYPE (scratch, not committed): `~/.cache/qlik-verify/nbb-proto/` - `nre.bb`
(bb nREPL client with `--await`), `FINDINGS.md`. Proved nbb+Playwright+nREPL
liveness, token economy (20 connection names in 534 chars vs a multi-KB
snapshot), live response capture (37 `/api/v1` responses into an atom). A live
nbb nREPL may still be running on port 4321 (ephemeral bg job).

NEXT (tasks 3-6):
3. browser-repl stdlib + launcher (`death-contraptions/tools/browser-repl/`):
   `browser_repl.cljs` (modes fresh/persistent/attach; nav/click/fill; targeted
   extractors; `aria` eyes; net+console capture into atoms with correlation;
   download/wait/assert; promesa), a launcher (`nbb nrepl-server` + stdlib +
   mode, writes `.nrepl-port`), `package.json`/`nbb.edn`. Reuse nbb-proto
   patterns.
4. browser-repl skill: modes, stdlib API, jxa-vs-Playwright routing hint,
   fire-and-poll for long ops, safety (local-only RCE, fresh-profile default,
   mutation gating, no echoing creds), teardown.
5. Wire + reconcile: enable the nrepl MCP as the always-on channel (config +
   `bb setup.bb` + reload); decide the Playwright MCP's fate (keep disabled
   fallback vs remove `tools/playwright`); update example config + docs.
6. Validate e2e on vohi via the new path (login + targeted extract + network
   correlation) with plain `nrepl-eval`.

## Hard-won facts & gotchas (do not relearn)

- nbb nREPL returns promises, not values -> `eval-code-await` wraps + polls.
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

Point a fresh session at this file. Start at task 3 (browser-repl stdlib +
launcher), reusing the `~/.cache/qlik-verify/nbb-proto` patterns and the
validated `eval-code-await` path. Then 4-6.
