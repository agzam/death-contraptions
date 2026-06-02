---
name: browser-repl
description: Drive a live browser from a ClojureScript REPL - nbb + Playwright behind an nREPL, evaluated through the nrepl MCP. The powerhouse for verifying/testing web products, scraping, auth automation, bulk downloads, and UI prototyping. Gives liveness (browser + atoms persist across evals), token economy (targeted extracts and scoped aria instead of multi-KB snapshots), live network/console capture for backend correlation, the full Playwright API, and fire-and-poll for long ops. Use when you need to interactively drive or inspect a real browser at low token cost, or correlate UI actions with backend signals. Prefer it over the Playwright MCP for anything iterative; use jxa-browser only for a quick peek at the user's already-open everyday browser.
---

# Browser REPL (browser-repl)

A live `nbb` (ClojureScript on Node) + Playwright session behind an nREPL. You
drive it with ordinary `nrepl-eval` calls, so state is live (the browser,
context, page, and capture atoms persist across evals) and you query exactly
what you need instead of dumping huge snapshots.

    agent -> nrepl MCP (auto-awaits cljs) -> nbb nREPL (holds browser + atoms) -> Playwright -> chromium

Stdlib: `/Users/ryl/GitHub/agzam/death-contraptions/tools/browser-repl/browser_repl.cljs`
Launcher: `/Users/ryl/GitHub/agzam/death-contraptions/tools/browser-repl/launch.bb`

## When to use which browser tool

- jxa-browser - the user's REAL, already-open everyday browser via AppleScript.
  Zero setup, non-invasive. Use for a quick peek at what they're looking at.
  No protocol-level network/devtools, no cross-origin iframes.
- browser-repl (this) - the powerhouse. Iterative driving, targeted extraction,
  live network/console capture, full Playwright API, long-running flows. Default
  choice for verifying/testing/automation.
- Playwright MCP - a fixed-tool, big-snapshot fallback (default-disabled). Only
  if browser-repl is unavailable and you want the curated tool surface.

## Prerequisites

- Deps installed once: `(cd /Users/ryl/GitHub/agzam/death-contraptions/tools/browser-repl && npm install)`
  (pins nbb + playwright; postinstall fetches chromium - reused from the shared
  ms-playwright cache).
- nrepl MCP enabled: `:servers {:nrepl {:disabled? false}}` in
  `local-config.edn.gpg`, then `bb setup.bb`, then reload the MCP. macOS/Linux.

## 1. Start a session

Run the launcher (a background job). It starts the nbb nREPL, requires the
stdlib, applies the mode, launches the browser, writes `.nrepl-port`, and prints
the port. Keep it running; killing it tears the whole tree down.

```sh
bb /Users/ryl/GitHub/agzam/death-contraptions/tools/browser-repl/launch.bb --mode fresh
```

Modes (`--mode`):
- `fresh` (default) - ephemeral context, wipes cookies. Best for most testing.
- `persistent` - reuses a login profile (`--user-data-dir`); log in once, reuse.
- `attach` - connects to an existing browser over CDP (`--cdp-endpoint http://127.0.0.1:9222`);
  real session + network depth, fills jxa's protocol gap.

Other flags: `--port <n>` (else a free ephemeral port), `--headless`,
`--port-file-dir <dir>` (where `.nrepl-port` is written; default CWD).

Note the printed port - you will pass it to `nrepl-eval` explicitly.

## 2. Drive it via nrepl-eval

ALWAYS pass `:port <the launcher's port>` and `:await true`. Auto-discovery only
finds `.nrepl-port` under the MCP's own workspace, so a session in this repo is
usually not auto-discovered; and `:await true` is required so promises resolve
(nbb returns the promise object otherwise - see gotchas).

The stdlib is loaded as the `browser-repl` namespace. Call it qualified
(`browser-repl/goto`) or alias it once per session: `(require '[browser-repl :as b])`.

```clojure
(browser-repl/goto "https://example.com")     ; -> "https://example.com/"
(browser-repl/status)                          ; compact session state
```

Navigation/interaction (each auto-starts the browser if needed):
- `(goto url)` `(current-url)` `(wait-url glob-or-regex)` `(wait-for target)`
- `(click target)` `(fill target s)` `(type-text target s)` `(press target key)`

`target` is a spec resolved at action time (no "built before start" trap):
- `"css selector"` | a Playwright `Locator`
- `[:role "button" {:name "Save"}]` | `[:text s]` | `[:label s]` | `[:placeholder s]` | `[:testid s]`

## 3. Eyes and extraction (token economy)

Prefer targeted reads over snapshots:
- `(aria)` / `(aria target)` - scoped ARIA snapshot, the compact "eyes" (default body).
- `(texts target)` - vector of trimmed text contents. `(text target)` - first one.
- `(count-of target)` `(attrs target "href")` `(visible? target)`
- `(eval-js "document.title")` - escape hatch.

## 4. Network and console capture (correlation)

Opt-in; attach BEFORE the action so nothing is missed.

```clojure
(browser-repl/capture-net! {:url-filter "/api/v1"})  ; record matching requests/responses
(browser-repl/goto "...")
(browser-repl/net-summary)                            ; {:requests N :responses M :by-status {200 ..}}
(browser-repl/net-where "/api/v1/datasets")           ; compact matching entries + correlation headers
```

Request-side correlation keys (`x-request-id`, `traceparent`, ...) are captured
from requests into the `net` atom - join key for a backend watcher (see the
`monitor` skill). `(capture-console!)` + `(console-tail n)` for console output.

## 5. Long ops: fire-and-poll

For slow flows, do not block a tool call. Kick the work off and poll between
turns:

```clojure
(browser-repl/run-job! :crawl (fn [] (p/let [...] result)))  ; returns :crawl immediately
(browser-repl/job :crawl)                                     ; {:status :running|:done|:error ...}
```

## 6. Iterating on the stdlib (live REPL development)

Editing `browser_repl.cljs`? Reload it in place - `defonce` keeps the live
browser and capture atoms:

```clojure
(require '[browser-repl] :reload)   ; nbb has NO load-file; use :reload
(browser-repl/status)               ; still :started? true after reload
```

## Errors and gotchas

- With `:await true`, a failed call is a GENUINE error: `isError`, the real
  message (e.g. the full Playwright timeout + call log), and `*e` bound to it -
  inspect with `(ex-message *e)`. A success sets `*1` for chaining. (`*2`/`*3`
  are not reliable: nbb's history vars are global and the await poll shifts them.)
- "nth not supported on this type function(a,b,c,d){this.cc=a;...}" on a SUCCESS
  call means the result is a raw, un-awaited promise being printed -> you forgot
  `:await true` (or the nrepl MCP is serving stale code; restart it).
- `eval-js` takes a JS EXPRESSION string. A bare `"() => document.title"` returns
  the function (serializes to nil). Use `"document.title"` or an IIFE.
- nbb has no `load-file`; load/reload the stdlib with `(require '[browser-repl] :reload)`.

## Safety

- Local-only: the nREPL evaluates ARBITRARY code (RCE-equivalent). The launcher
  binds 127.0.0.1 only - never expose the port or bind a public interface.
- Fresh-profile default: `fresh` mode persists no cookies/credentials. Use
  `persistent`/`attach` only for deliberate tenant/session testing.
- Mutation gating: on a real or shared tenant, confirm before any create/run/
  delete UI action. Prefix any created artifacts and clean them up.
- Never echo credentials: for logins, `fill` from values pulled out of the
  encrypted config; never print passwords, tokens, or cookies into the chat, and
  do not dump full network payloads that may contain secrets.

## Teardown

Kill the launcher background job (`eca__bg_job action=kill`) - its shutdown hook
SIGTERMs then SIGKILLs the whole tree (nbb + chromium) and removes the
`.nrepl-port` it wrote. `fresh` leaves nothing behind; `persistent` keeps its
profile so the next run skips re-auth.

## API reference (browser-repl ns)

```
configure! start! stop! page started? status api
goto current-url wait-url wait-for
click fill type-text press        ; target := "css" | [:role r {opts}] | [:text/:label/:placeholder/:testid s] | Locator
loc css by-role by-text by-label by-placeholder by-testid
texts text count-of attrs visible? aria eval-js
capture-net! net-summary net-where net-clear!   capture-console! console-tail console-clear!
download! assert! assert-visible   run-job! job
atoms: net console jobs state ; config keys: :mode :headless? :user-data-dir :cdp-endpoint :viewport
```
