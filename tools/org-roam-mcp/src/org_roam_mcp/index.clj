(ns org-roam-mcp.index
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org-roam-mcp.util :as util]
            [org-roam-mcp.parser :as parser]
            [org-roam-mcp.embeddings :as embeddings])
  (:import [com.github.jelmerk.hnswlib.core
            DistanceFunctions SearchResult Item]
           [com.github.jelmerk.hnswlib.core.hnsw HnswIndex]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; HNSW Item type
;; ---------------------------------------------------------------------------

(deftype NoteItem [^String id
                   ^floats vector
                   ^int dimensions
                   ;; metadata fields stored as strings for serialization
                   ^String title
                   ^String file-path
                   ^int level
                   ^String tags-str        ;; tab-separated
                   ^String aliases-str     ;; tab-separated
                   ^String links-str       ;; tab-separated node IDs
                   ^String content-preview ;; truncated body
                   ^long mtime
                   ^String checksum]
  Item
  (id [_] id)
  (vector [_] vector)
  (dimensions [_] dimensions)

  java.io.Serializable)

(defn- truncate
  "Truncate string to n chars for index storage limits."
  [^String s n]
  (if (< (count s) n) s (subs s 0 n)))

(defn make-item
  "Create a NoteItem from a chunk map and its embedding vector."
  [{:keys [node-id title file-path level tags aliases outgoing-links content mtime checksum]}
   ^floats vec]
  (->NoteItem node-id
              vec
              (alength vec)
              title
              file-path
              (int level)
              (str/join "\t" tags)
              (str/join "\t" aliases)
              (str/join "\t" outgoing-links)
              (truncate content 500)
              (long mtime)
              checksum))

(defn item->map
  "Extract metadata from a NoteItem as a Clojure map."
  [^NoteItem item]
  {:node-id  (.id item)
   :title    (.-title item)
   :file-path (.-file-path item)
   :level    (.-level item)
   :tags     (filterv (complement str/blank?) (str/split (.-tags-str item) #"\t"))
   :aliases  (filterv (complement str/blank?) (str/split (.-aliases-str item) #"\t"))
   :outgoing-links (into #{} (filter (complement str/blank?))
                         (str/split (.-links-str item) #"\t"))
   :content-preview (.-content-preview item)
   :mtime   (.-mtime item)
   :checksum (.-checksum item)})

;; ---------------------------------------------------------------------------
;; Index CRUD
;; ---------------------------------------------------------------------------

(defn create-index
  "Create a new empty HNSW index."
  [hnsw-config]
  (-> (HnswIndex/newBuilder (:dimensions hnsw-config)
                            DistanceFunctions/FLOAT_COSINE_DISTANCE
                            (:max-items hnsw-config))
      (.withM (int (:m hnsw-config)))
      (.withEf (int (:ef hnsw-config)))
      (.withEfConstruction (int (:ef-construction hnsw-config)))
      (.withRemoveEnabled)
      (.build)))

(defn add-item!
  "Add or replace an item in the index."
  [^HnswIndex index ^NoteItem item]
  (.add index item))

(defn remove-item!
  "Remove an item from the index by ID."
  [^HnswIndex index ^String id]
  (.remove index id (int 0)))

(defn search
  "Find k nearest neighbors for the given query vector.
   Returns seq of {:node-id :title ... :score :distance}."
  [^HnswIndex index ^floats query-vec k]
  (let [results (.findNearest index query-vec (int k))]
    (mapv (fn [^SearchResult sr]
            (let [item (.item sr)]
              (assoc (item->map item)
                     :distance (.distance sr)
                     :score (- 1.0 (.distance sr)))))
          results)))

(defn get-item
  "Get a single item by ID, or nil."
  [^HnswIndex index ^String id]
  (when-let [opt (.get index id)]
    (when (.isPresent opt)
      (.get opt))))

(defn all-items
  "Return all items in the index as a seq of NoteItems."
  [^HnswIndex index]
  (iterator-seq (.iterator (.items index))))

;; ---------------------------------------------------------------------------
;; Persistence
;; ---------------------------------------------------------------------------

(defn- meta-path
  "Resolve the meta.edn sidecar path for the given index directory."
  [index-dir]
  (io/file (util/expand-home index-dir) "meta.edn"))

(defn- index-path
  "Resolve the index.hnsw file path for the given index directory."
  [index-dir]
  (io/file (util/expand-home index-dir) "index.hnsw"))

(defn save-index!
  "Save index and metadata to disk atomically."
  [^HnswIndex index index-dir file-mtimes chunk-count model dimensions]
  (let [dir (io/file (util/expand-home index-dir))
        idx-file (io/file dir "index.hnsw")
        tmp-file (io/file dir "index.hnsw.tmp")
        meta-file (io/file dir "meta.edn")]
    (.mkdirs dir)
    (.save index tmp-file)
    (.renameTo tmp-file idx-file)
    (spit meta-file
          (pr-str {:version 1
                   :created-at (str (java.time.Instant/now))
                   :last-indexed (str (java.time.Instant/now))
                   :model model
                   :dimensions dimensions
                   :chunk-count chunk-count
                   :file-mtimes file-mtimes}))
    (util/log "Index saved:" chunk-count "chunks")))

(defn load-index
  "Load index from disk. Returns [index meta] or nil if missing/corrupt.
   Corrupt files are removed so a fresh build can proceed."
  [index-dir]
  (let [idx-file (index-path index-dir)
        m-file (meta-path index-dir)]
    (when (.exists idx-file)
      (util/log "Loading index from" (.getAbsolutePath idx-file))
      (try
        (let [index (HnswIndex/load idx-file (.getClassLoader NoteItem))
              meta (when (.exists m-file)
                     (edn/read-string (slurp m-file)))]
          (util/log "Loaded" (.size index) "items")
          [index meta])
        (catch Exception e
          (util/log "WARN: index file corrupt or unreadable:" (.getMessage e))
          (util/log "Removing corrupt index, will rebuild from scratch")
          (try (.delete idx-file) (catch Exception _))
          nil)))))

;; ---------------------------------------------------------------------------
;; Full build / catch-up
;; ---------------------------------------------------------------------------

(defn file-mtime-map
  "Build {relative-path -> mtime} map for all non-excluded .org files."
  [org-dir excludes]
  (let [base (io/file (util/expand-home org-dir))
        base-path (.getAbsolutePath base)]
    (->> (file-seq base)
         (filter (fn [^File f]
                   (and (.isFile f)
                        (str/ends-with? (.getName f) ".org")
                        (let [rel (subs (.getAbsolutePath f) (inc (count base-path)))]
                          (not (some #(str/starts-with? rel %) excludes))))))
         (reduce (fn [m ^File f]
                   (let [rel (subs (.getAbsolutePath f) (inc (count base-path)))]
                     (assoc m rel (.lastModified f))))
                 {}))))

(defn build-full!
  "Full index build: parse all .org files, embed, add to index."
  [config]
  (util/log "Starting full index build")
  (let [{:keys [org-dir exclude hnsw embed-batch-size]} config
        all-chunks (vec (parser/scan-directory org-dir (or exclude [])))
        ;; Only embed chunks with enough content for meaningful vectors
        embeddable (filterv #(>= (count (parser/embedding-text %)) parser/min-embed-chars) all-chunks)
        non-embeddable (filterv #(< (count (parser/embedding-text %)) parser/min-embed-chars) all-chunks)
        _ (util/log "Parsed" (count all-chunks) "chunks," (count embeddable) "embeddable," (count non-embeddable) "short (structural only)")
        texts (mapv parser/embedding-text embeddable)
        vectors (embeddings/embed-all config texts (or embed-batch-size 32))
        index (create-index hnsw)
        mtimes (file-mtime-map org-dir (or exclude []))]
    (doseq [[chunk vec] (map vector embeddable vectors)]
      (when vec
        (add-item! index (make-item chunk vec))))
    (save-index! index (:index-dir config) mtimes
                 (.size index) (:model config) (:dimensions hnsw))
    (util/log "Full build complete:" (.size index) "items")
    {:index index :mtimes mtimes :non-embedded non-embeddable}))

(defn catchup!
  "Re-index files whose mtime changed since last index save."
  [config ^HnswIndex index old-meta]
  (let [{:keys [org-dir exclude embed-batch-size]} config
        old-mtimes (or (:file-mtimes old-meta) {})
        current-mtimes (file-mtime-map org-dir (or exclude []))
        base (io/file (util/expand-home org-dir))
        changed (into []
                      (filter (fn [[rel mtime]]
                                (not= mtime (get old-mtimes rel))))
                      current-mtimes)
        deleted (into []
                      (filter (fn [[rel _]]
                                (not (contains? current-mtimes rel))))
                      old-mtimes)]
    (when (or (seq changed) (seq deleted))
      (util/log "Catch-up:" (count changed) "changed," (count deleted) "deleted files")
      ;; Remove chunks from deleted files - collect IDs first to avoid ConcurrentModification
      (doseq [[rel _] deleted]
        (let [abs (str (.getAbsolutePath base) "/" rel)
              ids-to-remove (into [] (keep (fn [item]
                                             (when (= (.-file-path ^NoteItem item) abs)
                                               (.id ^Item item))))
                                  (all-items index))]
          (doseq [id ids-to-remove]
            (remove-item! index id))))
      ;; Re-index changed files - collect IDs first, then remove, then parse
      (let [new-chunks (vec (mapcat (fn [[rel _]]
                                      (let [f (io/file base rel)]
                                        (when (.exists f)
                                          (try
                                            (let [abs (.getAbsolutePath f)
                                                  ids-to-remove (into [] (keep (fn [item]
                                                                                (when (= (.-file-path ^NoteItem item) abs)
                                                                                  (.id ^Item item))))
                                                                       (all-items index))]
                                              (doseq [id ids-to-remove]
                                                (remove-item! index id)))
                                            (parser/parse-file f)
                                            (catch Exception e
                                              (util/log "WARN: catch-up parse failed:" rel (.getMessage e))
                                              nil)))))
                                    changed))
            embeddable (filterv #(>= (count (parser/embedding-text %)) parser/min-embed-chars) new-chunks)]
        (when (seq embeddable)
          (let [texts (mapv parser/embedding-text embeddable)
                vectors (embeddings/embed-all config texts (or embed-batch-size 32))]
            (doseq [[chunk vec] (map vector embeddable vectors)]
              (when vec
                (add-item! index (make-item chunk vec)))))))
      (save-index! index (:index-dir config) current-mtimes
                   (.size index) (:model config) (get-in config [:hnsw :dimensions])))
    {:index index :mtimes current-mtimes}))

(defn init!
  "Initialize the index: load + catch-up if compatible, else full rebuild."
  [config]
  (if-let [[index meta] (load-index (:index-dir config))]
    ;; Check model compatibility
    (if (and (= (:model meta) (:model config))
             (= (:dimensions meta) (get-in config [:hnsw :dimensions])))
      (do
        (util/log "Index loaded, running catch-up scan")
        (catchup! config index meta))
      (do
        (util/log "Model/dimensions changed, rebuilding index")
        (build-full! config)))
    (build-full! config)))
