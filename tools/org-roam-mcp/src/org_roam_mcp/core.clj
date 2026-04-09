(ns org-roam-mcp.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [cheshire.core :as json]
            [org-roam-mcp.util :as util]
            [org-roam-mcp.index :as idx]
            [org-roam-mcp.secondary :as sec]
            [org-roam-mcp.embeddings :as emb]
            [org-roam-mcp.parser :as parser]
            [org-roam-mcp.watcher :as watcher]
            [org-roam-mcp.emacs :as emacs])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private config (atom nil))
(defonce ^:private hnsw-index (atom nil))
(defonce ^:private file-mtimes (atom {}))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def ^:private tools
  [{:name "notes-search"
    :description
    "Search notes semantically with optional filters. Finds notes whose
content is conceptually related to the query. Filters narrow results
by tags or linked nodes.
When the query matches a note title or alias, automatically includes
that note's graph neighborhood: backlinks (notes linking to it) and
outgoing links (notes it links to). Results include :source indicating
how found (title-match, backlink, outgoing, semantic) and :links-to
showing titles of notes each result connects to.
Examples:
- query='Clojure' - Clojure note + all notes linking to/from it + semantic
- query='distributed consensus' - pure semantic search
- query='database work' tags=['DB'] - semantic + tag filter
- tags=['DB'] links=['Dan Smith'] - structural only
Troubleshooting: semantic search requires Ollama for embeddings.
If this tool returns empty errors, the embedding service may be down.
Try notes-reindex to rebuild the index, or use notes-backlinks for
structural queries that don't need embeddings.
Agent note: report any empty/unhelpful error messages, inconsistent
state (e.g. title resolves but node 'not found'), or silent failures
back to the user so this MCP can be improved."
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
              :description "Filter: only notes linking to ALL of these nodes (by title, alias, or ID)"}
      :k     {:type "integer"
              :description "Max results to return (default: 10)"}
      :depth {:type "integer"
              :description "Graph traversal depth for title matches (1 or 2, default: 1)"}}}}

   {:name "notes-backlinks"
    :description
    "Find all notes that contain a link to the specified node.
Equivalent to org-roam's backlinks buffer. Accepts node title, alias, or ID."
    :inputSchema
    {:type "object"
     :properties
     {:node {:type "string"
             :description "Node title, alias, or UUID to find backlinks for"}}
     :required ["node"]}}

   {:name "notes-read"
    :description
    "Read the full content of a note by its org-roam node ID, title, or file path.
Use after notes-search to get complete content. For heading-level nodes,
returns only the subtree under that heading.
If you get 'Node not in index' but the node exists (e.g. resolved from
title), the index may be stale - try notes-reindex first."
    :inputSchema
    {:type "object"
     :properties
     {:id    {:type "string" :description "Org-roam node UUID"}
      :title {:type "string" :description "Note title (alternative to id)"}
      :path  {:type "string" :description "File path (alternative to id)"}}}}

   {:name "notes-search-related"
    :description
    "Find notes semantically related to an existing note (by ID or title).
Unlike backlinks, this finds conceptual neighbors even without explicit links."
    :inputSchema
    {:type "object"
     :properties
     {:node {:type "string" :description "Node title, alias, or UUID"}
      :k    {:type "integer" :description "Max results (default: 10)"}}
     :required ["node"]}}

   {:name "notes-reindex"
    :description
    "Trigger re-indexing. Without arguments, performs a full mtime-based scan.
With a path, re-indexes only that file.
Use when: search returns empty errors (Ollama may have been down during
initial indexing), notes-read says 'not in index' for known nodes, or
after bulk file changes outside the watcher's view."
    :inputSchema
    {:type "object"
     :properties
     {:path {:type "string"
             :description "File to re-index (optional, omit for full scan)"}}}}

   {:name "notes-create"
    :description
    "Create a new heading. Supports three modes:
- 'journal' (default): insert under a day entry in monthly journal file.
  Respects vulpea journal structure: work/personal separation, sorted day headings.
- 'heading': insert a new sub-heading under an existing node (by parent-id).
- 'file': create a new standalone .org file with title and content.
Returns the node-id and file path of the created heading."
    :inputSchema
    {:type "object"
     :properties
     {:title   {:type "string"
                :description "Heading title for the new entry"}
      :content {:type "string"
                :description "Body content (org-mode formatted)"}
      :mode    {:type "string"
                :enum ["journal" "heading" "file"]
                :description "Creation mode (default: journal)"}
      :type    {:type "string"
                :enum ["work" "personal"]
                :description "Journal type (journal mode only, default: work)"}
      :date    {:type "string"
                :description "YYYY-MM-DD date (journal mode only, default: today)"}
      :parent-id {:type "string"
                  :description "Node ID to insert under (heading mode only)"}}
     :required ["title" "content"]}}

   {:name "notes-edit"
    :description
    "Edit an existing note's content by node ID or title.
Supports two modes:
- 'append': add content at the end of the node's body
- 'replace': replace the entire body (preserving heading + properties)
Returns confirmation with the node-id and file path."
    :inputSchema
    {:type "object"
     :properties
     {:id      {:type "string"
                :description "Node ID to edit"}
      :title   {:type "string"
                :description "Node title (alternative to id)"}
      :content {:type "string"
                :description "New content (org-mode formatted)"}
      :mode    {:type "string"
                :enum ["append" "replace"]
                :description "Edit mode (default: append)"}}
     :required ["content"]}}])

;; ---------------------------------------------------------------------------
;; Tool implementations
;; ---------------------------------------------------------------------------

(defn- resolve-node-id
  "Resolve a string (ID, title, or alias) to a canonical node ID."
  [s]
  (if-let [item (idx/get-item @hnsw-index s)]
    (.id item)
    (when-let [candidates (seq (sec/resolve-node s))]
      (:node-id (first candidates)))))

(defn- resolve-link-titles
  "Convert outgoing link IDs to human-readable titles for API responses."
  [outgoing-links]
  (into []
        (keep (fn [id]
                (when-let [item (idx/get-item @hnsw-index id)]
                  (.-title ^org_roam_mcp.index.NoteItem item))))
        outgoing-links))

(defn- result-map
  "Shape an item-map into the API response format with resolved link titles."
  [item-map]
  (-> item-map
      (select-keys [:node-id :title :file-path :level :tags :content-preview
                     :score :distance :source])
      (assoc :links-to (resolve-link-titles (:outgoing-links item-map)))
      (update :file-path util/contract-home)))

(defn- graph-collect
  "Collect graph-neighborhood nodes up to `depth` hops with decaying scores."
  ([node-id] (graph-collect node-id 1))
  ([node-id depth]
   (let [results (atom {})
         add-node! (fn [nid score source]
                     (when (and (not (contains? @results nid))
                                (idx/get-item @hnsw-index nid))
                       (swap! results assoc nid
                              (assoc (idx/item->map (idx/get-item @hnsw-index nid))
                                     :score score :distance (- 1.0 score) :source source))
                       true))]
     ;; The matched node itself
     (when-let [item (idx/get-item @hnsw-index node-id)]
       (let [m (idx/item->map item)]
         (swap! results assoc node-id (assoc m :score 1.0 :distance 0.0 :source :title-match))
         ;; Hop 1: direct backlinks and outgoing
         (let [hop1-ids (atom #{})]
           (doseq [src-id (sec/find-backlinks node-id)]
             (when (add-node! src-id 0.95 :backlink)
               (swap! hop1-ids conj src-id)))
           (doseq [target-id (:outgoing-links m)]
             (when (add-node! target-id 0.9 :outgoing)
               (swap! hop1-ids conj target-id)))
           ;; Hop 2: backlinks and outgoing of hop-1 nodes
           (when (< 1 depth)
             (doseq [h1-id @hop1-ids]
               (when-let [h1-item (idx/get-item @hnsw-index h1-id)]
                 (let [h1-m (idx/item->map h1-item)]
                   (doseq [src-id (sec/find-backlinks h1-id)]
                     (add-node! src-id 0.85 :hop2-backlink))
                   (doseq [target-id (:outgoing-links h1-m)]
                     (add-node! target-id 0.85 :hop2-outgoing)))))))))
     @results)))

(defn do-notes-search
  "Semantic + graph-enriched search, with optional tag/link filters."
  [{:strs [query tags links k depth]}]
  (let [k (or k 10)
        graph-depth (min (or depth 1) 2)
        over-fetch (* k 10)]
    (cond
      ;; Semantic + graph-enriched search
      query
      (let [;; 1) Semantic search
            qvec (emb/embed-query @config query)
            semantic (idx/search @hnsw-index qvec over-fetch)
            ;; Apply tag/link filters to semantic results
            filtered
            (cond->> semantic
              (seq tags)
              (filter (fn [r]
                        (let [rtags (set (:tags r))]
                          (every? #(contains? rtags %) tags))))
              (seq links)
              (filter (fn [r]
                        (let [rlinks (:outgoing-links r)]
                          (every? (fn [link-ref]
                                    (when-let [target-id (resolve-node-id link-ref)]
                                      (contains? rlinks target-id)))
                                  links)))))
            semantic-tagged (mapv #(assoc % :source :semantic) filtered)

            ;; 2) Graph neighborhood for title/alias matches
            title-candidates (sec/resolve-node query)
            graph-nodes (reduce (fn [acc {:keys [node-id]}]
                                  (merge acc (graph-collect node-id graph-depth)))
                                {} title-candidates)

            ;; 3) Merge: graph nodes first (sorted by score), then semantic (deduped)
            graph-results (sort-by (comp - :score) (vals graph-nodes))
            graph-ids (set (keys graph-nodes))
            deduped-semantic (remove #(contains? graph-ids (:node-id %)) semantic-tagged)
            combined (into (vec graph-results) deduped-semantic)]
        {:content [{:type "text"
                    :text (json/generate-string
                           {:results (mapv result-map (take k combined))})}]})

      ;; Structural-only query
      (or (seq tags) (seq links))
      (let [tag-ids (when (seq tags)
                      (reduce (fn [acc tag]
                                (let [ids (sec/find-by-tag tag)]
                                  (if acc
                                    (set/intersection acc ids)
                                    ids)))
                              nil tags))
            link-ids (when (seq links)
                       (reduce (fn [acc link-ref]
                                 (when-let [target-id (resolve-node-id link-ref)]
                                   (let [sources (sec/find-backlinks target-id)]
                                     (if acc
                                       (set/intersection acc sources)
                                       sources))))
                               nil links))
            result-ids (cond
                         (and tag-ids link-ids) (set/intersection tag-ids link-ids)
                         tag-ids tag-ids
                         :else link-ids)
            results (keep (fn [id]
                            (when-let [item (idx/get-item @hnsw-index id)]
                              (result-map (idx/item->map item))))
                          (take k result-ids))]
        {:content [{:type "text"
                    :text (json/generate-string {:results (vec results)})}]})

      :else
      {:content [{:type "text" :text "Provide a query, tags, or links filter"}]
       :isError true})))

(defn do-notes-backlinks
  "Find all notes containing a link to the given node."
  [{:strs [node]}]
  (if-let [node-id (resolve-node-id node)]
    (let [source-ids (sec/find-backlinks node-id)
          results (keep (fn [id]
                          (when-let [item (idx/get-item @hnsw-index id)]
                            (result-map (idx/item->map item))))
                        source-ids)]
      {:content [{:type "text"
                  :text (json/generate-string {:results (vec results)})}]})
    {:content [{:type "text" :text (str "Node not found: " node)}]
     :isError true}))

(defn do-notes-read
  "Read full content of a note, extracting subtree for heading-level nodes."
  [{:strs [id title path]}]
  (let [node-id (or id
                    (when title (resolve-node-id title))
                    (when path
                      ;; Find by file path
                      (let [abs (util/expand-home path)]
                        (some (fn [item]
                                (when (= (.-file-path ^org_roam_mcp.index.NoteItem item) abs)
                                  (.id ^org_roam_mcp.index.NoteItem item)))
                              (idx/all-items @hnsw-index)))))]
    (if-not node-id
      {:content [{:type "text" :text "Note not found"}] :isError true}
      (let [item (idx/get-item @hnsw-index node-id)]
        (if-not item
          {:content [{:type "text" :text (str "Node not in index: " node-id)}] :isError true}
          (let [m (idx/item->map item)
                f (io/file (:file-path m))]
            (if-not (.exists f)
              {:content [{:type "text" :text (str "File not found: " (:file-path m))}] :isError true}
              (let [full-text (slurp f :encoding "UTF-8")
                    content (if (zero? (:level m))
                              full-text
                              ;; Heading-level: extract subtree
                              (let [lines (str/split-lines full-text)
                                    stars (apply str (repeat (:level m) \*))
                                    prefix (str stars " ")
                                    ;; Find the heading line
                                    start-idx (first (keep-indexed
                                                      (fn [i line]
                                                        (when (and (str/starts-with? line prefix)
                                                                   (str/includes? line (:title m)))
                                                          i))
                                                      lines))]
                                (if-not start-idx
                                  full-text
                                  (let [rest-lines (subvec (vec lines) start-idx)
                                        end-idx (or (first (keep-indexed
                                                            (fn [i line]
                                                              (when (and (pos? i)
                                                                         (re-matches #"^\*{1,}\s+.*" line)
                                                                         ;; Same or higher level
                                                                         (let [n (count (re-find #"^\*+" line))]
                                                                           (< n (inc (:level m)))))
                                                                i))
                                                            rest-lines))
                                                    (count rest-lines))]
                                    (str/join "\n" (subvec rest-lines 0 end-idx))))))]
                {:content [{:type "text"
                            :text (json/generate-string
                                   {:node-id node-id
                                    :title (:title m)
                                    :file (util/contract-home (:file-path m))
                                    :level (:level m)
                                    :content content})}]}))))))))

(defn do-notes-search-related
  "Find semantically similar notes by reusing the source node's embedding."
  [{:strs [node k]}]
  (let [k (or k 10)]
    (if-let [node-id (resolve-node-id node)]
      (if-let [item (idx/get-item @hnsw-index node-id)]
        (let [vec (.vector ^org_roam_mcp.index.NoteItem item)
              results (idx/search @hnsw-index vec (inc k))
              ;; Exclude the source node itself
              filtered (filterv #(not= (:node-id %) node-id) results)]
          {:content [{:type "text"
                      :text (json/generate-string
                             {:results (mapv result-map (take k filtered))})}]})
        {:content [{:type "text" :text (str "Node not in index: " node-id)}] :isError true})
      {:content [{:type "text" :text (str "Node not found: " node)}] :isError true})))

(defn do-notes-reindex
  "Re-index a single file or trigger full mtime-based catch-up scan."
  [{:strs [path]}]
  (try
    (if path
      ;; Single file re-index
      (let [f (io/file (util/expand-home path))]
        (if (.exists f)
          (let [chunks (parser/parse-file f)
                abs (.getAbsolutePath f)
                ;; Collect IDs first to avoid ConcurrentModification
                ids-to-remove (into [] (keep (fn [item]
                                               (when (= (.-file-path ^org_roam_mcp.index.NoteItem item) abs)
                                                 (.id ^org_roam_mcp.index.NoteItem item))))
                                      (idx/all-items @hnsw-index))]
            (doseq [id ids-to-remove]
              (sec/remove-for-id! id)
              (idx/remove-item! @hnsw-index id))
            ;; Add new chunks
            (let [texts (mapv parser/embedding-text chunks)
                  vectors (emb/embed-all @config texts (or (:embed-batch-size @config) 32))]
              (doseq [[chunk vec] (map vector chunks vectors)]
                (when vec
                  (let [item (idx/make-item chunk vec)]
                    (idx/add-item! @hnsw-index item)
                    (sec/update-for-item! item)))))
            {:content [{:type "text"
                        :text (json/generate-string
                               {:reindexed (count chunks) :file path})}]})
          {:content [{:type "text" :text (str "File not found: " path)}] :isError true}))
      ;; Full catch-up scan
      (do
        (idx/catchup! @config @hnsw-index nil)
        (sec/build! @hnsw-index)
        {:content [{:type "text" :text "Full re-index complete"}]}))
    (catch Exception e
      {:content [{:type "text" :text (str "Reindex failed: " (.getMessage e))}]
       :isError true})))

;; ---------------------------------------------------------------------------
;; Write tool implementations
;; ---------------------------------------------------------------------------

(defn- adjust-heading-levels
  "Shift org headings in content so they nest under a heading at `parent-level`.
   Finds the shallowest heading in content and shifts all headings so that
   shallowest becomes (inc parent-level)."
  [content parent-level]
  (when content
    (let [lines (str/split-lines content)
          min-level (reduce (fn [acc line]
                              (if-let [[_ stars] (re-matches #"^(\*+)\s.*" line)]
                                (min acc (count stars))
                                acc))
                            Integer/MAX_VALUE lines)]
      (if (= min-level Integer/MAX_VALUE)
        content
        (let [shift (- (inc parent-level) min-level)]
          (if (zero? shift)
            content
            (str/join "\n"
                      (mapv (fn [line]
                              (if-let [[_ stars rest] (re-matches #"^(\*+)(\s.*)" line)]
                                (str (apply str (repeat (max 1 (+ (count stars) shift)) \*)) rest)
                                line))
                            lines))))))))

(defn- write-temp-content!
  "Write content to a temp file, return the path.
   Avoids elisp string escaping issues for multi-line content."
  [content]
  (let [f (java.io.File/createTempFile "org-roam-mcp-" ".txt")]
    (.deleteOnExit f)
    (spit f (or content ""))
    (.getAbsolutePath f)))

(defn- escape-elisp-string
  "Escape a string for embedding in elisp double-quoted string.
   Only for short strings (titles, IDs). Use write-temp-content! for body content."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- create-journal-entry
  "Create a new heading under a journal day entry via vulpea."
  [title content journal-type date]
  (let [date-parts (when date
                     (let [[_ y m d] (re-matches #"(\d{4})-(\d{2})-(\d{2})" date)]
                       (when y [(Integer/parseInt y) (Integer/parseInt m) (Integer/parseInt d)])))
        date-elisp (if date-parts
                     (let [[y m d] date-parts]
                       (format "(encode-time 0 0 0 %d %d %d)" d m y))
                     "(current-time)")
        adjusted (adjust-heading-levels content 2)
        content-file (write-temp-content! adjusted)
        elisp (format
               "(progn
                  (require 'vulpea)
                  (let* ((vulpea-journal--type '%s)
                         (date %s)
                         (note (vulpea-journal-note date))
                         (file-path (vulpea-note-path note))
                         (day-id (vulpea-note-id note))
                         (body (with-temp-buffer
                                 (insert-file-contents \"%s\")
                                 (buffer-string))))
                    (with-current-buffer (find-file-noselect file-path)
                      (goto-char (org-find-entry-with-id day-id))
                      (org-end-of-subtree t)
                      (let ((new-id (org-id-new)))
                        (insert \"\\n** %s\\n\"
                                \":PROPERTIES:\\n\"
                                \":ID:       \" new-id \"\\n\"
                                \":END:\\n\\n\"
                                body \"\\n\")
                        (save-buffer)
                        (format \"%%s|%%s\" new-id file-path)))))"
               journal-type date-elisp content-file
               (escape-elisp-string (or title "Note")))]
    (emacs/eval-sexp elisp)))

(defn- create-heading-under
  "Create a new sub-heading under an existing node."
  [title content parent-id]
  (let [parent-item (idx/get-item @hnsw-index parent-id)
        parent-level (if parent-item (.-level ^org_roam_mcp.index.NoteItem parent-item) 1)
        adjusted (adjust-heading-levels content (inc parent-level))
        content-file (write-temp-content! adjusted)
        elisp (format
               "(progn
                  (require 'org-id)
                  (let* ((loc (org-id-find \"%s\" t))
                         (body (with-temp-buffer
                                 (insert-file-contents \"%s\")
                                 (buffer-string))))
                    (if (not loc) \"ERROR:parent-not-found\"
                      (switch-to-buffer (marker-buffer loc))
                      (goto-char loc)
                      (let ((parent-level (org-current-level))
                            (new-id (org-id-new)))
                        (org-end-of-subtree t)
                        (insert \"\\n\" (make-string (1+ parent-level) ?*) \" %s\\n\"
                                \":PROPERTIES:\\n\"
                                \":ID:       \" new-id \"\\n\"
                                \":END:\\n\\n\"
                                body \"\\n\")
                        (save-buffer)
                        (format \"%%s|%%s\" new-id (buffer-file-name))))))"
               (escape-elisp-string parent-id) content-file
               (escape-elisp-string (or title "Note")))]
    (emacs/eval-sexp elisp)))

(defn- create-standalone-file
  "Create a new standalone .org file in the org directory."
  [title content config]
  (let [content-file (write-temp-content! content)
        org-dir (util/expand-home (:org-dir config))
        ;; Slugify title for filename
        slug (-> (or title "note")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "_")
                 (str/replace #"^_|_$" ""))
        file-path (str org-dir "/" slug ".org")
        elisp (format
               "(progn
                  (require 'org-id)
                  (let* ((body (with-temp-buffer
                                 (insert-file-contents \"%s\")
                                 (buffer-string)))
                         (new-id (org-id-new)))
                    (with-current-buffer (find-file-noselect \"%s\")
                      (erase-buffer)
                      (insert \":PROPERTIES:\\n\"
                              \":ID:       \" new-id \"\\n\"
                              \":END:\\n\"
                              \"#+title: %s\\n\\n\"
                              body \"\\n\")
                      (save-buffer)
                      (format \"%%s|%%s\" new-id (buffer-file-name)))))"
               content-file file-path
               (escape-elisp-string (or title "Note")))]
    (emacs/eval-sexp elisp)))

(defn do-notes-create
  "Create a note via emacsclient in journal, heading, or file mode."
  [{:strs [title content mode type date parent-id]}]
  (if-not (emacs/available?)
    {:content [{:type "text" :text "Emacs server not running"}] :isError true}
    (try
      (let [create-mode (or mode "journal")
            result (case create-mode
                     "journal" (create-journal-entry title content (or type "work") date)
                     "heading" (if parent-id
                                 (create-heading-under title content parent-id)
                                 (throw (ex-info "heading mode requires parent-id" {})))
                     "file"    (create-standalone-file title content @config)
                     (throw (ex-info (str "Unknown mode: " create-mode) {})))
            cleaned (str/replace result "\"" "")]
        (if (str/starts-with? cleaned "ERROR:")
          {:content [{:type "text" :text cleaned}] :isError true}
          (let [[new-id file-path] (str/split cleaned #"\|" 2)]
            {:content [{:type "text"
                        :text (json/generate-string
                               {:node-id new-id
                                :file (util/contract-home file-path)
                                :title title
                                :mode create-mode})}]})))
      (catch Exception e
        {:content [{:type "text" :text (str "Create failed: " (.getMessage e))}]
         :isError true}))))

(defn do-notes-edit
  "Edit an existing note's body via emacsclient (append or replace)."
  [{:strs [id title content mode]}]
  (if-not (emacs/available?)
    {:content [{:type "text" :text "Emacs server not running"}] :isError true}
    (try
      (let [node-id (or id (when title (resolve-node-id title)))
            edit-mode (or mode "append")]
        (if-not node-id
          {:content [{:type "text" :text "Node not found"}] :isError true}
          (let [item (idx/get-item @hnsw-index node-id)
                node-level (if item (.-level ^org_roam_mcp.index.NoteItem item) 1)
                adjusted (adjust-heading-levels content node-level)
                content-file (write-temp-content! adjusted)
                elisp (format
                       "(progn
                          (require 'org-id)
                          (let* ((loc (org-id-find \"%s\" t))
                                 (body (with-temp-buffer
                                         (insert-file-contents \"%s\")
                                         (buffer-string))))
                            (if (not loc)
                                \"ERROR:node-not-found\"
                              (switch-to-buffer (marker-buffer loc))
                              (goto-char loc)
                              (let ((heading-end (save-excursion (org-end-of-meta-data t) (point)))
                                    (subtree-end (save-excursion (org-end-of-subtree t) (point))))
                                %s
                                (save-buffer)
                                (format \"%%s|%%s\" \"%s\" (buffer-file-name))))))"
                       (escape-elisp-string node-id)
                       content-file
                       (if (= edit-mode "replace")
                         "(progn
                            (delete-region heading-end subtree-end)
                            (goto-char heading-end)
                            (insert \"\\n\" body \"\\n\"))"
                         "(progn
                            (goto-char subtree-end)
                            (insert \"\\n\" body \"\\n\"))")
                       (escape-elisp-string node-id))
                result (emacs/eval-sexp elisp)
                cleaned (str/replace result "\"" "")]
            (if (str/starts-with? cleaned "ERROR:")
              {:content [{:type "text" :text cleaned}] :isError true}
              (let [[eid file-path] (str/split cleaned #"\|" 2)]
                {:content [{:type "text"
                            :text (json/generate-string
                                   {:node-id eid
                                    :file (util/contract-home file-path)
                                    :mode edit-mode})}]})))))
      (catch Exception e
        {:content [{:type "text" :text (str "Edit failed: " (.getMessage e))}]
         :isError true}))))

;; ---------------------------------------------------------------------------
;; MCP JSON-RPC handler
;; ---------------------------------------------------------------------------

(def ^:private server-info {:name "org-roam-mcp" :version "0.1.0"})

(defn- handle-request
  "Dispatch a JSON-RPC request to the appropriate MCP method handler."
  [{:strs [id method params]}]
  (case method
    "initialize"
    {:jsonrpc "2.0" :id id
     :result {:protocolVersion "2024-11-05"
              :capabilities {:tools {}}
              :serverInfo server-info}}

    "notifications/initialized" nil

    "tools/list"
    {:jsonrpc "2.0" :id id
     :result {:tools tools}}

    "tools/call"
    (let [{tool "name" args "arguments"} params]
      {:jsonrpc "2.0" :id id
       :result (try
                 (case tool
                   "notes-search"         (do-notes-search args)
                   "notes-backlinks"      (do-notes-backlinks args)
                   "notes-read"           (do-notes-read args)
                   "notes-search-related" (do-notes-search-related args)
                   "notes-reindex"        (do-notes-reindex args)
                   "notes-create"         (do-notes-create args)
                   "notes-edit"           (do-notes-edit args)
                   {:content [{:type "text" :text (str "Unknown tool: " tool)}]
                    :isError true})
                 (catch Exception e
                   (util/log "ERROR in tool" tool ":" (.getMessage e))
                   {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
                    :isError true}))})

    ;; Unknown method - ignore
    nil))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main
  "Entry point: load config, init index, start watcher, run MCP stdio loop."
  [& args]
  (let [config-path (or (first args) "config.edn")
        cfg (-> (slurp config-path) edn/read-string)]
    (reset! config cfg)
    (util/log "org-roam-mcp starting, config:" config-path)

    ;; Ensure Ollama connectivity (start SSH tunnel if configured)
    (util/ensure-ssh-tunnel! cfg)

    ;; Initialize index: load from disk immediately, catch-up async
    (util/log "Initializing index...")
    (if-let [[index meta] (idx/load-index (:index-dir cfg))]
      (do
        (reset! hnsw-index index)
        (reset! file-mtimes (or (:file-mtimes meta) {}))
        ;; Parse non-embedded chunks for secondary indices
        (let [all-chunks (parser/scan-directory (:org-dir cfg) (or (:exclude cfg) []))
              non-embedded (filterv #(< (count (parser/embedding-text %)) parser/min-embed-chars) all-chunks)]
          (sec/build! index non-embedded))
        (util/log "Index loaded, starting async catch-up")
        ;; Catch-up in background - server is queryable immediately
        (future
          (try
            (let [{:keys [index mtimes]} (idx/catchup! cfg @hnsw-index meta)]
              (reset! hnsw-index index)
              (reset! file-mtimes mtimes)
              ;; Rebuild secondary with fresh parse for non-embedded
              (let [all-chunks (parser/scan-directory (:org-dir cfg) (or (:exclude cfg) []))
                    non-embedded (filterv #(< (count (parser/embedding-text %)) parser/min-embed-chars) all-chunks)]
                (sec/build! index non-embedded))
              (util/log "Async catch-up complete"))
            (catch Exception e
              (util/log "WARN: async catch-up failed:" (.getMessage e))))))
      ;; No existing index - must do full build (blocking, no choice)
      (try
        (let [{:keys [index mtimes non-embedded]} (idx/build-full! cfg)]
          (reset! hnsw-index index)
          (reset! file-mtimes mtimes)
          (sec/build! index non-embedded))
        (catch Exception e
          (util/log "ERROR: full build failed:" (.getMessage e) "- starting empty")
          (reset! hnsw-index (idx/create-index (:hnsw cfg))))))

    ;; Start file watcher
    (let [stop-watcher (try
                         (watcher/start! cfg hnsw-index file-mtimes)
                         (catch Exception e
                           (util/log "WARN: file watcher failed to start:" (.getMessage e))
                           nil))]

      ;; Shutdown hook: stop watcher, save index
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (util/log "Shutting down")
                                   (util/stop-ssh-tunnel!)
                                   (when stop-watcher (stop-watcher))
                                   (try
                                     (idx/save-index! @hnsw-index (:index-dir @config) @file-mtimes
                                                      (.size ^com.github.jelmerk.hnswlib.core.hnsw.HnswIndex @hnsw-index)
                                                      (:model @config) (get-in @config [:hnsw :dimensions]))
                                     (catch Exception e
                                       (util/log "ERROR saving index on shutdown:" (.getMessage e))))))))

    (util/log "MCP server ready, entering stdio loop")
    ;; MCP stdio loop
    (let [rdr (java.io.BufferedReader. *in*)]
      (doseq [line (line-seq rdr)]
        (when-not (str/blank? line)
          (try
            (let [req (json/parse-string line)]
              (when-let [resp (handle-request req)]
                (println (json/generate-string resp))
                (flush)))
            (catch Exception e
              (util/log "ERROR parsing request:" (.getMessage e)))))))))
