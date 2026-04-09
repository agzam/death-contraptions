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
- [x] 32 tests, 107 assertions

## Should do

- [ ] #+begin_src block handling in parser (markers stripped but content kept)
- [ ] :roam_refs: property - parsed but not exposed or indexed

## Nice to have

- [ ] annotator.clj - entity recognition, disambiguation, three-layer output
- [ ] notes-annotate tool + Emacs UI for interactive entity linking
- [ ] Incremental index compaction
- [ ] Emacs integration package (elisp UI: org-roam-mcp-search with minibuffer)
- [ ] Index other corpora: HN comments, Reddit history, GitHub org source code
