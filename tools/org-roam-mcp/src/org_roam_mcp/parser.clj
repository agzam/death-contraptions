(ns org-roam-mcp.parser
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [org-roam-mcp.util :as util])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Low-level line matchers
;; ---------------------------------------------------------------------------

(def ^:private re-heading #"^(\*+)\s+(.+)$")
(def ^:private re-prop-start #"(?i)^\s*:PROPERTIES:\s*$")
(def ^:private re-prop-end #"(?i)^\s*:END:\s*$")
(def ^:private re-prop-kv #"(?i)^\s*:([^:]+):\s+(.+?)\s*$")
(def ^:private re-keyword #"(?i)^#\+(\w+):\s*(.*)$")
(def ^:private re-id-link #"\[\[id:([^\]]+)\]\[([^\]]*)\]\]")
(def ^:private re-any-link #"\[\[([^\]]+)\]\[([^\]]*)\]\]")

(defn- parse-property-drawer
  "Given lines starting at a :PROPERTIES: line, return [props remaining-lines].
   Props is a map with lower-cased keys."
  [lines]
  (loop [ls (rest lines) ;; skip :PROPERTIES: line
         props {}]
    (if (empty? ls)
      [props ls]
      (let [line (first ls)]
        (if (re-matches re-prop-end line)
          [props (rest ls)]
          (if-let [[_ k v] (re-matches re-prop-kv line)]
            (recur (rest ls) (assoc props (str/lower-case k) v))
            (recur (rest ls) props)))))))

(defn- parse-aliases
  "Parse :ROAM_ALIASES: value - space-separated, quoted multi-word."
  [s]
  (when s
    (let [s (str/trim s)]
      (when-not (str/blank? s)
        (loop [chars (seq s)
               current []
               result []
               in-quote? false]
          (if (empty? chars)
            (let [final (str/join current)]
              (if (str/blank? final)
                result
                (conj result final)))
            (let [c (first chars)]
              (cond
                (= c \")
                (recur (rest chars) current result (not in-quote?))

                (and (= c \space) (not in-quote?))
                (let [token (str/join current)]
                  (recur (rest chars) [] (if (str/blank? token) result (conj result token)) false))

                :else
                (recur (rest chars) (conj current c) result in-quote?)))))))))

(defn- parse-filetags
  "Parse #+filetags: value. Handles :tag1:tag2: and bare 'tag' formats."
  [s]
  (when s
    (let [s (str/trim s)]
      (if (str/starts-with? s ":")
        (filterv (complement str/blank?) (str/split s #":"))
        (when-not (str/blank? s) [s])))))

(defn- extract-heading-tags
  "Extract inline tags from end of heading text: '* Heading :tag1:tag2:' -> [clean-title [tags]]"
  [heading-text]
  (if-let [[_ tags-str] (re-find #"\s+(:(?:\S+?:)+)\s*$" heading-text)]
    (let [clean (str/trimr (subs heading-text 0 (- (count heading-text) (count tags-str))))
          tags (filterv (complement str/blank?) (str/split tags-str #":"))]
      [clean tags])
    [heading-text []]))

(defn- extract-id-links
  "Extract all [[id:...][...]] links from text, return set of target IDs."
  [text]
  (into #{} (map second) (re-seq re-id-link text)))

(defn- strip-links
  "Replace [[...][desc]] with desc, and [[target]] with target."
  [text]
  (-> text
      (str/replace re-any-link "$2")
      (str/replace #"\[\[([^\]]+)\]\]" "$1")))

(defn- keyword-line?
  "True if line is a #+keyword: line."
  [line]
  (boolean (re-matches re-keyword line)))

(defn- block-delimiter?
  "True if line is #+begin_... or #+end_..."
  [line]
  (boolean (re-matches #"(?i)^#\+(begin|end)_.*" line)))

(defn- content-line?
  "True if line should be included in chunk content for embedding."
  [line]
  (not (or (keyword-line? line)
           (block-delimiter? line)
           (re-matches re-prop-start line)
           (re-matches re-prop-end line)
           (re-matches re-prop-kv line))))

;; ---------------------------------------------------------------------------
;; Tag-link extraction (vulpea pattern)
;; ---------------------------------------------------------------------------

(defn- extract-tag-links
  "Extract vulpea-style tag links: lines that are just [[id:...][tag-title]].
   These appear after #+title: and before any real content. Returns:
   {:tags [tag-titles] :tag-link-ids #{node-ids} :remaining [lines]}"
  [lines]
  (loop [ls lines
         tags []
         ids #{}]
    (if (empty? ls)
      {:tags tags :tag-link-ids ids :remaining ls}
      (let [line (str/trim (first ls))]
        (cond
          (str/blank? line)
          (recur (rest ls) tags ids)

          ;; A line that is solely an id-link (possibly with whitespace)
          (and (re-matches #"\[\[id:[^\]]+\]\[[^\]]*\]\]\s*" (str line " "))
               (let [[_ _id desc] (re-find re-id-link line)]
                 desc))
          (let [[_ id desc] (re-find re-id-link line)]
            (recur (rest ls) (conj tags desc) (conj ids id)))

          ;; Not a tag-link line, stop
          :else
          {:tags tags :tag-link-ids ids :remaining ls})))))

;; ---------------------------------------------------------------------------
;; File-level preamble parsing
;; ---------------------------------------------------------------------------

(defn- parse-preamble
  "Parse lines before the first heading. Returns map with file-level metadata."
  [lines]
  (loop [ls lines
         title nil
         filetags nil
         props nil
         body-lines []]
    (if (empty? ls)
      (let [{:keys [tags tag-link-ids remaining]} (extract-tag-links body-lines)]
        {:title title :filetags filetags :props props
         :tag-links tags :tag-link-ids tag-link-ids :body-lines remaining
         :remaining-lines []})
      (let [line (first ls)]
        (cond
          ;; Property drawer
          (re-matches re-prop-start line)
          (let [[p remaining] (parse-property-drawer ls)]
            (recur remaining title filetags p body-lines))

          ;; Heading - end of preamble
          (re-matches re-heading line)
          (let [{:keys [tags tag-link-ids remaining]} (extract-tag-links body-lines)]
            {:title title :filetags filetags :props props
             :tag-links tags :tag-link-ids tag-link-ids :body-lines remaining
             :remaining-lines ls})

          ;; #+keyword
          :else
          (if-let [[_ kw val] (re-matches re-keyword line)]
            (case (str/lower-case kw)
              "title"    (recur (rest ls) (str/trim val) filetags props body-lines)
              "filetags" (recur (rest ls) title (parse-filetags val) props body-lines)
              (recur (rest ls) title filetags props body-lines))
            ;; Regular content line
            (recur (rest ls) title filetags props (conj body-lines line))))))))

;; ---------------------------------------------------------------------------
;; Chunk splitting
;; ---------------------------------------------------------------------------

(defn- heading-sections
  "Split lines into sections at heading boundaries."
  [lines]
  (when (seq lines)
    (loop [ls lines
           sections []
           current nil]
      (if (empty? ls)
        (if current (conj sections current) sections)
        (let [line (first ls)]
          (if-let [[_ stars text] (re-matches re-heading line)]
            (let [level (count stars)
                  new-section {:level level :heading-text text :lines []}]
              (recur (rest ls)
                     (if current (conj sections current) sections)
                     new-section))
            (recur (rest ls)
                   sections
                   (when current (update current :lines conj line)))))))))

(defn- section->chunk
  "Convert a heading section to a chunk map, or nil if no :ID: property."
  [section file-path mtime inherited-tags file-title]
  (let [lines (:lines section)
        ;; Check for property drawer
        [props body-lines]
        (if (and (seq lines) (re-matches re-prop-start (str/trim (first lines))))
          (parse-property-drawer lines)
          [nil lines])
        id (get props "id")]
    (when id
      (let [[clean-title htags] (extract-heading-tags (:heading-text section))
            aliases (parse-aliases (get props "roam_aliases"))
            content-lines (filter content-line? (map str/trimr body-lines))
            raw-content (str/join "\n" content-lines)
            content-text (strip-links raw-content)
            outgoing (extract-id-links (str/join "\n" (cons (:heading-text section) body-lines)))]
        {:node-id id
         :title clean-title
         :file-title file-title
         :file-path file-path
         :level (:level section)
         :tags (into (vec inherited-tags) htags)
         :aliases (or aliases [])
         :outgoing-links outgoing
         :content content-text
         :mtime mtime
         :checksum (util/sha256 content-text)}))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse-file
  "Parse a single .org file into a seq of chunks (one per :ID: node)."
  [^File file]
  (let [path (.getAbsolutePath file)
        mtime (.lastModified file)
        text (slurp file :encoding "UTF-8")
        lines (str/split-lines text)
        ;; Capture ALL id-links in the entire file for the file-level node.
        ;; This ensures links under non-ID headings aren't lost.
        all-file-links (extract-id-links text)
        preamble (parse-preamble lines)
        file-id (get-in preamble [:props "id"])
        file-tags (into (or (:filetags preamble) [])
                        (:tag-links preamble))
        ;; Parse headings first so file-level chunk can include TOC
        file-title* (or (:title preamble) (.getName file))
        sections (heading-sections (:remaining-lines preamble))
        heading-chunks (keep #(section->chunk % path mtime file-tags file-title*) sections)
        ;; File-level chunk - enrich with sub-heading titles as TOC
        file-chunk
        (when file-id
          (let [body-text (strip-links (str/join "\n" (filter content-line? (:body-lines preamble))))
                outgoing (into (or (:tag-link-ids preamble) #{})
                               all-file-links)
                ;; Append sub-heading titles for richer embedding
                sub-titles (keep (fn [s]
                                   (let [[t _] (extract-heading-tags (:heading-text s))]
                                     (when-not (str/blank? t) t)))
                                 sections)
                enriched (if (seq sub-titles)
                           (str body-text "\n" (str/join ", " sub-titles))
                           body-text)]
            {:node-id file-id
             :title file-title*
             :file-path path
             :level 0
             :tags file-tags
             :aliases (or (parse-aliases (get-in preamble [:props "roam_aliases"])) [])
             :outgoing-links outgoing
             :content enriched
             :mtime mtime
             :checksum (util/sha256 enriched)}))]
    (if file-chunk
      (cons file-chunk heading-chunks)
      heading-chunks)))

(defn- excluded?
  "True if relative path matches any exclude prefix in the config."
  [rel-path excludes]
  (some #(str/starts-with? rel-path %) excludes))

(defn scan-directory
  "Scan org-dir for .org files, parse each into chunks.
   Returns a seq of all chunks across all files."
  [org-dir excludes]
  (let [base (io/file (util/expand-home org-dir))
        base-path (.getAbsolutePath base)]
    (->> (file-seq base)
         (filter (fn [^File f]
                   (and (.isFile f)
                        (str/ends-with? (.getName f) ".org")
                        (let [rel (subs (.getAbsolutePath f)
                                        (inc (count base-path)))]
                          (not (excluded? rel excludes))))))
         (mapcat (fn [f]
                   (try
                     (parse-file f)
                     (catch Exception e
                       (util/log "WARN: failed to parse" (.getAbsolutePath f) "-" (.getMessage e))
                       nil)))))))

(def ^:private max-embed-chars
  "~8192 tokens * 3 chars/token = ~24K chars, conservative limit."
  24000)

(def min-embed-chars
  "Minimum embedding text length to produce a useful vector.
   Chunks below this are skipped in HNSW (still in secondary indices)."
  30)

(defn embedding-text
  "Build the text string sent to the embedding model for a chunk.
   For heading-level chunks, prepends file title for context.
   Truncates to max-embed-chars to stay within model context window."
  [{:keys [title file-title tags content level]}]
  (let [;; For heading chunks, add file title as hierarchical context
        display-title (if (and file-title (pos? (or level 0))
                              (not= file-title title))
                        (str file-title " > " title)
                        (or title ""))
        parts (cond-> [display-title]
                (seq tags) (conj (str/join ", " tags))
                true       (conj (or content "")))
        text (str/join "\n" parts)]
    (if (< (count text) max-embed-chars)
      text
      (subs text 0 max-embed-chars))))
