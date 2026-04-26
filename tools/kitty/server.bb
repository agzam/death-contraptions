#!/usr/bin/env bb
;; MCP server for Kitty terminal: send commands, launch tabs, read output.
;; Uses Kitty's remote control protocol via `kitten @`.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

;;; ---------- Socket discovery ----------

(defn find-socket
  "Locate the Kitty remote control unix socket."
  []
  (let [matches (->> (file-seq (java.io.File. "/tmp"))
                     (filter #(re-matches #"kitty_sock-\d+" (.getName %)))
                     (sort-by #(.lastModified %) >))]
    (when-let [f (first matches)]
      (.getAbsolutePath f))))

;;; ---------- Shell helpers ----------

(defn kitten-cmd
  "Build a kitten @ command vector targeting the given socket."
  [socket & args]
  (into ["kitten" "@" "--to" (str "unix:" socket)] args))

(defn sh-ok
  "Run command, return {:ok true :out ...} or {:ok false :err ...}."
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (if (zero? exit)
      {:ok true :out (str/trim out)}
      {:ok false :err (str/trim (str err " " out))})))

(defn run-kitten
  "Run a kitten @ command against the discovered socket."
  [socket & args]
  (apply sh-ok (apply kitten-cmd socket args)))

;;; ---------- Tool handlers ----------

(defn do-ls
  "List all Kitty OS windows, tabs, and windows as JSON."
  [_args]
  (if-let [socket (find-socket)]
    (let [{:keys [ok out err]} (run-kitten socket "ls")]
      (if ok
        {:content [{:type "text" :text out}]}
        {:content [{:type "text" :text (str "kitty ls failed: " err)}]
         :isError true}))
    {:content [{:type "text" :text "No Kitty socket found in /tmp"}]
     :isError true}))

(defn- active-window
  "Return the active window map from kitty @ ls, or nil."
  [socket]
  (let [{:keys [ok out]} (run-kitten socket "ls")]
    (when ok
      (let [data   (json/parse-string out)
            os-win (->> data (filter #(get % "is_active")) first)
            tab    (->> (get os-win "tabs") (filter #(get % "is_active")) first)
            win    (->> (get tab "windows") (filter #(get % "is_active")) first)]
        win))))

(defn do-send
  "Type command text into the active Kitty window without pressing Enter."
  [args]
  (if-let [socket (find-socket)]
    (if-let [win (active-window socket)]
      (if (not (get win "at_prompt"))
        (let [fg  (first (get win "foreground_processes"))
              cmd (str/join " " (get fg "cmdline"))]
          {:content [{:type "text"
                      :text (str "Active window is busy running: " cmd
                                 ". Switch to an idle tab first.")}]
           :isError true})
        (let [text   (get args "text")
              win-id (str "id:" (get win "id"))
              cmd    (kitten-cmd socket "send-text" "--match" win-id text)
              {:keys [ok err]} (apply sh-ok cmd)]
          (if ok
            (do (sh-ok "open" "-a" "kitty")
                {:content [{:type "text"
                            :text (str "Sent to active Kitty window (" win-id "): " text)}]})
            {:content [{:type "text" :text (str "send-text failed: " err)}]
             :isError true})))
      {:content [{:type "text" :text "Could not determine active Kitty window"}]
       :isError true})
    {:content [{:type "text" :text "No Kitty socket found in /tmp"}]
     :isError true}))

(defn do-launch
  "Open a new Kitty tab and run a command."
  [args]
  (if-let [socket (find-socket)]
    (let [command (get args "command")
          cwd    (get args "cwd")
          hold   (get args "hold" false)
          title  (get args "title")
          cmd    (cond-> (kitten-cmd socket "launch" "--type=tab")
                  cwd   (into ["--cwd" cwd])
                  hold  (conj "--hold")
                  title (into ["--tab-title" title])
                  true  (into ["/bin/zsh" "-c" command]))]
      (let [{:keys [ok out err]} (apply sh-ok cmd)]
        (if ok
          {:content [{:type "text"
                      :text (str "Launched new tab"
                                 (when title (str " '" title "'"))
                                 " running: " command
                                 (when-not (str/blank? out)
                                   (str "\nWindow ID: " out)))}]}
          {:content [{:type "text" :text (str "launch failed: " err)}]
           :isError true})))
    {:content [{:type "text" :text "No Kitty socket found in /tmp"}]
     :isError true}))

(defn do-get-text
  "Read the screen buffer of a Kitty window."
  [args]
  (if-let [socket (find-socket)]
    (let [match  (get args "match")
          extent (get args "extent" "screen")
          cmd    (cond-> (kitten-cmd socket "get-text" "--extent" extent)
                  match (into ["--match" match]))]
      (let [{:keys [ok out err]} (apply sh-ok cmd)]
        (if ok
          {:content [{:type "text" :text out}]}
          {:content [{:type "text" :text (str "get-text failed: " err)}]
           :isError true})))
    {:content [{:type "text" :text "No Kitty socket found in /tmp"}]
     :isError true}))

(defn do-focus
  "Focus a Kitty tab or window."
  [args]
  (if-let [socket (find-socket)]
    (let [match (get args "match")
          type  (get args "type" "window")
          subcmd (if (= type "tab") "focus-tab" "focus-window")
          cmd   (cond-> (kitten-cmd socket subcmd)
                  match (into ["--match" match]))]
      (let [{:keys [ok err]} (apply sh-ok cmd)]
        (if ok
          {:content [{:type "text"
                      :text (str "Focused " type
                                 (when match (str " (match: " match ")")))}]}
          {:content [{:type "text" :text (str "focus failed: " err)}]
           :isError true})))
    {:content [{:type "text" :text "No Kitty socket found in /tmp"}]
     :isError true}))

;;; ---------- MCP Server ----------

(def server-info {:name "kitty" :version "1.0.0"})

(def tools
  [{:name        "kitty-ls"
    :description "List Kitty windows and tabs as JSON with IDs, titles, PIDs, cwds."
    :inputSchema {:type "object" :properties {}}}

   {:name        "kitty-send"
    :description "Type text into active Kitty window without pressing Enter."
    :inputSchema
    {:type       "object"
     :properties {:text {:type        "string"
                         :description "Command text to type (will NOT be executed, no Enter sent)"}}
     :required   ["text"]}}

   {:name        "kitty-launch"
    :description "Open a new Kitty tab and run a command in it."
    :inputSchema
    {:type       "object"
     :properties {:command {:type        "string"
                            :description "Shell command to run in the new tab"}
                  :cwd     {:type        "string"
                            :description "Working directory for the new tab"}
                  :hold    {:type        "boolean"
                            :description "Keep tab open after command exits (default: false)"}
                  :title   {:type        "string"
                            :description "Tab title"}}
     :required   ["command"]}}

   {:name        "kitty-get-text"
    :description "Read the screen buffer of a Kitty window."
    :inputSchema
    {:type       "object"
     :properties {:match  {:type        "string"
                           :description "Window match expression. Omit for the active window."}
                  :extent {:type        "string"
                           :description "What to capture: 'screen' (default), 'all' (includes scrollback), 'selection'"}}}}

   {:name        "kitty-focus"
    :description "Focus a Kitty tab or window by match expression (e.g. 'id:42')."
    :inputSchema
    {:type       "object"
     :properties {:match {:type        "string"
                          :description "Match expression (e.g. 'id:42', 'title:build')"}
                  :type  {:type        "string"
                          :description "Focus target: 'window' (default) or 'tab'"}}
     :required   ["match"]}}])

(defn handle-request
  "Dispatch a JSON-RPC request by method name."
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
                 "kitty-ls"       (do-ls args)
                 "kitty-send"     (do-send args)
                 "kitty-launch"   (do-launch args)
                 "kitty-get-text" (do-get-text args)
                 "kitty-focus"    (do-focus args)
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
