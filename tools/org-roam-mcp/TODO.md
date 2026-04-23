# org-roam-mcp - TODO

## Implemented

- [x] deps.edn, config.edn, start.sh (cd to SCRIPT_DIR, stderr to server.log)
- [x] util.clj, parser.clj, embeddings.clj, index.clj, secondary.clj
- [x] core.clj - MCP server, 7 tools, JSON-RPC stdio loop
- [x] watcher.clj - native macOS FSEvents via io.methvin/directory-watcher 0.18.0
- [x] emacs.clj - emacsclient wrapper with timeout and error handling
- [x] notes-create - three modes: journal (vulpea), heading (parent-id), file (standalone)
- [x] notes-edit tool - append/replace mode, org-id-find for immediate lookup
- [x] Registered in ~/.config/eca/config.json, hot-reloadable via mcp/startServer
- [x] Full index: 2385 chunks, 1931 embeddable, ~17s build, <0.5s load
- [x] Graph-enriched search - title match fans out through backlinks + outgoing
- [x] Multi-hop graph search - depth param (1 or 2), score decay per hop
- [x] Title/alias boosting (score 1.0), file-level TOC enrichment
- [x] Short chunk filtering (<30 chars excluded from HNSW, kept in structural)
- [x] Tag-link IDs preserved as outgoing links (+268 edges)
- [x] Full-file link extraction for file-level chunks (+561 targets)
- [x] Non-embedded chunks in secondary indices
- [x] Graceful Ollama fallback, ConcurrentModification fix, classloader fix
- [x] Async catch-up on startup - index loads immediately, catch-up runs in background future
- [x] Index save debouncing - watcher saves at most every 30s via ScheduledExecutorService
- [x] Ollama keep_alive "30m" on all embed API calls to prevent model unloading
- [x] embed-batch consistency - :as :string + :throw-exceptions? false across all embed fns
- [x] Graceful recovery on corrupt HNSW index - load-index catches deserialization errors, deletes corrupt file, falls back to full rebuild
- [x] Clean JVM exit on stdin EOF - stdio loop wraps in try/finally + System/exit so non-daemon executor threads don't keep the JVM alive after the MCP client disconnects; watcher stop fn drains processor + save executors before the shutdown hook's final save-index! so they can't race on meta.edn
- [x] 32 tests, 107 assertions

## Bugs

- [ ] Empty error messages everywhere - `.getMessage` returns nil for many Java exceptions (ConnectException, NullPointerException, nested ex-info). Use `(or (ex-message e) (str (class e)))` in all catch blocks: core.clj:321, watcher.clj:26, and anywhere else `.getMessage` is used raw.
- [ ] notes-read fails for non-embedded nodes - resolves title via secondary index, gets node-id, then demands HNSW item (which doesn't exist for short chunks or embed-failed nodes). notes-read should fall back to secondary metadata + direct file read via emacsclient/org-id-find.
- [ ] notes-search crashes when Ollama is down instead of degrading - graph-enriched search (title match, backlinks, outgoing) doesn't need embeddings but the code calls embed-query unconditionally before any graph logic. Should catch embed failure, skip semantic portion, return graph+structural results with a `:degraded` flag.
- [ ] Watcher removes old HNSW items before re-embedding succeeds - process-file-change! deletes existing items (watcher.clj:66-71) then attempts embed. On embed failure, nodes are silently lost. Should defer removal until new items are ready, or restore old items on failure.
- [ ] Error responses lack recovery hints - "Node not in index" should suggest "try notes-reindex"; embed failures should mention Ollama URL and suggest checking connectivity. Agents can't guess corrective actions.

## Should do

- [ ] #+begin_src block handling in parser (markers stripped but content kept)
- [ ] :roam_refs: property - parsed but not exposed or indexed

## Nice to have

- [ ] annotator.clj - entity recognition, disambiguation, three-layer output
- [ ] notes-annotate tool + Emacs UI for interactive entity linking
- [ ] Incremental index compaction
- [ ] Emacs integration package (elisp UI: org-roam-mcp-search with minibuffer)
- [ ] Index other corpora: HN comments, Reddit history, GitHub org source code
