# nREPL MCP Server

MCP server connecting LLMs to Clojure/ClojureScript REPLs via nREPL. Supports clj (JVM), bb (Babashka), nbb, and shadow-cljs.

Babashka + built-in bencode. No external deps.

## Tools (2)

- `nrepl-eval` - eval Clojure code. Auto-discovers port. Persistent sessions. Delimiter repair.
- `nrepl-list-ports` - discover running nREPLs with type detection.

For doc lookup use `(doc sym)` or `(-> 'sym resolve meta)` via eval. For loading files use `(load-file "path")` via eval.

## Starting REPLs

```bash
# JVM Clojure (add :nrepl alias to deps.edn with nrepl/nrepl dep)
clojure -M:nrepl

# Babashka
bb nrepl-server 7888

# nbb
npx nbb nrepl-server :port 7888

# shadow-cljs (nREPL port in .shadow-cljs/nrepl.port)
npx shadow-cljs server
```

## Port discovery

Walks CWD upward for `.nrepl-port` and `.shadow-cljs/nrepl.port`. When exactly one port is found, `nrepl-eval` uses it automatically. Results cached for 30s; `nrepl-list-ports` always scans fresh.

## Sessions

Each port gets a cloned nREPL session. Defs, namespace switches, `*1`/`*e` persist across calls. Session IDs persisted to `/tmp/eca-nrepl-sessions/`. Stale sessions (REPL restart) auto-detected and recloned.

## Error recovery

Broken connections auto-reconnect on next eval. Stale sessions auto-reclone. Discovery cache invalidated on connection failure.

## ClojureScript via shadow-cljs

shadow-cljs nREPL starts in Clojure mode. Switch to CLJS:
```clojure
(shadow.cljs.devtools.api/repl :app)
```
Switch back: `:cljs/quit`

## Tests

```bash
cd tools/nrepl && bb test
```

## Setup

Registered in `setup.bb`. Run `bb setup.bb` to propagate. Disabled by default in `local-config.example.edn`; enable with `{:servers {:nrepl {}}}`.
