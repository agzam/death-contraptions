# org-roam-mcp - Specification

MCP server exposing semantic search and manipulation of an Org-Roam/Vulpea
knowledge base to AI-agentic workflows.

## 1. Architecture Overview

```
MBP (local)                                 Arch (arch-machina)
+------------------------------------+      +---------------------+
| ECA (stdio) <-> org-roam-mcp (JVM) |      |                     |
|                                    | HTTP | ollama serve        |
|  - hnsw index (in-memory + file)   |----->| nomic-embed-text    |
|  - file watcher on ~/Sync/org/     |:11434| CUDA / RTX 3070     |
|  - emacsclient for note CRUD       |      |                     |
+------------------------------------+      +---------------------+

Storage (local):
  ~/Sync/org/                          .org files (source of truth, synced)
  ~/.emacs.d/.local/cache/org-roam-mcp/
    index.hnsw                         serialized hnsw index (~50-150 MB)
    meta.edn                           index metadata (version, timestamp)
```

Single JVM process started by ECA, communicates over stdio (JSON-RPC).
Holds the vector index in memory. Calls Ollama over HTTP for embeddings.
Calls emacsclient for note creation/journal operations.

## 2. Dependencies

```clojure
;; deps.edn
{:paths ["src" "test"]
 :deps
 {org.clojure/clojure                {:mvn/version "1.12.0"}
  ;; Vector index - JDK17+ variant with Vector API (SIMD) support
  com.github.jelmerk/hnswlib-core-jdk17 {:mvn/version "1.2.1"}
  ;; HTTP client for Ollama
  ;; NOTE: coordinate is hato/hato, NOT metosin/hato
  hato/hato                          {:mvn/version "1.0.0"}
  ;; JSON
  cheshire/cheshire                  {:mvn/version "5.13.0"}
  ;; Native file watcher (macOS FSEvents, Linux inotify)
  io.methvin/directory-watcher       {:mvn/version "0.18.0"}}

 :aliases
 {:run {:main-opts ["-m" "org-roam-mcp.core"]}
  :dev {:extra-deps {nrepl/nrepl       {:mvn/version "1.3.1"}
                     cider/cider-nrepl {:mvn/version "0.50.2"}}}}}
```

Java 25 is available locally; hnswlib-core-jdk17 will use SIMD-accelerated
distance computations automatically.

### Implementation notes

- HnswIndex class is at `com.github.jelmerk.hnswlib.core.hnsw.HnswIndex`
  (note the `.hnsw` sub-package)
- DistanceFunctions, SearchResult, Item are at `com.github.jelmerk.hnswlib.core.*`
- Search method is `.findNearest` (not `.findNeighbors`)
- Builder requires `.withRemoveEnabled()` for `.remove()` to work
- Index load requires passing `(clojure.lang.RT/baseLoader)` as classloader
  so Java deserialization can find the deftype class
- Avoid transient maps inside `doseq` - `assoc!` returns are discarded,
  causing data loss after the initial 8-entry array bucket fills
- Ollama's context-length errors require progressive truncation fallback:
  some technical content tokenizes at 2+ tokens/char, exceeding the 8192
  token limit even at ~4K characters
- Tag-link IDs: `extract-tag-links` returns `{:tags :tag-link-ids :remaining}`
  so vulpea tag-links are captured as both tags AND outgoing links in the
  file-level chunk
- Full-file link extraction: file-level chunks capture ALL `[[id:...]]`
  links from the entire file text, not just the preamble. Links under
  non-ID headings are no longer lost.
- Non-embedded secondary indices: `sec/build!` accepts optional
  `extra-chunks` for nodes too short for HNSW but structurally relevant.
  Secondary indices cover ALL nodes.
- Async startup: index loads from disk immediately, catch-up runs in a
  background `future`. Server is queryable with possibly-stale results
  until catch-up completes.
- Native file watcher: replaced `java.nio.file.WatchService` with
  `io.methvin/directory-watcher` 0.18.0 which uses macOS FSEvents
  natively (and Linux inotify). Simpler code, more reliable events.
- Ollama `keep_alive`: all embed API calls send `keep_alive: "30m"` to
  prevent Ollama from unloading the model between requests.
- Embed consistency: all embed functions (`embed-batch`, `embed-query`,
  `embed-single-with-truncation`) use `:as :string` + `:throw-exceptions?
  false` for consistent error handling.
- Multi-hop graph search: `graph-collect` accepts a depth param (1 or 2).
  Score decays by hop: title-match=1.0, hop1 backlink=0.95, hop1
  outgoing=0.9, hop2=0.85.

## 3. Configuration

File: `config.edn` (in project root, or `~/.config/org-roam-mcp/config.edn`)

All paths in config use `~/` notation. The code must expand these to
absolute paths at startup using `System/getProperty "user.home"`:

```clojure
(defn expand-home [path]
  (if (str/starts-with? path "~/")
    (str (System/getProperty "user.home") (subs path 1))
    path))
```

This applies to `:org-dir`, `:index-dir`, and any paths in tool arguments.

```clojure
{;; Ollama embedding endpoint (via SSH tunnel or direct)
 :ollama-url   "http://localhost:11434"
 :model        "nomic-embed-text"

 ;; Notes directory (source of truth)
 :org-dir      "~/Sync/org"

 ;; Patterns to exclude (matched against relative path from org-dir)
 :exclude      ["data/" ".sync/" ".git/"]

 ;; Persisted index location
 :index-dir    "~/.emacs.d/.local/cache/org-roam-mcp"

 ;; HNSW index parameters
 :hnsw
 {:dimensions       768          ;; nomic-embed-text output dims
  :max-items        100000       ;; capacity ceiling
  :m                16           ;; bi-directional links per node
  :ef               200          ;; search-time accuracy parameter
  :ef-construction  200}         ;; build-time accuracy parameter

 ;; File watcher
 :watch-debounce-ms 2000         ;; debounce rapid saves

 ;; Embedding batch size (for initial indexing)
 :embed-batch-size  32}
```

## 4. Data Model

### 4.1 What we index

Each indexable unit is a "chunk" - a section of an org file anchored by an
`:ID:` property. Two types:

1. File-level chunk: the file's top-level properties + title + body text
   before the first heading.
2. Heading-level chunk: a heading with its own `:ID:` property + body text
   up to the next heading of same or higher level.

A single .org file may produce 1 to N chunks (one per `:ID:` node).

Chunks WITHOUT an `:ID:` property are skipped. They are not addressable
by org-roam and cannot be linked to.

### 4.2 Chunk record

```clojure
{:node-id    "429A65A5-7DA8-4245-860D-3DAEEA57226B"  ;; org :ID:
 :title      "Clojure"                                ;; #+title or heading text
 :file-path  "~/Sync/org/clojure.org"                 ;; resolved to absolute at runtime
 :level      0                                        ;; 0 = file-level, 1+ = heading
 :tags       ["PLs" "JVM"]                            ;; filetags + heading tags
 :aliases    ["cljs"]                                 ;; :ROAM_ALIASES:
 :outgoing-links #{"429A65A5..." "BC467892..."}       ;; node IDs this chunk links to
 :content    "Clojure is a dynamic..."                ;; text for embedding
 :mtime      1712345678                               ;; file mtime at index time
 :checksum   "a1b2c3..."}                             ;; SHA-256 of content (for drift detection)
```

### 4.3 HNSW item

Implements `com.github.jelmerk.hnswlib.core.Item<String, float[]>`:

```clojure
;; Java class (generated via deftype or gen-class)
;; Fields: id (String), vector (float[]), dimensions (int),
;;         title (String), file-path (String), level (int),
;;         tags (String), content-preview (String),
;;         outgoing-links (String)  ;; comma-separated node IDs
;;
;; The Item carries display metadata so search results can be returned
;; without a secondary lookup. Full note content is read from the .org
;; file on demand (notes-read tool).
```

Serialized with the index (Java Serializable). Content preview is truncated
to ~500 chars to keep the index file manageable.

### 4.4 Secondary indices (in-memory, derived from HNSW items)

Built on index load, updated incrementally alongside the HNSW index.

```clojure
;; Backlink index: who links to node X?
;; {target-node-id -> #{source-node-id ...}}
(def backlinks (atom {}))

;; Title/alias lookup: for entity recognition in text
;; {lowercase-string -> [{:node-id "..." :title "..." :type :title/:alias}]}
;; Multiple entries per key when ambiguous (e.g., two people named "Dan")
(def title-index (atom {}))

;; Tag index: nodes by tag
;; {tag-string -> #{node-id ...}}
(def tag-index (atom {}))
```

These are cheap to maintain: a full rebuild from 50K items takes
milliseconds (just iterating stored metadata, no Ollama calls).

## 5. Org File Parsing

### 5.1 Parser requirements

The parser operates on raw .org file text. It does NOT depend on Emacs or
org-element. It extracts only what the indexer needs.

### 5.2 Parsing rules

1. Property drawers: blocks delimited by `:PROPERTIES:` / `:END:`.
   Extract key-value pairs. Keys are case-insensitive.

2. File-level metadata:
   - `:ID:` from the first property drawer (must appear before any heading)
   - `#+title:` keyword (case-insensitive prefix match)
   - `#+filetags:` keyword - colon-delimited, e.g., `:work-notes:` -> `["work-notes"]`
   - `#+startup:` (ignored, but don't treat as content)

3. Headings: lines matching `^(\*+)\s+(.+)$`
   - Level = count of `*` characters
   - Heading text = remainder after stars and whitespace
   - Tags at end of heading: `:tag1:tag2:` pattern
   - Property drawer immediately following the heading line

4. Content extraction per chunk:
   - File-level: text between title/properties and first heading
   - Heading-level: text from heading line to next heading of same or higher level
   - Strip: property drawers, `#+keyword:` lines, `#+begin_`/`#+end_` block markers
   - Preserve: paragraph text, list items, inline code, link descriptions
   - Links `[[id:UUID][Description]]`: extract Description as plain text
   - Links `[[url][Description]]`: extract Description as plain text

5. Exclude patterns: skip files whose relative path matches any entry in
   `:exclude` config (prefix match).

### 5.3 Content for embedding

The text sent to Ollama for embedding is composed as:

```
{title}
{tags joined by ", " if any}
{body text stripped of markup}
```

Title is prepended so that the embedding captures the topic even for
short body sections. Tags provide categorical signal.

Max chunk size: 8192 tokens (~6000 words). nomic-embed-text has an 8192
token context window. Chunks exceeding this are truncated (should be rare
for well-structured notes).

## 6. Embedding Pipeline

### 6.1 Ollama client

Single function: embed text(s) via `POST /api/embed`.

```clojure
(defn embed
  "Returns vector of float arrays. Supports batching."
  [config texts]
  ;; POST {:model model :input texts}
  ;; -> {:embeddings [[f1 f2 ...] [f1 f2 ...]]}
  ;; Convert each inner vector to float-array
  )
```

Uses the `/api/embed` endpoint (not legacy `/api/embeddings`):
- Supports batch input (vector of strings)
- Returns float32 (not float64)
- Returns L2-normalized vectors (unit length)
- Supports automatic truncation

### 6.2 Batching strategy

During initial indexing, chunks are batched in groups of `:embed-batch-size`
(default 32) to reduce HTTP round-trips. Each batch is a single POST with
an array of input strings.

During incremental updates, individual chunks are embedded one at a time
(latency is fine for single-file re-indexing).

### 6.3 Error handling

- Ollama unreachable: log warning, skip embedding, retry on next cycle.
  The server remains functional for queries against the existing index.
- Model not loaded: Ollama auto-loads on first request; may take a few
  seconds on cold start. Set `keep_alive` to `"30m"` to keep model warm.
- Timeout: 30s per batch request. Log and retry once.

## 7. HNSW Index Management

### 7.1 Index lifecycle

```
                     ┌─────────────────────────────┐
                     │      Server starts           │
                     └─────────────┬───────────────┘
                                   │
                          ┌────────▼────────┐
                          │ index.hnsw      │
                          │ exists on disk? │
                          └───┬─────────┬───┘
                           yes│         │no
                    ┌─────────▼──┐  ┌───▼──────────┐
                    │ Load from  │  │ Full scan:    │
                    │ disk       │  │ parse all     │
                    └─────────┬──┘  │ .org files,   │
                              │     │ embed, build  │
                    ┌─────────▼──┐  │ index, save   │
                    │ Catch-up:  │  └───┬───────────┘
                    │ scan for   │      │
                    │ mtime >    │      │
                    │ indexed    │      │
                    └─────────┬──┘      │
                              │         │
                     ┌────────▼─────────▼──┐
                     │  Index ready         │
                     │  Start file watcher  │
                     │  Accept MCP requests │
                     └──────────────────────┘
```

### 7.2 Initialization

```clojure
(defn create-index [config]
  (-> (HnswIndex/newBuilder
        (int (:dimensions hnsw-config))
        DistanceFunctions/FLOAT_COSINE_DISTANCE
        (int (:max-items hnsw-config)))
      (.withM (int (:m hnsw-config)))
      (.withEf (int (:ef hnsw-config)))
      (.withEfConstruction (int (:ef-construction hnsw-config)))
      (.build)))
```

### 7.3 Persistence

- Save: `(.save index (io/file index-path))` after batch operations
  or debounced after incremental updates.
- Load: `(HnswIndex/load (io/file index-path))`
- Save is atomic: write to temp file, then rename (prevents corruption
  on crash).

### 7.4 Operations

- Add/update: `.add` with the new item. If an item with the same ID
  exists, it is replaced (hnswlib supports this).
- Delete: `.remove` by ID (experimental in hnswlib, but sufficient
  for note deletion).
- Search: `.findNeighbors(query-vector, k)` returns
  `List<SearchResult<Item, Float>>` sorted by distance ascending
  (0.0 = identical, 2.0 = maximally dissimilar for cosine).

### 7.5 Metadata sidecar

A small `meta.edn` file alongside `index.hnsw`:

```clojure
{:version       1                        ;; schema version
 :created-at    "2026-04-08T..."
 :last-indexed  "2026-04-08T..."
 :model         "nomic-embed-text"       ;; embedding model used
 :dimensions    768
 :chunk-count   4523
 :file-mtimes   {"clojure.org" 1712345678
                 "bias.org"    1712345600
                 ...}}                   ;; for catch-up on restart
```

The `:file-mtimes` map enables efficient catch-up: on startup, compare
each file's current mtime against the recorded value. Only re-index files
where mtime differs.

If `:model` or `:dimensions` change (e.g., switching embedding models),
the index must be fully rebuilt.

## 8. File Watching

### 8.1 Mechanism

Uses `io.methvin/directory-watcher` 0.18.0 which provides native OS
event APIs: macOS FSEvents and Linux inotify. Replaces the original
`java.nio.file.WatchService` approach which fell back to polling on macOS.

The watcher is configured via `DirectoryWatcher.builder` with a single
`.path` call for the org directory root - recursive watching is built in.

### Implementation notes

- Segment-aware exclude matching: `excluded-dir?` splits the relative
  path into segments, matching any segment (e.g., `data/` excludes both
  `data/` at root AND `daily/data/` nested).
- Per-file debounce via `ScheduledExecutorService`: each file path gets
  a delayed task; new events for the same path cancel and reschedule.
  Default 2s.
- Index save debouncing: a separate `debounced-save` function coalesces
  saves to at most every 30s during bulk watcher updates, avoiding
  excessive disk I/O.
- Graceful shutdown: `start!` returns a stop function that calls
  `.close` on the DirectoryWatcher.

### 8.2 Event handling

```
File event -> debounce (2s) -> parse file -> diff chunks ->
  for each changed/new chunk:
    embed via Ollama -> update hnsw index
  for each removed chunk:
    remove from hnsw index
  save index to disk (debounced, max every 30s)
```

Debouncing: Syncthing and editors may write files multiple times in
rapid succession. Buffer events per file path, only process after
no new events for `:watch-debounce-ms` (default 2s).

### 8.3 Diff strategy

For a changed file, compare the set of chunk IDs + checksums against
what is stored in the index:

- New chunk ID: embed and add
- Existing chunk ID, different checksum: re-embed and update
- Missing chunk ID (was in index, not in file): remove from index

This avoids re-embedding unchanged sections of a file.

## 9. MCP Protocol

### 9.1 Transport

JSON-RPC 2.0 over stdio. Line-delimited JSON (one JSON object per line).
Same pattern as existing Babashka MCP servers in the project.

Protocol version: `2024-11-05`

### 9.2 Server info

```clojure
{:name "org-roam-mcp" :version "0.1.0"}
```

Capabilities: `{:tools {}}`

### 9.3 Tool definitions

#### notes-search

Semantic search with optional structural filters.

```clojure
{:name "notes-search"
 :description
 "Search notes semantically with optional filters. Finds notes whose
  content is conceptually related to the query. Filters narrow results
  by tags, linked people/topics, or combinations.
  Examples:
  - 'distributed consensus' - pure semantic search
  - query='database work' tags=['DB'] - semantic + tag filter
  - query='project updates' links=['Dan Smith'] - notes related to
    'project updates' that also link to the 'Dan Smith' node
  - tags=['DB'] links=['Dan Smith'] - no semantic query, just
    structural: notes tagged DB that link to Dan"
 :inputSchema
 {:type "object"
  :properties
  {:query {:type "string"
           :description "Semantic search query (optional if filters provided)"}
   :tags  {:type "array"
           :items {:type "string"}
           :description "Filter: only notes with ALL of these tags"}
   :links {:type "array"
           :items {:type "string"}
           :description "Filter: only notes that link to ALL of these
                         nodes (by title, alias, or node ID)"}
   :k     {:type "integer"
           :description "Max results to return (default: 10)"}}}}
```

Implementation strategy for compound queries:
- If `:query` is provided: embed it, search HNSW for top `k * 10`
  results (over-fetch), then apply tag/link filters, return top `k`.
- If only filters (no `:query`): scan the tag-index and backlink-index
  directly, intersect results, return matching chunks with metadata.
- Filters use AND logic: a result must match ALL specified tags AND
  link to ALL specified nodes.

Response format:

```clojure
{:content
 [{:type "text"
   :text (json/generate-string
          {:results
           [{:node-id "..."
             :title "Database normalization notes"
             :file "~/Sync/org/db-normalization.org"
             :level 0
             :tags ["DB" "architecture"]
             :excerpt "Dan proposed we normalize the schema..."
             :score 0.87}
            ...]})}]}
```

#### notes-backlinks

Find all notes that link to a given node.

```clojure
{:name "notes-backlinks"
 :description
 "Find all notes that contain a link to the specified node.
  Equivalent to org-roam's backlinks buffer. Accepts node title,
  alias, or ID."
 :inputSchema
 {:type "object"
  :properties
  {:node {:type "string"
          :description "Node title, alias, or UUID to find backlinks for"}}
  :required ["node"]}}
```

Implementation: look up node in title-index to resolve to node-id,
then query backlinks index for all source chunks. Return with metadata.

Response format:

```clojure
{:results
 [{:node-id "..."
   :title "2026-03-15 Saturday"
   :file "~/Sync/org/daily/2026-03-work-notes.org"
   :level 1
   :excerpt "Met with [[id:...][Dan Smith]] to discuss..."
   :link-context "...the paragraph surrounding the link..."}
  ...]}
```

#### notes-read

Read full content of a specific note.

```clojure
{:name "notes-read"
 :description
 "Read the full content of a specific note by its org-roam node ID,
  title, or file path. Use after notes-search to get complete content."
 :inputSchema
 {:type "object"
  :properties
  {:id   {:type "string"
          :description "Org-roam node UUID"}
   :title {:type "string"
           :description "Note title (alternative to id)"}
   :path {:type "string"
          :description "File path (alternative to id)"}}}}
```

Response: full file content as text. If the node is a heading-level
chunk (level > 0), returns only the subtree under that heading.

#### notes-annotate

Analyze text and identify entities that match existing notes,
producing link annotations with disambiguation data.

```clojure
{:name "notes-annotate"
 :description
 "Given text, identify words and phrases that match existing note
  titles or aliases. Returns annotated spans with candidate links.
  When multiple notes match the same text (ambiguity), all candidates
  are returned with similarity scores for disambiguation.
  Use this to turn plain text into richly linked org-mode content."
 :inputSchema
 {:type "object"
  :properties
  {:text {:type "string"
          :description "Plain text to annotate with note links"}
   :context {:type "string"
             :description "Optional surrounding context to help
                           disambiguation (e.g., the rest of the
                           document, or 'this is a work journal')"}}
  :required ["text"]}}
```

Implementation:

1. Scan text against the title-index using longest-match-first
   strategy. For each position in the text, try matching the longest
   known title/alias first, then shorter ones. Avoid overlapping spans.

2. For each matched span, collect all candidate nodes from the
   title-index (there may be multiple for ambiguous names).

3. If ambiguous AND `:context` is provided: embed the context and
   compute similarity against each candidate's embedding to rank them.

4. Return structured annotation data.

Response format:

```clojure
{:original-text "Dan proposed normalizing the DB for proper ACID rules"
 :annotations
 [{:span [0 3]
   :matched-text "Dan"
   :ambiguous true
   :candidates
   [{:node-id "A1B2..."
     :title "Dan Smith"
     :score 0.95        ;; context-based disambiguation score (if context provided)
     :link "[[id:A1B2...][Dan Smith]]"}
    {:node-id "C3D4..."
     :title "Dan Jones"
     :score 0.72
     :link "[[id:C3D4...][Dan Jones]]"}]}
  {:span [30 32]
   :matched-text "DB"
   :ambiguous false
   :candidates
   [{:node-id "E5F6..."
     :title "Database"
     :score 1.0
     :link "[[id:E5F6...][Database]]"}]}
  {:span [44 48]
   :matched-text "ACID"
   :ambiguous false
   :candidates
   [{:node-id "G7H8..."
     :title "ACID properties"
     :score 1.0
     :link "[[id:G7H8...][ACID properties]]"}]}]

 ;; Pre-rendered version with top candidates applied (for quick use)
 :annotated-text
 "[[id:A1B2...][Dan Smith]] proposed normalizing the [[id:E5F6...][DB]] for proper [[id:G7H8...][ACID]] rules"

 ;; Same but only unambiguous links applied, ambiguous left as plain text
 :safe-annotated-text
 "Dan proposed normalizing the [[id:E5F6...][DB]] for proper [[id:G7H8...][ACID]] rules"}
```

The response provides three layers for the consumer:
- `:annotations` - full structured data for UI-driven disambiguation
- `:annotated-text` - best-guess version (top candidate for each span)
- `:safe-annotated-text` - conservative version (only unambiguous links)

An Emacs UI (future) could walk through `:annotations` where
`:ambiguous` is true, presenting candidates via completing-read.
The AI agent can use `:annotated-text` directly or reason about
the ambiguous candidates using conversation context.

#### notes-create

Create a new note or insert content at a specific location.

```clojure
{:name "notes-create"
 :description
 "Create or insert note content. Supports multiple modes:
  - 'file': create a new standalone .org file (default)
  - 'heading': insert a new heading under an existing node
  - 'journal': create/append to a journal entry for a date
  Each mode uses emacsclient to ensure proper :ID: generation,
  vulpea indexing, and correct file structure."
 :inputSchema
 {:type "object"
  :properties
  {:mode    {:type "string"
             :enum ["file" "heading" "journal"]
             :description "Creation mode (default: file)"}
   :title   {:type "string"
             :description "Note/heading title (required for file and heading modes)"}
   :content {:type "string"
             :description "Body content to insert"}
   :tags    {:type "array"
             :items {:type "string"}
             :description "Tags as note titles to link (file mode only)"}

   ;; heading mode params
   :parent-id {:type "string"
               :description "Node ID under which to insert the heading
                             (heading mode only)"}
   :level     {:type "integer"
               :description "Heading level, e.g., 2 for '** heading'
                             (heading mode, default: one below parent)"}

   ;; journal mode params
   :date   {:type "string"
            :description "YYYY-MM-DD (journal mode, default: today)"}
   :journal-type {:type "string"
                  :enum ["work" "personal"]
                  :description "Journal type (journal mode, default: work)"}
   :heading {:type "string"
             :description "Sub-heading under the day entry
                           (journal mode, optional)"}}
  :required ["content"]}}
```

Implementation details per mode:

file mode:
- emacsclient creates a new file via org-roam capture template
- Sets #+title, generates :ID:, inserts content
- If :tags provided, inserts tag-links after title line
- File watcher picks up the new file for indexing

heading mode:
- emacsclient navigates to :parent-id node
- Inserts a new heading at the specified :level
- Generates a new :ID: property for the heading
- Inserts :content under the heading
- Saves the buffer

journal mode:
- emacsclient calls vulpea-journal+ with the date and type
- If the day entry (level-1 heading) does not exist, it is created
- If :heading is provided, inserts a level-2 sub-heading under the day
- Appends :content at the insertion point
- Insertion point: end of the day's subtree (before next day heading),
  so new entries appear chronologically at the bottom

Response for all modes:

```clojure
{:node-id "..."           ;; the created/modified node's ID
 :file    "~/Sync/org/..."
 :title   "..."
 :mode    "journal"}
```

#### notes-search-related

Find notes semantically related to an existing note.

```clojure
{:name "notes-search-related"
 :description
 "Given an existing note (by ID or title), find other notes that
  are semantically related to it. Unlike backlinks, this finds
  conceptual neighbors even if no explicit links exist."
 :inputSchema
 {:type "object"
  :properties
  {:node {:type "string"
          :description "Node title, alias, or UUID"}
   :k    {:type "integer"
          :description "Max results (default: 10)"}}
  :required ["node"]}}
```

Implementation: look up the node's embedding in the index, use it
directly as the query vector (no Ollama call needed). Exclude the
node itself from results.

#### notes-reindex

Trigger re-indexing.

```clojure
{:name "notes-reindex"
 :description
 "Trigger re-indexing of notes. Without arguments, performs a full
  scan comparing mtimes. With a path, re-indexes only that file.
  Use after bulk changes or if the index seems stale."
 :inputSchema
 {:type "object"
  :properties
  {:path {:type "string"
          :description "File to re-index (optional, omit for full scan)"}}}}
```

## 10. Emacsclient Integration

### 10.1 Invocation

```clojure
(defn emacsclient-eval
  "Evaluate elisp via emacsclient. Returns the printed result."
  [elisp-form]
  ;; ["emacsclient" "--eval" "(progn ...)"]
  ;; Capture stdout, parse result.
  ;; Timeout: 10s
  )
```

### 10.2 File mode - new standalone note

```elisp
(progn
  (require 'vulpea)
  (let* ((note (vulpea-create "Note Title"
                 :tags '("tag1" "tag2")
                 :body "Content here"))
         (id (vulpea-note-id note))
         (path (vulpea-note-path note)))
    (with-current-buffer (find-file-noselect path)
      (save-buffer))
    (format "(%s %s)" id path)))
```

### 10.3 Heading mode - insert under existing node

```elisp
(progn
  (require 'org-roam)
  (let* ((parent-id "PARENT-UUID")
         (node (org-roam-node-from-id parent-id)))
    (org-roam-node-visit node)
    ;; Move to end of the parent's subtree
    (org-end-of-subtree t)
    ;; Insert new heading at correct level
    (let ((new-id (org-id-new)))
      (insert "\n** New Heading Title\n"
              ":PROPERTIES:\n"
              ":ID:       " new-id "\n"
              ":END:\n\n"
              "Content here\n")
      (save-buffer)
      (format "(%s %s)" new-id (buffer-file-name)))))
```

### 10.4 Journal mode - precise insertion

Journal insertion must respect the monthly-file, daily-heading structure.
The day heading is level 1; sub-entries are level 2+.

```elisp
(progn
  (require 'vulpea-journal)
  ;; Step 1: navigate to (or create) the day entry
  (let ((date (encode-time 0 0 0 DD MM YYYY)))
    (vulpea-journal+ 'work date))

  ;; Step 2: find the correct insertion point
  ;; We are now in the journal buffer, at the day heading.
  ;; Move to end of this day's subtree (before next day or EOF).
  (org-end-of-subtree t)

  ;; Step 3: insert sub-heading with content
  (let ((new-id (org-id-new)))
    (insert "\n** Sub-heading Title\n"
            ":PROPERTIES:\n"
            ":ID:       " new-id "\n"
            ":END:\n\n"
            "Content body here\n")
    (save-buffer)
    (format "(%s %s %s)"
            new-id
            (buffer-file-name)
            (org-entry-get nil "ID" t))))  ;; parent day heading ID
```

Key invariants for journal insertion:
- Day headings are level 1: `* 2026-04-08 Tuesday`
- Sub-entries are level 2+: `** Meeting notes`
- Each day heading has its own :ID: and :CREATED: properties
- New entries go at the END of the day's subtree, so they appear
  chronologically after existing entries for that day
- If no day heading exists for the date, vulpea-journal+ creates it
  (including the :ID: and :CREATED: properties)
- The monthly file is created automatically if it does not exist

### 10.5 Content-only append (no new heading)

For appending text to an existing node without creating a sub-heading:

```elisp
(progn
  (require 'org-roam)
  (let ((node (org-roam-node-from-id "TARGET-UUID")))
    (org-roam-node-visit node)
    (org-end-of-subtree t)
    (insert "\nAppended content here.\n")
    (save-buffer)
    (buffer-file-name)))
```

### 10.6 Safety considerations

- All emacsclient operations open buffers in the background
  (`find-file-noselect` where possible) to avoid disrupting the
  user's current editing session.
- `save-buffer` is called explicitly to ensure the file watcher
  picks up changes.
- If Emacs server is not running, emacsclient returns exit code 1.
  The MCP server catches this and returns a clear error.

Note: exact elisp forms need validation during implementation.
The vulpea API may require adjustments based on actual function
signatures in the installed version.

## 11. Namespace Layout

```
src/org_roam_mcp/
  core.clj          Main entry point, MCP JSON-RPC handler, stdio loop
  index.clj         HNSW index wrapper (create, load, save, search, add, remove)
  secondary.clj     Secondary indices (backlinks, title-index, tag-index)
  embeddings.clj    Ollama HTTP client (embed single, embed batch)
  parser.clj        Org file parser (extract chunks from .org text)
  watcher.clj       Native file watcher (directory-watcher, debounce, dispatch)
  emacs.clj         Emacsclient wrapper for note CRUD operations
  util.clj          Path expansion, checksums, logging
```

### core.clj responsibilities
- Parse config.edn
- Initialize index (load or build)
- Start file watcher
- Enter stdio loop: read JSON lines, dispatch to tools, write responses
- Graceful shutdown: save index on exit (shutdown hook)

### Startup sequence
1. Load config.edn
2. If index.hnsw exists: load it + load meta.edn, build secondary indices
3. If not: full build (blocking - no existing data to serve)
4. Start file watcher (native OS events via directory-watcher)
5. Enter MCP stdio loop (main thread)
6. In background future: run catch-up scan (compare file mtimes),
   re-embed changed files, rebuild secondary indices

The server is immediately queryable after step 2 (possibly with
stale results until catch-up completes in step 6). This avoids
blocking startup on re-indexing.

## 12. Error Handling

| Scenario | Behavior |
|----------|----------|
| Ollama unreachable on startup | Log warning, start anyway with existing index. Queries work, indexing queued until Ollama available. |
| Ollama unreachable during re-index | Skip file, retry on next watcher cycle |
| Malformed .org file | Log warning with file path, skip file |
| Index file corrupted/missing | `load-index` catches deserialization errors, logs a warning, deletes the corrupt file, and returns nil - triggering a full rebuild from .org files |
| emacsclient not running | Return MCP error: "Emacs server not running" |
| File deleted | Remove all chunks for that file from index |
| Chunk too large (> 8192 tokens) | Progressive truncation: batch fails -> individual embed -> truncate to 60% and retry (up to 4 attempts) |

## 13. Performance Considerations

- Initial indexing (measured): 1,370 files -> 2,385 chunks parsed in ~0.7s,
  embedded in 75 batches in ~15s via SSH tunnel to RTX 3070. Total
  including index build + save: ~22s. 5 batches required individual
  fallback with progressive truncation (dense technical content).
  Final index: 2,376 items (9 empty-content chunks skipped).
- Search latency: < 10ms for 50K vectors with SIMD-accelerated cosine
  distance on JDK17+ (hnswlib-core-jdk17).
- Memory: 50K vectors * 768 dims * 4 bytes = ~150MB for vectors alone,
  plus HNSW graph overhead (~2x), plus metadata. Total ~400-500MB.
  Reasonable for a long-running process.
- Index save: atomic write via temp file + rename. Debounced to avoid
  excessive disk I/O during bulk updates.

## 14. Startup Script

`start.sh`:

```bash
#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"   # clojure needs deps.edn in cwd
exec clojure -J-Xmx512m \
     -J--add-modules -Jjdk.incubator.vector \
     -M -m org-roam-mcp.core \
     "$SCRIPT_DIR/config.edn" 2>"$SCRIPT_DIR/server.log"
```

Key details:
- `cd "$SCRIPT_DIR"` is required because ECA launches from an arbitrary
  working directory, and `clojure` needs `deps.edn` in cwd.
- stderr is redirected to `server.log` to avoid JVM's
  `WARNING: Using incubator modules` confusing the MCP protocol.

ECA config.json entry (absolute paths required):

```json
{
  "org-roam-mcp": {
    "command": "/Users/ryl/.config/eca/tools/org-roam-mcp/start.sh"
  }
}
```

Hot-reload without restarting ECA via elisp:
```elisp
(let ((session (eca-session)))
  (eca-api-notify session :method "mcp/stopServer" :params (list :name "org-roam-mcp"))
  (run-with-timer 2 nil (lambda ()
    (eca-api-notify (eca-session) :method "mcp/startServer" :params (list :name "org-roam-mcp")))))
```

## 15. Design Notes

### 15.1 Reusability beyond org-roam

The architecture is generic: parse corpus -> embed -> HNSW -> secondary
indices -> MCP tools. The org-specific parts are:
- parser.clj (org file format)
- emacs.clj (emacsclient for CRUD)
- watcher.clj (watches ~/Sync/org/)

Everything else (embeddings, index, secondary, MCP protocol, watcher
debounce logic) is corpus-agnostic. To index other corpora (HN comments,
Reddit history, GitHub org source code), swap the parser and tune
chunking. The rest stays.

Potential future instances:
- HN/Reddit comment history: parse from API exports, chunk per
  comment/thread, same embedding + search pipeline
- GitHub org source code: chunk by function/class, use code-specific
  embedding model (e.g., code-search-ada), add file-path/language
  metadata to secondary indices

### 15.2 Emacs integration (outside AI loop)

The MCP server is a long-running process with structured JSON I/O.
Elisp commands can leverage it directly:

- Option A: shell out to a small CLI that sends one JSON-RPC request
  to the running server (via a Unix socket or named pipe - would need
  a secondary listener)
- Option B: use ECA's existing MCP connection from Elisp - the tools
  are already callable
- Option C: connect to the nREPL port directly from Elisp (CIDER
  already knows how)

A `org-roam-mcp-search` command could:
1. Read query from minibuffer
2. Call notes-search
3. Present results in completing-read (or consult source)
4. Jump to the selected note's file + heading

### 15.3 Annotator UI design

The annotator (notes-annotate tool) provides data, but the interaction
model for applying annotations is the hard UX problem.

Spectrum:
- Fully automatic: wrong (ambiguity, false positives)
- Fully manual: tedious (hundreds of matches in long text)
- Sweet spot: auto-apply unambiguous, step through ambiguous

Envisioned UI (query-replace style for entity linking):
1. Apply safe-annotated-text (unambiguous links only) automatically
2. For each ambiguous match, show:
   - The matched text highlighted in context
   - Ranked candidates (by embedding similarity to surrounding context)
   - Single-keystroke actions: pick candidate, skip, edit link text
3. Transient or hydra-style keymap for fast stepping

The three-layer response design (annotations, annotated-text,
safe-annotated-text) supports this split:
- AI agent uses annotated-text directly (best-guess)
- Emacs UI walks annotations where ambiguous=true
- safe-annotated-text is the conservative fallback

## 16. Future Extensions

- Additional embedding models: switch to larger models (e.g.,
  qwen3-embedding-4b via OpenRouter) for longer documents.
- Hybrid search: combine semantic similarity with keyword matching
  (e.g., title/tag exact match boosts ranking).
- Incremental index compaction: periodically rebuild HNSW index
  to reclaim space from deletions.
- Multi-machine index sync: if notes sync across machines, index
  could also be synced or rebuilt per-machine.
