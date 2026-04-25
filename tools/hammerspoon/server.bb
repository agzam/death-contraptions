#!/usr/bin/env bb
;; MCP server for Hammerspoon Fennel nREPL.
;; Connects to the jeejah nREPL exposed by Spacehammer and evaluates Fennel code.
;; macOS only.
;; Author: Ag Ibragimov - github.com/agzam

(require '[bencode.core :as bencode]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(import '[java.net Socket]
        '[java.io ByteArrayOutputStream PushbackInputStream])

;;; ---------- Platform gate ----------

(when-not (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
  (binding [*out* *err*]
    (println "hammerspoon MCP server requires macOS"))
  (System/exit 1))

;;; ---------- nREPL connection ----------

(def !conn
  "Atom holding {:socket :in :out :session} or nil."
  (atom nil))

(def nrepl-port-file
  (str (System/getProperty "user.home") "/.spacehammer/.nrepl-port"))

(defn bytes->str
  "Coerce byte arrays from bencode responses to strings."
  [v]
  (cond
    (bytes? v)  (String. ^bytes v "UTF-8")
    (vector? v) (mapv bytes->str v)
    (map? v)    (update-vals v bytes->str)
    :else       v))

(defn send-bencode!
  "Bencode msg to a byte buffer, then write+flush to the socket stream.
   Avoids write-bencode directly on a socket stream - that breaks after
   interleaved reads (likely a buffering quirk in bb's bencode.core)."
  [^java.io.OutputStream out msg]
  (let [baos (ByteArrayOutputStream.)]
    (bencode/write-bencode baos msg)
    (.write out (.toByteArray baos))
    (.flush out)))

(defn read-port
  "Read nREPL port from .nrepl-port file."
  []
  (try
    (let [s (str/trim (slurp nrepl-port-file))]
      (parse-long s))
    (catch Exception _
      nil)))

(defn nrepl-connect!
  "Open TCP socket to nREPL, clone a session. Returns connection map or nil."
  []
  (when-let [port (read-port)]
    (try
      (let [sock (doto (Socket. "localhost" (int port))
                   (.setSoTimeout 10000))
            in   (PushbackInputStream. (.getInputStream sock))
            out  (.getOutputStream sock)]
        ;; clone a session
        (send-bencode! out {"op" "clone" "id" "init"})
        (let [resp    (bytes->str (bencode/read-bencode in))
              session (get resp "new-session")]
          (when session
            {:socket sock :in in :out out :session session})))
      (catch Exception _
        nil))))

(defn ensure-conn!
  "Return a live connection, reconnecting if needed."
  []
  (let [c @!conn]
    (if (and c (not (.isClosed (:socket c))))
      c
      (let [c (nrepl-connect!)]
        (reset! !conn c)
        c))))

(defn disconnect!
  "Close current connection."
  []
  (when-let [{:keys [socket]} @!conn]
    (try (.close socket) (catch Exception _)))
  (reset! !conn nil))

;;; ---------- nREPL eval ----------

(defn nrepl-eval
  "Evaluate Fennel code via nREPL. Returns {:value ... :out ... :err ...}."
  [code]
  (if-let [{:keys [in out session]} (ensure-conn!)]
    (try
      (let [id (str (random-uuid))]
        (send-bencode! out {"op"      "eval"
                            "code"    code
                            "id"      id
                            "session" session})
        (loop [value nil stdout "" stderr ""]
          (let [resp   (bytes->str (bencode/read-bencode in))
                value  (or (get resp "value") value)
                stdout (str stdout (get resp "out" ""))
                stderr (str stderr (get resp "err" ""))
                status (get resp "status")]
            (if (and status (some #(= "done" %) status))
              {:value value :out stdout :err stderr}
              (recur value stdout stderr)))))
      (catch Exception e
        (disconnect!)
        {:err (str "nREPL connection error: " (.getMessage e))}))
    {:err (str "Cannot connect to Hammerspoon nREPL. "
               "Ensure Spacehammer is running and "
               nrepl-port-file " exists.")}))

;;; ---------- Tool handler ----------

(defn do-eval
  "Handle hammerspoon-eval tool call."
  [{:strs [code]}]
  (if (str/blank? code)
    {:content [{:type "text" :text "Error: code is required"}]
     :isError true}
    (let [{:keys [value out err]} (nrepl-eval code)
          parts (cond-> []
                  (not (str/blank? out)) (conj out)
                  (not (str/blank? err)) (conj (str "STDERR: " err))
                  value                  (conj (str/trim-newline value)))]
      (if (and (str/blank? value) (not (str/blank? err)))
        {:content [{:type "text" :text (str "Error: " err)}]
         :isError true}
        {:content [{:type "text"
                    :text (if (seq parts)
                            (str/join "\n" parts)
                            "nil")}]}))))

;;; ---------- MCP boilerplate ----------

(def server-info
  {:name    "hammerspoon"
   :version "0.1.0"})

(def tools
  [{:name        "hammerspoon-eval"
    :description "Evaluate Fennel code in the running Hammerspoon (Spacehammer) nREPL. Returns the evaluation result. Use for inspecting or manipulating Hammerspoon state, debugging Spacehammer modules, and testing Fennel expressions in the live environment."
    :inputSchema
    {:type       "object"
     :properties {:code {:type        "string"
                         :description "Fennel code to evaluate"}}
     :required   ["code"]}}])

(defn handle-request
  "Dispatch a JSON-RPC request."
  [{:strs [id method params]}]
  (case method
    "initialize"
    {:jsonrpc "2.0" :id id
     :result {:protocolVersion "2024-11-05"
              :capabilities    {:tools {}}
              :serverInfo      server-info}}

    "notifications/initialized" nil

    "tools/list"
    {:jsonrpc "2.0" :id id
     :result {:tools tools}}

    "tools/call"
    (let [{tool "name" args "arguments"} params]
      {:jsonrpc "2.0" :id id
       :result (case tool
                 "hammerspoon-eval" (do-eval args)
                 {:content [{:type "text"
                             :text (str "Unknown tool: " tool)}]
                  :isError true})})

    ;; Unknown method
    nil))

;;; ---------- Main loop ----------

(when (= *file* (System/getProperty "babashka.file"))
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (when-not (str/blank? line)
      (when-let [res (handle-request (json/parse-string line))]
        (println (json/generate-string res))
        (flush)))))
