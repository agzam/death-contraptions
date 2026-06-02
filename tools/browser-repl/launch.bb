#!/usr/bin/env bb
;; browser-repl launcher: start a live nbb nREPL with the browser_repl stdlib
;; preloaded and a session mode applied, then write .nrepl-port so the nrepl MCP
;; auto-discovers it. The agent drives the session via `nrepl-eval`.
;;
;; Runs nbb in this tool dir (so node_modules + nbb.edn resolve), supervises it
;; (Ctrl-C / kill tears the browser down), and prints the port for explicit
;; `:port` use when auto-discovery dirs do not line up.
;;
;; Uses a minimal bencode nREPL client + def-on-resolve/poll await pattern
;; (nbb's nREPL returns the promise object, not its value) - the same pattern
;; baked into the nrepl MCP as eval-code-await. See plan.md.

(require '[babashka.cli :as cli]
         '[babashka.process :as proc]
         '[bencode.core :as bc]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '[java.net Socket ServerSocket InetSocketAddress]
        '[java.io PushbackInputStream])

(def ^:private script-dir
  ;; canonicalize first so a relative *file* (load-file from a test) still
  ;; resolves a non-nil parent dir.
  (-> *file* io/file .getCanonicalFile .getParentFile .getCanonicalPath))

(def cli-spec
  {:port          {:coerce :long    :desc "nREPL port (default: a free ephemeral port)."}
   :mode          {:coerce :keyword :default :fresh :desc "Session mode: fresh|persistent|attach."}
   :headless      {:coerce :boolean :default false  :desc "Run chromium headless."}
   :user-data-dir {:coerce :string  :desc "Persistent profile dir (mode persistent)."}
   :cdp-endpoint  {:coerce :string  :desc "CDP endpoint, e.g. http://127.0.0.1:9222 (mode attach)."}
   :port-file-dir {:coerce :string  :desc "Dir to write .nrepl-port into (default: the tool dir; the registry handles cross-repo discovery)."}})

;; --------------------------------------------------------------------------
;; Pure builders (side-effect free so launch_test.bb can assert them)
;; --------------------------------------------------------------------------

(defn nbb-cmd
  "argv to start the nbb nREPL on `port`. Uses the tool's pinned local nbb so we
   never depend on a global / mise install."
  [tool-dir port]
  [(str tool-dir "/node_modules/.bin/nbb") "nrepl-server" ":port" (str port)])

(defn session-config
  "The config map handed to (browser-repl/configure!), built from parsed opts.
   Only keys the stdlib understands, nils dropped."
  [{:keys [mode headless user-data-dir cdp-endpoint]}]
  (cond-> {:mode (or mode :fresh) :headless? (boolean headless)}
    user-data-dir (assoc :user-data-dir user-data-dir)
    cdp-endpoint  (assoc :cdp-endpoint cdp-endpoint)))

(defn init-forms
  "Ordered nREPL forms to bring the session up. nbb's SCI has no `load-file`, so
   we (require) the stdlib off the classpath (nbb runs in this tool dir, whose
   nbb.edn puts \".\" on :paths). -main awaits all three: require and start!
   return promises over nREPL; configure! is synchronous but harmless to await.
   Names are fully qualified so the launcher's session ns is irrelevant."
  [config]
  ["(require (quote [browser-repl]))"
   (str "(browser-repl/configure! " (pr-str config) ")")
   "(browser-repl/start!)"])

;; def-on-resolve + poll wrapper. nbb returns the promise, not its value, so we
;; stash the resolved value into *launch-val* and flip *launch-pending*, then
;; poll. Ends with a keyword (not the promise) to dodge nbb's spurious
;; "nth not supported" printer error on promise-returning forms.
(def ^:private await-require "(require (quote [promesa.core :as p]))")
(def ^:private await-pre "(do (def *launch-pending* true) (p/catch (p/let [v ")
(def ^:private await-post
  (str "] (def *launch-val* v) (def *launch-pending* false)) "
       "(fn [e] (def *launch-val* (str \"ERR \" (or (.-message e) e))) (def *launch-pending* false))) "
       ":launch/kicked)"))

(defn await-wrap
  "Wrap a form so its (possibly promise) result lands in *launch-val*. Pure."
  [form]
  (str await-pre form await-post))

;; --------------------------------------------------------------------------
;; Minimal bencode nREPL client (single persistent connection for init, so the
;; session ns stays stable across the kickoff/poll evals)
;; --------------------------------------------------------------------------

(defn- decode [x]
  (cond
    (bytes? x)      (String. ^bytes x "UTF-8")
    (map? x)        (into {} (map (fn [[k v]] [(decode k) (decode v)])) x)
    (sequential? x) (mapv decode x)
    :else           x))

(defn- open-conn [port]
  (let [s (Socket. "127.0.0.1" (int port))]
    {:socket s :out (.getOutputStream s) :in (PushbackInputStream. (.getInputStream s))}))

(defn- close-conn [{:keys [^Socket socket]}]
  (when (and socket (not (.isClosed socket))) (.close socket)))

(defn- eval-on
  "Send one eval op over an open conn, accumulate until status done."
  [{:keys [in out]} code]
  (bc/write-bencode out {"op" "eval" "code" code})
  (.flush ^java.io.OutputStream out)
  (loop [acc {:value nil :out "" :err "" :ex nil}]
    (let [msg (decode (bc/read-bencode in))
          st  (set (get msg "status"))
          acc (cond-> acc
                (get msg "value") (assoc :value (get msg "value"))
                (get msg "out")   (update :out str (get msg "out"))
                (get msg "err")   (update :err str (get msg "err"))
                (get msg "ex")    (assoc :ex (get msg "ex")))]
      (if (contains? st "done") acc (recur acc)))))

(defn- await-eval
  "Require promesa, kick off an await-wrapped form, poll *launch-pending* until
   resolved (or timeout). Returns the resolved *launch-val* string."
  [conn form timeout-ms]
  (eval-on conn await-require)
  (eval-on conn (await-wrap form))             ; kickoff; result intentionally ignored
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [pend (:value (eval-on conn "*launch-pending*"))]
        (cond
          (= pend "false")                        (:value (eval-on conn "*launch-val*"))
          (< deadline (System/currentTimeMillis)) "TIMEOUT (start! still pending)"
          :else                                   (do (Thread/sleep 150) (recur)))))))

;; --------------------------------------------------------------------------
;; Side effects
;; --------------------------------------------------------------------------

(defn free-port
  "Grab a free ephemeral TCP port (small TOCTOU window; fine for launch)."
  []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- port-open?
  [port]
  (try
    (with-open [s (Socket.)]
      (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 500)
      true)
    (catch Exception _ false)))

(defn- wait-port
  "Poll until the nREPL port accepts connections, or timeout. Returns boolean."
  [port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (port-open? port)                       true
        (< deadline (System/currentTimeMillis)) false
        :else                                   (do (Thread/sleep 200) (recur))))))

(defn- die [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

(defn -main [& args]
  (let [opts          (cli/parse-opts args {:spec cli-spec})
        tool-dir      script-dir
        nbb-bin       (io/file tool-dir "node_modules" ".bin" "nbb")
        stdlib        (str tool-dir "/browser_repl.cljs")
        port          (or (:port opts) (free-port))
        ;; default to the tool dir, NOT the CWD: running the launcher from a real
        ;; repo (e.g. the first ECA workspace) must not litter it with .nrepl-port.
        ;; cross-repo discovery is the registry's job now, not a CWD breadcrumb.
        port-file-dir (or (:port-file-dir opts) tool-dir)
        port-file     (io/file port-file-dir ".nrepl-port")
        own-port-file (io/file tool-dir ".nrepl-port")   ; nbb writes its own here
        registry-dir  (or (System/getenv "NREPL_MCP_PORT_DIR")
                          (str (System/getProperty "user.home") "/.cache/nrepl-ports"))
        ;; advertise here so the nrepl MCP auto-discovers us from a sibling repo
        registry-file (io/file registry-dir (str "browser-repl-" port ".port"))
        config        (session-config opts)]
    (when-not (.exists nbb-bin)
      (die (str "browser-repl: nbb not installed. Run:\n  (cd " tool-dir " && npm install)")))
    (when-not (.exists (io/file stdlib))
      (die (str "browser-repl: stdlib missing at " stdlib)))
    (let [p (proc/process (nbb-cmd tool-dir port) {:dir tool-dir :inherit true})]
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. ^Runnable
                (fn []
                  ;; Tear down the whole tree. nbb+playwright trap SIGTERM to
                  ;; close chromium gracefully and can outlive a plain destroy,
                  ;; so snapshot descendants, SIGTERM, then SIGKILL survivors.
                  (try
                    (let [pr   (:proc p)
                          kids (doall (iterator-seq
                                       (.iterator (.descendants (.toHandle ^Process pr)))))]
                      (.destroy ^Process pr)
                      (doseq [k kids] (try (.destroy k) (catch Exception _)))
                      (Thread/sleep 400)
                      (when (.isAlive ^Process pr) (.destroyForcibly ^Process pr))
                      (doseq [k kids] (when (.isAlive k) (.destroyForcibly k))))
                    (catch Exception _))
                  (doseq [f [port-file own-port-file registry-file]]
                    (try (when (.exists f) (.delete f)) (catch Exception _))))))
      (when-not (wait-port port 20000)
        (die "browser-repl: nbb nREPL did not come up within 20s."))
      (let [conn (open-conn port)]
        (try
          ;; await every form: require + start! return promises over nREPL, and
          ;; awaiting the sync configure! is harmless. Sequential awaits also
          ;; guarantee require finishes before configure!/start! reference the ns.
          (let [res (last (doall (map #(await-eval conn % 30000) (init-forms config))))]
            (spit port-file (str port))
            (try (.mkdirs (io/file registry-dir)) (spit registry-file (str port))
                 (catch Exception _))
            (println)
            (println "browser-repl session up.")
            (println (str "  nREPL port : " port "  (mode " (:mode config)
                          ", headless " (:headless? config) ")"))
            (println (str "  .nrepl-port: " (.getCanonicalPath port-file)))
            (when (and (string? res) (str/starts-with? res "ERR"))
              (println (str "  warn: start! -> " res
                            "  (session is alive; fix and retry from the REPL)")))
            (println "  drive it via nrepl-eval, e.g.:")
            (println "    (require '[browser-repl :as b])")
            (println "    (b/goto \"https://example.com\")  (b/aria)  (b/texts \"h1\")")
            (println "  stop: kill this process (the browser closes)."))
          (finally (close-conn conn))))
      @p)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
