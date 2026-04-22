#!/usr/bin/env bb
;; Qlik KB MCP launcher + stdio proxy. Reads QCS_API_TOKEN from config.edn
;; (populated by setup.bb from local-config.edn). A local knowledgebases.yaml
;; acts as an override for the default (binary-embedded) KB set.
;;
;; Two upstream warts this shim covers:
;;
;; 1. Pre-flight auth check. Upstream registers every tool whose KB health
;;    check failed and logs only to pino stderr (which ECA never surfaces),
;;    so bad/expired tokens manifest as cryptic mid-chat failures. We hit
;;    GET /v1/knowledgebases here; on 401/403 we die so ECA reports
;;    "MCP failed to start" instead.
;;
;; 2. tools/list annotation rewrite. Upstream ships annotations of
;;    {readOnlyHint=false, destructiveHint=true} for what are in fact
;;    read-only searches plus document downloads, triggering extra approval
;;    prompts in MCP clients that honor annotations. Rather than proxying
;;    every byte, we only parse/rewrite on the stdout path and pass
;;    everything else through verbatim.
;;
;; Author: Ag Ibragimov - github.com/agzam

(require '[babashka.http-client :as http]
         '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def ^:private script-dir
  (-> *file* io/file .getParentFile .getCanonicalPath))

(def ^:private bin-path
  (str script-dir "/bin/mcp-qlik-kb-server"))

(def ^:private local-yaml
  (str script-dir "/knowledgebases.yaml"))

(def ^:private api-base "https://qcs.us.qlikcloud.com/api")

(def ^:private correct-annotations
  ;; Every qlik-kb tool is a read-only Qlik Cloud GET (search / download);
  ;; none writes, so destructiveHint must be false. Same value for every
  ;; tool, so no per-name dispatch needed.
  {:readOnlyHint true
   :destructiveHint false
   :idempotentHint true
   :openWorldHint true})

(def ^:private config
  (let [f (io/file script-dir "config.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      {})))

(defn- die [msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit 1))

(defn- preflight!
  "Block launch on 401/403; warn-and-launch on anything else so a
  flaky backend doesn't keep agents from working on unrelated tasks."
  [token]
  (try
    (let [r (http/get (str api-base "/v1/knowledgebases")
                      {:headers {"Authorization" (str "Bearer " token)}
                       :throw false
                       :connect-timeout 3000
                       :timeout 5000})
          status (:status r)]
      (when (#{401 403} status)
        (die (str "qlik-kb: QCS_API_TOKEN rejected by Qlik Cloud (HTTP "
                  status "). Refresh the token at "
                  "https://qcs.us.qlikcloud.com/settings/api-keys and "
                  "update " script-dir "/config.edn."))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "qlik-kb: pre-flight non-auth error ("
                      (.getMessage e) "); launching anyway"))))))

(defn- rewrite-line
  "Parse one JSON-RPC frame; rewrite tool annotations when the payload
  shape matches a tools/list response. Non-JSON and other shapes pass
  through unchanged, so we never corrupt the transport."
  [line]
  (try
    (let [msg (json/parse-string line true)]
      (if (vector? (get-in msg [:result :tools]))
        (json/generate-string
         (update-in msg [:result :tools]
                    (fn [tools]
                      (mapv #(assoc % :annotations correct-annotations)
                            tools))))
        line))
    (catch Exception _
      line)))

(defn- pump-stdin!
  "Bytes from parent stdin into the binary's stdin. No parsing; stdio
  framing is the binary's problem."
  [child-in]
  (let [buf (byte-array 8192)]
    (try
      (loop []
        (let [n (.read System/in buf)]
          (when (pos? n)
            (.write child-in buf 0 n)
            (.flush child-in)
            (recur))))
      (catch Exception _)
      (finally (try (.close child-in) (catch Exception _))))))

(defn- pump-stdout!
  "Newline-delimited JSON frames from the binary to parent stdout,
  with annotation rewrite on the tools/list response only."
  [child-out]
  (let [reader (io/reader child-out)]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (println (rewrite-line line))
          (.flush System/out)
          (recur)))
      (catch Exception _))))

(defn- launch! [args extra-env]
  (let [p (proc/process args {:in :pipe :out :pipe :err :inherit
                              :extra-env extra-env})
        t-out (doto (Thread. #(pump-stdout! (:out p))) (.setDaemon true) .start)]
    (doto (Thread. #(pump-stdin! (:in p))) (.setDaemon true) .start)
    (let [exit-code (:exit @p)]
      ;; Drain any final frames the binary wrote before exiting.
      (.join t-out 500)
      (System/exit exit-code))))

(when-not (.canExecute (io/file bin-path))
  (die (str "qlik-kb binary missing at " bin-path
            "\nRun: bb " script-dir "/update.bb")))

(let [token (:qcs-api-token config)
      args (cond-> [bin-path]
             (.exists (io/file local-yaml)) (into ["--config" local-yaml]))
      env (cond-> {"TRANSPORT_MODE" "stdio"}
            token (assoc "QCS_API_TOKEN" token))]
  (when (nil? token)
    (die (str "qlik-kb: QCS_API_TOKEN missing from " script-dir
              "/config.edn. Add :qcs-api-token to local-config.edn "
              "under :servers {:qlik-kb {...}}, re-encrypt, then run "
              "bb setup.bb.")))
  ;; Skip pre-flight when a local knowledgebases.yaml exists - it may
  ;; override apiBase, which our hardcoded URL wouldn't reflect.
  (when-not (.exists (io/file local-yaml))
    (preflight! token))
  (launch! args env))
