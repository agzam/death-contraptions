(ns org-roam-mcp.watcher
  "File watcher using io.methvin/directory-watcher for native OS events
   (macOS FSEvents, Linux inotify). Debounced per-file processing,
   segment-aware exclude filtering, index save debouncing (30s)."
  (:require [clojure.string :as str]
            [org-roam-mcp.util :as util]
            [org-roam-mcp.parser :as parser]
            [org-roam-mcp.embeddings :as embeddings]
            [org-roam-mcp.index :as idx]
            [org-roam-mcp.secondary :as sec])
  (:import [io.methvin.watcher DirectoryWatcher]
           [java.nio.file Path Paths]))

;; ---------------------------------------------------------------------------
;; Debounce
;; ---------------------------------------------------------------------------

(defn- debounced-processor
  "Debounce file events per path. Returns {:process schedule-fn :stop drain-fn}.
   The :stop fn shuts the executor down and waits for any in-flight task so
   the shutdown hook can save-index! without racing a pending re-embed."
  [debounce-ms process-fn]
  (let [pending (atom {})
        executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    {:process
     (fn [^String abs-path]
       (when-let [fut (get @pending abs-path)]
         (.cancel ^java.util.concurrent.ScheduledFuture fut false))
       (let [fut (.schedule executor
                   ^Runnable (fn []
                               (swap! pending dissoc abs-path)
                               (try
                                 (process-fn abs-path)
                                 (catch Exception e
                                   (util/log "WARN: watcher process error for" abs-path "-" (.getMessage e)))))
                   (long debounce-ms)
                   java.util.concurrent.TimeUnit/MILLISECONDS)]
         (swap! pending assoc abs-path fut)))
     :stop
     (fn []
       (.shutdown executor)
       (.awaitTermination executor 5 java.util.concurrent.TimeUnit/SECONDS))}))

;; ---------------------------------------------------------------------------
;; Index save debouncing
;; ---------------------------------------------------------------------------

(defn- debounced-save
  "Coalesce index saves to at most every save-interval-ms. Returns
   {:mark-dirty mark-fn :stop drain-fn}. :stop shuts the executor and
   waits for any in-flight save, so no save can race the shutdown hook's
   final save-index! on meta.edn (which spit writes non-atomically)."
  [save-interval-ms config hnsw-index-atom file-mtimes-atom]
  (let [dirty (atom false)
        executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)
        pending (atom nil)]
    {:mark-dirty
     (fn mark-dirty []
       (reset! dirty true)
       (when-let [fut @pending] (.cancel ^java.util.concurrent.ScheduledFuture fut false))
       (reset! pending
               (.schedule executor
                 ^Runnable (fn []
                             (when @dirty
                               (try
                                 (idx/save-index! @hnsw-index-atom (:index-dir config)
                                                  @file-mtimes-atom
                                                  (.size ^com.github.jelmerk.hnswlib.core.hnsw.HnswIndex @hnsw-index-atom)
                                                  (:model config) (get-in config [:hnsw :dimensions]))
                                 (reset! dirty false)
                                 (catch Exception e
                                   (util/log "WARN: debounced save failed:" (.getMessage e))))))
                 (long save-interval-ms)
                 java.util.concurrent.TimeUnit/MILLISECONDS)))
     :stop
     (fn []
       (.shutdown executor)
       (.awaitTermination executor 5 java.util.concurrent.TimeUnit/SECONDS))}))

;; ---------------------------------------------------------------------------
;; Exclude filtering
;; ---------------------------------------------------------------------------

(defn- excluded-dir?
  "Check if a relative dir path should be excluded.
   Matches any path segment: 'daily/data/xx' matches exclude 'data/'."
  [rel-path excludes]
  (let [segments (str/split (str rel-path "/") #"/")]
    (some (fn [excl]
            (let [e (str/replace excl #"/$" "")]
              (some #(= % e) segments)))
          excludes)))

;; ---------------------------------------------------------------------------
;; File change processing
;; ---------------------------------------------------------------------------

(defn- process-file-change!
  "Re-index a single changed/created file. Marks save-fn dirty after changes."
  [config hnsw-index-atom file-mtimes-atom save-fn ^String abs-path]
  (let [f (java.io.File. abs-path)]
    (if (.exists f)
      ;; File changed or created
      (let [chunks (try (parser/parse-file f)
                        (catch Exception e
                          (util/log "WARN: parse failed for" abs-path "-" (.getMessage e))
                          nil))]
        (when chunks
          (let [ids-to-remove (into [] (keep (fn [item]
                                               (when (= (.-file-path ^org_roam_mcp.index.NoteItem item) abs-path)
                                                 (.id ^com.github.jelmerk.hnswlib.core.Item item))))
                                      (idx/all-items @hnsw-index-atom))]
            (doseq [id ids-to-remove]
              (sec/remove-for-id! id)
              (idx/remove-item! @hnsw-index-atom id)))
          (let [texts (mapv parser/embedding-text chunks)
                vectors (embeddings/embed-all config texts (or (:embed-batch-size config) 32))]
            (doseq [[chunk vec] (map vector chunks vectors)]
              (when vec
                (let [item (idx/make-item chunk vec)]
                  (idx/add-item! @hnsw-index-atom item)
                  (sec/update-for-item! item)))))
          (let [org-dir (util/expand-home (:org-dir config))
                rel (subs abs-path (inc (count org-dir)))]
            (swap! file-mtimes-atom assoc rel (.lastModified f)))
          (save-fn)
          (util/log "Reindexed" abs-path ":" (count chunks) "chunks")))
      ;; File deleted
      (let [ids-to-remove (into [] (keep (fn [item]
                                           (when (= (.-file-path ^org_roam_mcp.index.NoteItem item) abs-path)
                                             (.id ^com.github.jelmerk.hnswlib.core.Item item))))
                                  (idx/all-items @hnsw-index-atom))]
        (when (seq ids-to-remove)
          (doseq [id ids-to-remove]
            (sec/remove-for-id! id)
            (idx/remove-item! @hnsw-index-atom id))
          (let [org-dir (util/expand-home (:org-dir config))
                rel (subs abs-path (inc (count org-dir)))]
            (swap! file-mtimes-atom dissoc rel))
          (save-fn)
          (util/log "Removed" (count ids-to-remove) "chunks for deleted" abs-path))))))

;; ---------------------------------------------------------------------------
;; DirectoryWatcher setup
;; ---------------------------------------------------------------------------

(defn start!
  "Start watching the org directory for changes using native OS events.
   Returns a function to stop the watcher. The stop fn drains the processor
   and save executors in order so the shutdown hook's save-index! runs alone."
  [config hnsw-index-atom file-mtimes-atom]
  (let [org-dir (util/expand-home (:org-dir config))
        excludes (or (:exclude config) [])
        debounce-ms (or (:watch-debounce-ms config) 2000)
        save-ctl (debounced-save 30000 config hnsw-index-atom file-mtimes-atom)
        mark-dirty (:mark-dirty save-ctl)
        processor-ctl (debounced-processor
                       debounce-ms
                       (fn [abs-path]
                         (process-file-change! config hnsw-index-atom file-mtimes-atom mark-dirty abs-path)))
        on-change (:process processor-ctl)
        watcher (-> (DirectoryWatcher/builder)
                    (.path (Paths/get org-dir (into-array String [])))
                    (.listener (reify io.methvin.watcher.DirectoryChangeListener
                                 (onEvent [_ event]
                                   (let [^Path path (.path event)
                                         abs (.toString (.toAbsolutePath path))
                                         rel (subs abs (inc (count org-dir)))]
                                     (when (and (str/ends-with? abs ".org")
                                                (not (excluded-dir? rel excludes)))
                                       (on-change abs))))))
                    (.build))]
    (.watchAsync watcher)
    (util/log "File watcher started on" org-dir "(native events)")
    ;; Return stop function. Order matters: stop the event source first, let
    ;; any in-flight file processing finish (it may call mark-dirty), then
    ;; drain the save executor so the shutdown hook can save-index! alone.
    (fn []
      (.close watcher)
      ((:stop processor-ctl))
      ((:stop save-ctl))
      (util/log "File watcher stopped"))))
