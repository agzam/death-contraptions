#!/usr/bin/env bb
;; MCP server for nREPL evaluation.
;; Connects to Clojure/ClojureScript/Babashka/nbb REPLs via nREPL protocol.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[nrepl-mcp.client :as client]
         '[nrepl-mcp.discovery :as discovery]
         '[nrepl-mcp.sessions :as sessions]
         '[nrepl-mcp.delimiters :as delimiters])

(def ^:private max-output-chars 8500)

(defn- truncate
  "Cap output size to avoid blowing up MCP message limits."
  [s]
  (if (< max-output-chars (count s))
    (str (subs s 0 max-output-chars)
         (format "\n[truncated %d/%d chars]"
                 max-output-chars (count s)))
    s))

(def server-info
  {:name "nrepl" :version "0.2.0"})

;; ---------------------------------------------------------------------------
;; Discovery cache
;; ---------------------------------------------------------------------------

(def ^:private discovery-cache (atom nil)) ;; {:ports [...] :ts epoch-ms}
(def ^:private cache-ttl-ms 30000)

(defn- warmup-ports!
  "Pre-establish connections and sessions for discovered ports."
  [ports]
  (future
    (doseq [{:keys [port status]} ports
            :when (= :connected status)]
      (try
        (client/get-connection! "localhost" port)
        (sessions/get-session! "localhost" port)
        (catch Exception _)))))

(defn- cached-discover-port
  "Return a single auto-discovered port, using a 30s cache to avoid
   filesystem walks + socket probes on every eval."
  []
  (let [now (System/currentTimeMillis)
        cached @discovery-cache
        ports (if (and cached (< (- now (:ts cached)) cache-ttl-ms))
                (:ports cached)
                (let [fresh (discovery/discover-ports)]
                  (reset! discovery-cache {:ports fresh :ts now})
                  (warmup-ports! fresh)
                  fresh))]
    (when (= 1 (count ports))
      (:port (first ports)))))

(defn invalidate-discovery-cache! []
  (reset! discovery-cache nil))

(defn cljs-port?
  "True when the discovery cache classifies this port as a cljs runtime
   (nbb/shadow-cljs), where eval returns a promise that must be awaited."
  [port]
  (boolean (some (fn [p] (and (= port (:port p))
                              (#{:nbb :shadow-cljs} (:type p))))
                 (:ports @discovery-cache))))

;; ---------------------------------------------------------------------------
;; Tool definitions - intentionally minimal for token efficiency.
;; nrepl-doc, nrepl-load-file, nrepl-interrupt removed: the LLM can
;; (doc sym), (load-file path), or ask the user to restart the REPL.
;; ---------------------------------------------------------------------------

(def nrepl-eval-tool
  {:name "nrepl-eval"
   :description "Eval Clojure/CLJS via nREPL. Connects to clj, bb, nbb, or shadow-cljs. Session state persists across calls. Port auto-discovered from .nrepl-port when omitted."
   :inputSchema
   {:type "object"
    :properties {:code {:type "string"
                        :description "Clojure code to evaluate."}
                 :port {:type "integer"
                        :description "nREPL port. Omit to auto-discover."}
                 :host {:type "string"
                        :description "nREPL host (default: localhost)."}
                 :ns {:type "string"
                      :description "Namespace to eval in."}
                 :timeout_ms {:type "integer"
                              :description "Timeout in ms (default: 30000)."}
                 :reset_session {:type "boolean"
                                 :description "Reset session before eval."}
                 :await {:type "boolean"
                         :description "Resolve a returned promise before replying (cljs/nbb). Auto-detected for nbb/shadow-cljs sessions; set explicitly to override."}}
    :required ["code"]}})

(def nrepl-list-ports-tool
  {:name "nrepl-list-ports"
   :description "Discover running nREPL servers. Scans .nrepl-port files upward from working directory, probes each for type (clj/bb/nbb/shadow-cljs)."
   :inputSchema
   {:type "object"
    :properties {:start_dir {:type "string"
                             :description "Directory to scan from (default: cwd)."}}
    :required []}})

;; ---------------------------------------------------------------------------
;; Tool handlers
;; ---------------------------------------------------------------------------

(defn eval-nrepl
  [{:strs [code port host ns timeout_ms reset_session await]}]
  (let [port (or port (cached-discover-port))
        host (or host "localhost")]
    (if-not port
      (let [found (discovery/find-port-files nil)]
        {:content [{:type "text"
                    :text (if (empty? found)
                            "No nREPL port found. Start a REPL: clj -M:nrepl | bb nrepl-server | npx nbb nrepl-server"
                            (str "Multiple nREPL ports, specify one:\n"
                                 (str/join "\n" (map #(str "  " (:port %) " - " (:project-root %)) found))))}]
         :isError true})

      (try
        (let [{repair-code :code repaired? :repaired? repair-note :note}
              (delimiters/repair code)
              code (if repaired? repair-code code)
              session (if reset_session
                        (sessions/reset-session! host port)
                        (sessions/get-session! host port))
              await? (if (some? await) await (cljs-port? port))
              eval-fn (if await? client/eval-code-await client/eval-code)
              result (eval-fn
                      {:host host :port port :code code
                       :ns ns :session session
                       :timeout-ms (or timeout_ms 30000)})]
          (if (:timed-out? result)
            {:content [{:type "text"
                        :text (format "Timed out (%dms)." (or timeout_ms 30000))}]
             :isError true}

            (let [{:keys [value out err ex root-ex]} result
                  result-ns (:ns result)
                  has-error? (some? ex)]
              ;; Track namespace for this session
              (when result-ns
                (sessions/update-ns! port result-ns))
              (let [current-ns (or result-ns (sessions/current-ns port))]
                (cond->
                  {:content
                   (cond->
                     [(if has-error?
                        {:type "text"
                         :text (truncate
                                (str/join "\n" (remove nil? [ex (when root-ex (str "root: " root-ex))
                                                             (when (seq err) err)])))}
                        {:type "text"
                         :text (truncate (if (seq value) value "nil"))})]

                     (seq out)
                     (conj {:type "text" :text (truncate (str "[out]\n" out))})

                     (and (not has-error?) (seq err))
                     (conj {:type "text" :text (truncate (str "[err]\n" err))})

                     current-ns
                     (conj {:type "text" :text (str "[ns] " current-ns)})

                     repaired?
                     (conj {:type "text" :text (str "[delimiter-repair] " repair-note)}))}

                  has-error? (assoc :isError true))))))

        (catch java.net.ConnectException e
          ;; Server truly unreachable - invalidate discovery so we re-scan
          (client/close-connection! host port)
          (sessions/evict-session! port)
          (invalidate-discovery-cache!)
          {:content [{:type "text"
                      :text (format "Cannot connect to nREPL at %s:%d - %s"
                                    host port (.getMessage e))}]
           :isError true})

        (catch java.io.IOException _e
          ;; Transient socket error - evict connection but try to preserve session.
          ;; The session may still be valid on the nREPL server after reconnecting.
          (client/close-connection! host port)
          (try
            (let [conn (client/get-connection! host port)
                  sid (sessions/get-session! host port)
                  responses (client/nrepl-op!
                             conn {"op" "eval" "code" "true" "session" sid}
                             :timeout-ms 3000)]
              (if (some #(= sid (get % "session")) responses)
                {:content [{:type "text"
                            :text "nREPL connection restored, session preserved. Retry your eval."}]}
                (do (sessions/evict-session! port)
                    {:content [{:type "text"
                                :text "nREPL reconnected but session lost. Fresh session on next eval."}]})))
            (catch Exception _
              (sessions/evict-session! port)
              {:content [{:type "text"
                          :text "nREPL connection lost. Will reconnect on next eval."}]
               :isError true})))

        (catch Exception e
          {:content [{:type "text"
                      :text (str "nREPL error: " (.getMessage e))}]
           :isError true})))))

(defn list-ports
  [{:strs [start_dir]}]
  (invalidate-discovery-cache!)
  (let [ports (discovery/discover-ports :start-dir start_dir)]
    (if (empty? ports)
      {:content [{:type "text"
                  :text "No nREPL servers found. Start one: clj -M:nrepl | bb nrepl-server | npx nbb nrepl-server"}]}
      {:content [{:type "text"
                  :text (str/join "\n"
                                  (for [{:keys [port type project-root status]} ports]
                                    (format "port %d | %s | %s | %s"
                                            port (name type) (name status)
                                            (or project-root "?"))))}]})))

;; ---------------------------------------------------------------------------
;; MCP JSON-RPC dispatch
;; ---------------------------------------------------------------------------

(defn handle-request
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
     :result {:tools [nrepl-eval-tool nrepl-list-ports-tool]}}

    "tools/call"
    (let [{tool "name" args "arguments"} params]
      {:jsonrpc "2.0" :id id
       :result (case tool
                 "nrepl-eval" (eval-nrepl args)
                 "nrepl-list-ports" (list-ports args)
                 {:content [{:type "text" :text (str "Unknown tool: " tool)}]
                  :isError true})})

    nil))

(defn- shutdown! []
  (sessions/close-all-sessions!)
  (client/close-all!))

(when (= *file* (System/getProperty "babashka.file"))
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable shutdown!))
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (when-not (str/blank? line)
      (when-let [res (handle-request (json/parse-string line))]
        (println (json/generate-string res))
        (flush)))))
