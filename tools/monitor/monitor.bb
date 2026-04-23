#!/usr/bin/env bb
;; Conditional stream watcher: spawn a long-running source command and emit
;; to stdout only when a line matches the supplied filter. Intended to run
;; under ECA's bg-job harness so the LLM pays tokens only on matches, not
;; on the raw firehose.
;; Author: Ag Ibragimov - github.com/agzam

(require '[babashka.process :as proc]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(def usage
  "monitor.bb -- conditional stream watcher for ECA background tasks

Usage:
  monitor.bb --source <shell-cmd>
             [(--jq <expr> | --regex <pat> | --grep <str>)]
             [--live] [--digest-interval <sec>] [--digest-top <n>]
             [--max-matches <n>] [--max-runtime <sec>]

Arguments:
  --source <shell-cmd>  Long-running producer whose stdout is scanned.
                        Runs under 'sh -c <cmd>'; on monitor shutdown the
                        whole descendant tree is destroyed so kubectl/stern/
                        jq/etc. never become orphans.

Filter (choose exactly one):
  --jq <expr>   jq expression applied per input line. Match = jq stdout is
                non-empty and not literal 'null'; its value is emitted.
                Write selection and reshape in one expression, e.g.
                  'select(.type==\"Warning\") | {ns:.involvedObject.namespace, reason:.reason}'
                Source must emit one JSON value per line (ND-JSON).
  --regex <p>   Match lines against a Java regex; matching line is emitted
                verbatim.
  --grep <s>    Match lines containing a literal substring; matching line
                is emitted verbatim.

Optional:
  --live        Push each match into the ECA chat via
                  emacsclient --eval '(eca-chat-send-prompt \"monitor: ...\")'
                as fire-and-forget. Wakes the agent on every match without
                waiting for a user turn. Requires an Emacs server reachable
                via emacsclient on PATH. --max-matches defaults to unlimited
                when --live is set; use --max-runtime to bound total runtime.
  --digest-interval <sec>
                Enable digest mode: buffer matched lines and emit a deduped
                top-N histogram every N seconds instead of per-match output.
                Filter flags are optional in this mode (no filter = digest
                every source line). Combines with --live (the digest becomes
                the pushed prompt). Default --digest-top is 20.
  --digest-top <n>
                Cap the number of entries in each digest (default: 20).
  --max-matches <n>
                Exit 0 after N matches. Default: 1 without --live/--digest,
                unlimited with either.
  --max-runtime <sec>
                Hard ceiling on total runtime in seconds. Monitor logs a
                reason line to stderr and exits 0 after the budget elapses
                even if no match fired, destroying the source tree.

Semantics:
  - Non-matches produce zero stdout output, so eca__bg_job read_output peeks
    are essentially free.
  - Diagnostics go to stderr. Stdout is match-only.
  - Source command must stay line-buffered on its stdout. Pipelines that
    block-buffer need stdbuf -oL (Linux) or gstdbuf -oL (macOS coreutils).
  - --jq forks jq per input line. Fine for event streams; for very chatty
    log tails prefer --regex.
")

(defn log!
  "Diagnostics on stderr - stdout is reserved for matches."
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn emit!
  "Write a match to stdout with explicit flush so bg-job peeks see it without
  waiting for the OS pipe buffer to fill on quiet streams."
  [s]
  (println s)
  (.flush *out*))

(def ^:private bare-flags
  "Boolean-style flags that take no value. Normalised to 'true' before the
  pair-based parser runs so the rest of the parser stays uniform."
  #{"--live"})

(defn- normalize-bare-flags
  "Splice a literal 'true' after every bare flag so the pair parser can treat
  every flag as key/value. Keeps the parser a single shape."
  [argv]
  (reduce (fn [acc tok]
            (if (contains? bare-flags tok)
              (conj acc tok "true")
              (conj acc tok)))
          []
          argv))

(defn parse-args
  "Minimal positional flag parser. Three filter flags are mutually exclusive
  and one is required. Throws ex-info with :usage? / :help? so -main renders
  a single clean error or the help page."
  [argv]
  (when (or (empty? argv)
            (contains? #{"-h" "--help" "help"} (first argv)))
    (throw (ex-info "help" {:help? true})))
  (let [argv  (normalize-bare-flags argv)
        pairs (loop [[k v & more] argv acc []]
                (cond
                  (nil? k) acc
                  (nil? v) (throw (ex-info (str "flag " k " needs a value")
                                           {:usage? true}))
                  :else (recur more (conj acc [k v]))))
        parse-positive-int
        (fn [flag v]
          (let [n (try (Long/parseLong v) (catch Exception _ nil))]
            (when-not (and n (pos? n))
              (throw (ex-info (str flag " must be a positive integer, got: "
                                   (pr-str v))
                              {:usage? true})))
            n))
        m (reduce (fn [m [k v]]
                    (case k
                      "--source"          (assoc m :source v)
                      "--jq"              (assoc m :jq v)
                      "--regex"           (assoc m :regex v)
                      "--grep"            (assoc m :grep v)
                      "--live"            (assoc m :live true)
                      "--max-runtime"     (assoc m :max-runtime
                                                 (parse-positive-int "--max-runtime" v))
                      "--max-matches"     (assoc m :max-matches
                                                 (parse-positive-int "--max-matches" v))
                      "--digest-interval" (assoc m :digest-interval
                                                 (parse-positive-int "--digest-interval" v))
                      "--digest-top"      (assoc m :digest-top
                                                 (parse-positive-int "--digest-top" v))
                      (throw (ex-info (str "unknown flag " k)
                                      {:usage? true}))))
                  {}
                  pairs)
        filters (select-keys m [:jq :regex :grep])
        digest? (boolean (:digest-interval m))]
    (when-not (:source m)
      (throw (ex-info "--source is required" {:usage? true})))
    (cond
      (and (empty? filters) (not digest?))
      (throw (ex-info "one of --jq / --regex / --grep is required (or pass --digest-interval to match everything)"
                      {:usage? true}))

      (< 1 (count filters))
      (throw (ex-info "pass exactly one of --jq / --regex / --grep"
                      {:usage? true})))
    (let [[ftype fexpr] (first filters)
          live?         (boolean (:live m))]
      {:source          (:source m)
       :filter-type     ftype
       :filter-expr     fexpr
       :live            live?
       :max-runtime     (:max-runtime m)
       ;; --max-matches default: 1 in stop-on-match mode, unlimited in live
       ;; and digest modes (both are long-running by intent).
       :max-matches     (or (:max-matches m)
                            (when-not (or live? digest?) 1))
       :digest-interval (:digest-interval m)
       :digest-top      (or (:digest-top m) 20)})))

(defn elisp-string
  "Encode s as an Elisp double-quoted string literal. Escapes backslash,
  double-quote, and newline - the three characters that actually break an
  'emacsclient --eval' argument."
  [s]
  (str "\""
       (-> s
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\n" "\\n"))
       "\""))

(defn push-to-eca!
  "Fire-and-forget emacsclient call that injects a prompt into the active ECA
  chat so the agent wakes immediately on a match. Runs in a future so the
  match loop never blocks on emacsclient latency. Errors go to stderr; they
  must not crash the monitor because one failed push should not stop the
  watcher."
  [match]
  (future
    (try
      (let [expr (str "(eca-chat-send-prompt "
                      (elisp-string (str "monitor: " match))
                      ")")
            {:keys [exit err]} (shell/sh "emacsclient" "--eval" expr)]
        (when-not (zero? exit)
          (log! (str "monitor: emacsclient push failed (exit "
                     exit "): " (str/trim (or err ""))))))
      (catch Exception e
        (log! (str "monitor: emacsclient push errored: " (.getMessage e)))))))

(defn match-regex
  "Emit the whole line on a regex hit - keeps text streams self-describing
  without asking the user to restate the format."
  [pattern line]
  (when (re-find (re-pattern pattern) line) line))

(defn match-grep
  "Literal substring variant - avoids regex-escape footguns for common cases."
  [needle line]
  (when (str/includes? line needle) line))

(defn match-jq
  "Shell out to jq per input line. A non-empty non-null jq stdout counts as
  a match and its value becomes the emission, letting selection and reshape
  share one expression. Forks jq per line - simple, correct, and fast enough
  for event streams; swap to a persistent jq child only if throughput demands."
  [expr line]
  (let [{:keys [exit out]} (shell/sh "jq" "-c" expr :in line)
        s (when (zero? exit) (str/trim out))]
    (when (and (seq s) (not= s "null")) s)))

(defn make-matcher
  "Close over the filter expression so the hot loop is a single call.
  No filter (nil filter-type) is only reachable in digest mode - the parser
  ensures a filter is set in every other mode. identity passes every line."
  [{:keys [filter-type filter-expr]}]
  (case filter-type
    nil    identity
    :regex #(match-regex filter-expr %)
    :grep  #(match-grep filter-expr %)
    :jq    #(match-jq filter-expr %)))

(defn which
  "Resolve a binary path, nil when not on PATH. Used for jq preflight."
  [cmd]
  (let [{:keys [exit out]} (shell/sh "which" cmd)]
    (when (zero? exit) (str/trim out))))

(defn preflight!
  "Fail fast for the two predictable setup errors: missing jq and malformed
  jq expression. Running jq once against '{}' surfaces syntax errors at
  startup instead of silently producing zero matches forever."
  [{:keys [filter-type filter-expr]}]
  (when (= :jq filter-type)
    (when-not (which "jq")
      (log! "monitor: jq not found on PATH - install jq or use --regex/--grep")
      (System/exit 2))
    (let [{:keys [exit err]} (shell/sh "jq" "-c" filter-expr :in "{}")]
      (when (= exit 3) ; jq's exit code for compile errors
        (log! (str "monitor: jq compile error: " (str/trim err)))
        (System/exit 2)))))

(defn- destroy-tree!
  "Kill the sh wrapper and any descendants (kubectl, stern, jq, the source's
  own children). Plain Process.destroy() only signals the direct child, and
  sh does not forward SIGTERM to its children, so orphans would keep running.
  Uses ProcessHandle.descendants() which Babashka's JVM (9+) provides."
  [^Process p]
  (try
    (when (.isAlive p)
      (doseq [h (-> p .toHandle .descendants .iterator iterator-seq)]
        (try (.destroy ^java.lang.ProcessHandle h) (catch Exception _)))
      (.destroy p))
    (catch Exception _)))

;;; ---------- Digest mode ----------

(defn normalize-line
  "Collapse instance-identifying tokens so the same class of event folds
  together when bucketed. Without this, every k8s event line is unique
  because of random pod hashes, cronjob iteration numbers, and the age
  column, and dedup degenerates to identity.

  Transformations (ordered):
    - age column:                      '60m' '5s' '2h' '1d' '3w' -> <AGE>
    - long digit runs (5+ digits):     '29616080'               -> <N>
    - hash-like suffixes:              '-a1b2c3d4e5' '-ltgtv'   -> -<ID>

  Deliberately keeps single-digit ordinals (e.g. 'postgresql-1' in
  StatefulSet pods) distinct because they usually carry meaning."
  [line]
  (-> line
      (str/replace #"\b\d+[smhdw]\b" "<AGE>")
      (str/replace #"\b\d{5,}\b" "<N>")
      (str/replace #"-[a-z0-9]{5,10}(?=\s|:|/|$)" "-<ID>")))

(def ^:private digest-state
  "Per-window counters:
    {:buckets {normalized-line -> {:count N :example raw-first-line}}
     :total   N}
  Bucketing by normalized form folds instance variation; keeping the first
  raw line as the example makes the digest readable without showing ugly
  <N>/<ID> tokens back to the user."
  (atom {:buckets {} :total 0}))

(defn add-to-digest!
  "Bucket a line by its normalized form, count occurrences, and remember the
  first raw exemplar seen per bucket."
  [line]
  (let [key (normalize-line line)]
    (swap! digest-state
           (fn [s]
             (-> s
                 (update :total inc)
                 (update-in [:buckets key]
                            (fn [b]
                              (if b
                                (update b :count inc)
                                {:count 1 :example line}))))))))

(defn build-digest-message
  "Format a digest string from buckets. Header reports total lines and
  distinct classes; rows show each bucket's count and exemplar line, sorted
  by count descending. Extracted so tests can pin the exact layout."
  [interval buckets total top-n]
  (let [distinct-count (count buckets)
        top            (->> buckets
                            (sort-by (fn [[_ b]] (- (:count b))))
                            (take top-n))
        header         (format "monitor digest (%ds window, %d lines, %d classes):"
                               interval total distinct-count)
        rows           (map (fn [[_ {:keys [count example]}]]
                              (format "  %dx %s" count example))
                            top)]
    (str/join "\n" (cons header rows))))

(defn flush-digest!
  "Produce a digest from the current window, emit it, optionally push it, and
  reset the window. Suppress empty windows - no sense spamming a 'nothing
  happened' message to the agent every interval."
  [{:keys [digest-interval digest-top live]}]
  (let [snapshot (loop [] ; swap-and-return-old via compare-and-set
                   (let [old @digest-state]
                     (if (compare-and-set! digest-state old {:buckets {} :total 0})
                       old
                       (recur))))
        {:keys [buckets total]} snapshot]
    (when (pos? total)
      (let [msg (build-digest-message digest-interval buckets total digest-top)]
        (emit! msg)
        (when live (push-to-eca! msg))))))

(defn- arm-digest-ticker!
  "Daemon thread that flushes the digest every interval until JVM exit."
  [spec]
  (let [interval (:digest-interval spec)]
    (doto (Thread. (fn []
                     (try
                       (loop []
                         (Thread/sleep (* 1000 interval))
                         (flush-digest! spec)
                         (recur))
                       (catch InterruptedException _))))
      (.setDaemon true)
      (.start))))

(defn- arm-max-runtime!
  "Start a daemon thread that System/exits after the budget elapses. Daemon
  so the JVM can still exit normally when the source ends first; System/exit
  triggers the shutdown hook that destroys the source tree."
  [^long seconds]
  (doto (Thread. (fn []
                   (try
                     (Thread/sleep (* 1000 seconds))
                     (log! (str "monitor: --max-runtime " seconds
                                "s reached, exiting"))
                     (System/exit 0)
                     (catch InterruptedException _))))
    (.setDaemon true)
    (.start)))

(defn run-loop!
  "Spawn the source as a child of sh -c and stream its stdout through the
  matcher. Three routing modes on each match:
    - digest: accumulate into the window buffer; a separate ticker flushes.
    - live:   emit + fire-and-forget emacsclient push.
    - plain:  emit + optionally exit when --max-matches is reached.
  Source stderr is inherited so operator-visible errors surface naturally.
  On JVM shutdown, destroy-tree! cleans up the whole process subtree and we
  flush one final digest if digest mode is on. Named run-loop! to avoid
  shadowing clojure.core/run!."
  [{:keys [source live max-runtime max-matches digest-interval] :as spec}]
  (let [matcher (make-matcher spec)
        hits    (atom 0)
        child   (proc/process ["sh" "-c" source]
                              {:out :stream :err :inherit})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do
                                  (when digest-interval (flush-digest! spec))
                                  (destroy-tree! (:proc child)))))
    (when max-runtime
      (arm-max-runtime! max-runtime))
    (when digest-interval
      (arm-digest-ticker! spec))
    (with-open [rdr (io/reader (:out child))]
      (doseq [line (line-seq rdr)]
        (when-let [out (matcher line)]
          (if digest-interval
            (add-to-digest! out)
            (do
              (emit! out)
              (when live (push-to-eca! out))
              (when max-matches
                (let [n (swap! hits inc)]
                  (when (<= max-matches n)
                    (log! (str "monitor: --max-matches " max-matches
                               " reached, exiting"))
                    (System/exit 0)))))))))
    (:exit @child)))

(defn -main [& argv]
  (try
    (let [spec     (parse-args argv)
          time-b   (some->> (:max-runtime spec) (format " max-runtime=%ds"))
          count-b  (some->> (:max-matches spec) (format " max-matches=%d"))
          live-b   (when (:live spec) " live=yes")
          digest-b (some->> (:digest-interval spec)
                            (format " digest-interval=%ds"))
          top-b    (when (:digest-interval spec)
                     (format " digest-top=%d" (:digest-top spec)))]
      (preflight! spec)
      (log! (format "monitor: source=%s filter=%s%s%s%s%s%s"
                    (pr-str (:source spec))
                    (if-let [ft (:filter-type spec)] (name ft) "none")
                    (or live-b "")
                    (or count-b "")
                    (or time-b "")
                    (or digest-b "")
                    (or top-b "")))
      (System/exit (or (run-loop! spec) 0)))
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [help? usage?]} (ex-data e)]
        (cond
          help?  (do (println usage) (System/exit 0))
          usage? (do (log! (str "monitor: " (.getMessage e)))
                     (log! usage)
                     (System/exit 2))
          :else  (throw e))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
