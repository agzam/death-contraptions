#!/usr/bin/env bb
;; MCP server for elisp evaluation via emacsclient.
;; Writes code to a temp file and evals it - no shell escaping issues.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[babashka.process :as process]
         '[clojure.string :as str])

(import '[java.io File]
        '[java.util.concurrent TimeUnit]
        '[java.nio.file Files])

(def ^:private default-timeout-sec 30)
(def ^:private max-output-chars 50000)

(defn- truncate [s]
  (if (< max-output-chars (count s))
    (str (subs s 0 max-output-chars)
         (format "\n\n[output truncated at %d chars, %d total]"
                 max-output-chars (count s)))
    s))

(def server-info
  {:name "elisp-eval" :version "1.1.0"})

(def elisp-eval-tool
  {:name "elisp-eval"
   :description "Evaluate Emacs Lisp code in the running Emacs server. Returns the result of evaluation along with any new *Messages* and *trace-output* produced during evaluation. State persists between calls. Only the return value of the last expression is captured. Write elisp naturally with no escaping needed.

IMPORTANT constraints - this tool is synchronous (batch-mode, not interactive):
- NEVER call functions that enter recursive-edit or prompt for input (e.g., read-string, y-or-n-p, edebug stepping). These will hang until timeout.
- For debugging, use trace-function/trace-function-foreground instead of edebug. Backtraces are captured automatically on error. Trace output from *trace-output* is also captured automatically.
- For interactive modes (games, shells), pause timers and interact turn-by-turn via direct function calls and buffer reads.
- Use run-with-timer for async/non-blocking work.

Common debugging patterns:
- Describe a function: (describe-function 'foo) then (with-current-buffer (help-buffer) (buffer-substring-no-properties (point-min) (point-max)))
- Buffer info: (list :buffer (buffer-name) :file buffer-file-name :mode major-mode :minor minor-mode-list :point (point) :modified (buffer-modified-p))
- Active timers: (mapcar (lambda (t) (list (timer--function t) (timer--time t))) timer-list)
- Running processes: (mapcar (lambda (p) (list (process-name p) (process-status p) (process-command p))) (process-list))
- Find function source: (find-lisp-object-file-name 'foo (symbol-function 'foo))
- Read special buffers (strip text properties to avoid output explosion): (with-current-buffer BUF (buffer-substring-no-properties (point-min) (point-max)))

Session hygiene:
- Minimize UI disruption: open buffers in background (e.g., with-current-buffer, display-buffer) unless foreground is required.
- Kill any temporary buffers created during diagnostic or exploratory work.
- When modifying hooks, advice, or global state via config files, mirror those changes in the running session: remove stale hooks/advice before adding, reload features as needed.

MCP server management:
- After modifying an MCP server script (e.g., files in ~/.config/eca/tools/), restart it so changes take effect. Server names are keys in ~/.config/eca/config.json under mcpServers.
  (let ((session (eca-session)))
    (eca-api-notify session :method \"mcp/stopServer\" :params (list :name SERVER_NAME))
    (run-with-timer 2 nil (lambda () (eca-api-notify (eca-session) :method \"mcp/startServer\" :params (list :name SERVER_NAME)))))"
   :inputSchema
   {:type "object"
    :properties {:code {:type "string"
                        :description "Emacs Lisp code to evaluate."}
                 :timeout {:type "integer"
                           :description "Timeout in seconds (default: 30). Increase for long-running operations like profiling, sit-for, or package installs."}
                 :print_length {:type "integer"
                                :description "Max list/vector elements to print (default: 200). Set to -1 for unlimited."}
                 :print_level {:type "integer"
                               :description "Max nesting depth to print (default: 10). Set to -1 for unlimited."}}
    :required ["code"]}})

(def screenshot-tool
  {:name "emacs-screenshot"
   :description "Capture a screenshot of the current Emacs frame as a PNG image. Useful for debugging display issues, themes, mode-line problems, or observing visual state. macOS only (uses screencapture)."
   :inputSchema
   {:type "object"
    :properties {}
    :required []}})

(defn eval-elisp [{:strs [code timeout print_length print_level]}]
  (let [timeout-sec (or timeout default-timeout-sec)
        pl  (if print_length (if (= print_length -1) "nil" (str print_length)) "200")
        plv (if print_level  (if (= print_level -1)  "nil" (str print_level))  "10")
        tmp        (File/createTempFile "eca-elisp-"  ".el")
        msgs-tmp   (File/createTempFile "eca-msgs-"   ".txt")
        bt-tmp     (File/createTempFile "eca-bt-"     ".txt")
        trace-tmp  (File/createTempFile "eca-trace-"  ".txt")
        result-tmp (File/createTempFile "eca-result-" ".txt")
        path        (.getAbsolutePath tmp)
        msgs-path   (.getAbsolutePath msgs-tmp)
        bt-path     (.getAbsolutePath bt-tmp)
        trace-path  (.getAbsolutePath trace-tmp)
        result-path (.getAbsolutePath result-tmp)]
    (try
      (spit tmp code)
      (let [wrapper (format "(let* ((eca--bt-path \"%s\")
       (eca--result-path \"%s\")
       (eca--trace-path \"%s\")
       (msgs-buf (get-buffer-create \"*Messages*\"))
       (msgs-pos (with-current-buffer msgs-buf (point-max)))
       (trace-buf (get-buffer \"*trace-output*\"))
       (trace-pos (when trace-buf (with-current-buffer trace-buf (point-max))))
       (result (with-temp-buffer
                 (insert-file-contents \"%s\")
                 (goto-char (point-min))
                 (let (forms)
                   (condition-case nil
                       (while t (push (read (current-buffer)) forms))
                     (end-of-file nil))
                   (let ((eca--code (cons 'progn (nreverse forms))))
                     (if (fboundp 'handler-bind)
                         (handler-bind
                             ((error
                               (lambda (_e)
                                 (write-region
                                  (with-output-to-string (backtrace))
                                  nil eca--bt-path nil 'silent))))
                           (eval eca--code t))
                       (eval eca--code t))))))
       (new-msgs (with-current-buffer msgs-buf
                   (let ((s (string-trim (buffer-substring-no-properties msgs-pos (point-max)))))
                     (and (not (string-empty-p s)) s))))
       (new-trace (let ((tb (or trace-buf (get-buffer \"*trace-output*\"))))
                    (when tb
                      (with-current-buffer tb
                        (let ((s (string-trim
                                  (buffer-substring-no-properties
                                   (or trace-pos (point-min)) (point-max)))))
                          (and (not (string-empty-p s)) s)))))))
  (when new-msgs
    (write-region new-msgs nil \"%s\" nil 'silent))
  (when new-trace
    (write-region new-trace nil eca--trace-path nil 'silent))
  (let ((print-length %s)
        (print-level %s))
    (write-region (prin1-to-string result) nil eca--result-path nil 'silent))
  result)" bt-path result-path trace-path path msgs-path pl plv)
            proc (process/process ["emacsclient" "--eval" wrapper]
                                  {:out :string :err :string})
            completed? (.waitFor (:proc proc) timeout-sec TimeUnit/SECONDS)]
        (if-not completed?
          (do (.destroyForcibly (:proc proc))
              {:content [{:type "text"
                          :text (format "Evaluation timed out (%ds). Process killed. This usually means the code entered an interactive prompt, recursive-edit, or infinite loop."
                                        timeout-sec)}]
               :isError true})
          (let [{:keys [exit out err]} @proc
                ;; Prefer result from file (has print limits) over stdout
                result-text (let [s (str/trim (slurp result-tmp))]
                              (when-not (str/blank? s) s))
                result (or result-text (str/trim out))
                messages (let [s (str/trim (slurp msgs-tmp))]
                           (when-not (str/blank? s) s))
                backtrace (let [s (str/trim (slurp bt-tmp))]
                            (when-not (str/blank? s) s))
                trace (let [s (str/trim (slurp trace-tmp))]
                        (when-not (str/blank? s) s))]
            (if (zero? exit)
              {:content (cond-> [{:type "text" :text (truncate result)}]
                          messages (conj {:type "text" :text (truncate (str "--- *Messages* ---\n" messages))})
                          trace    (conj {:type "text" :text (truncate (str "--- *trace-output* ---\n" trace))}))}
              {:content (cond-> [{:type "text" :text (truncate (str/trim (str out err)))}]
                          backtrace (conj {:type "text" :text (truncate (str "--- Backtrace ---\n" backtrace))}))
               :isError true}))))
      (finally
        (.delete tmp)
        (.delete msgs-tmp)
        (.delete bt-tmp)
        (.delete trace-tmp)
        (.delete result-tmp)))))

(defn- emacsclient-eval
  "Quick one-shot emacsclient eval. Returns trimmed stdout or nil."
  [expr]
  (let [proc (process/process ["emacsclient" "--eval" expr]
                              {:out :string :err :string})
        completed? (.waitFor (:proc proc) 5 TimeUnit/SECONDS)]
    (when (and completed? (zero? (:exit @proc)))
      (str/trim (:out @proc)))))

(defn- file->b64-image
  "Read a PNG file, return MCP image content."
  [^File f]
  (let [bytes (Files/readAllBytes (.toPath f))
        b64   (.encodeToString (java.util.Base64/getEncoder) bytes)]
    {:content [{:type "image" :data b64 :mimeType "image/png"}]}))

(defn- screenshot-error [msg]
  {:content [{:type "text" :text msg}] :isError true})

(defn- screenshot-macos
  "Get CGWindowID via Swift + CoreGraphics, then screencapture."
  []
  (let [swift-code (str "import Cocoa\n"
                        "let opts: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]\n"
                        "if let wl = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] {\n"
                        "  for w in wl {\n"
                        "    if let o = w[\"kCGWindowOwnerName\"] as? String, o == \"Emacs\",\n"
                        "       let l = w[\"kCGWindowLayer\"] as? Int, l == 0,\n"
                        "       let n = w[\"kCGWindowNumber\"] as? Int {\n"
                        "      print(n); break\n"
                        "    }\n"
                        "  }\n"
                        "}\n")
        wid-proc (process/process ["swift" "-e" swift-code]
                                  {:out :string :err :string})
        _        (.waitFor (:proc wid-proc) 15 TimeUnit/SECONDS)
        wid      (some-> @wid-proc :out str/trim)]
    (if (or (not (zero? (:exit @wid-proc))) (str/blank? wid))
      (screenshot-error "Could not find Emacs CGWindowID. Is Emacs a GUI window?")
      (let [tmp (File/createTempFile "emacs-screenshot-" ".png")
            tmp-path (.getAbsolutePath tmp)]
        (try
          (let [proc (process/process
                      ["screencapture" (str "-l" wid) "-x" "-o" tmp-path]
                      {:out :string :err :string})
                completed? (.waitFor (:proc proc) 10 TimeUnit/SECONDS)]
            (if (or (not completed?) (not (zero? (:exit @proc))))
              (screenshot-error "screencapture failed.")
              (file->b64-image tmp)))
          (finally (.delete tmp)))))))

(defn- screenshot-x11
  "Use Emacs outer-window-id + ImageMagick import."
  []
  (let [raw (emacsclient-eval "(frame-parameter nil 'outer-window-id)")
        wid (some-> raw (str/replace "\"" ""))]
    (if (or (str/blank? wid) (= wid "nil"))
      (screenshot-error "Could not get X11 window ID from Emacs.")
      (let [tmp (File/createTempFile "emacs-screenshot-" ".png")
            tmp-path (.getAbsolutePath tmp)]
        (try
          (let [proc (process/process ["import" "-window" wid tmp-path]
                                      {:out :string :err :string})
                completed? (.waitFor (:proc proc) 10 TimeUnit/SECONDS)]
            (if (or (not completed?) (not (zero? (:exit @proc))))
              (screenshot-error "import failed. Is ImageMagick installed?")
              (file->b64-image tmp)))
          (finally (.delete tmp)))))))

(defn- screenshot-pgtk
  "Wayland - try gnome-screenshot, fall back to grim."
  []
  (let [tmp      (File/createTempFile "emacs-screenshot-" ".png")
        tmp-path (.getAbsolutePath tmp)]
    (try
      (let [proc (process/process ["gnome-screenshot" "-w" "-f" tmp-path]
                                  {:out :string :err :string})
            completed? (.waitFor (:proc proc) 10 TimeUnit/SECONDS)]
        (if (and completed? (zero? (:exit @proc)))
          (file->b64-image tmp)
          ;; fallback to grim
          (let [grim (process/process ["grim" tmp-path]
                                      {:out :string :err :string})
                grim-ok? (.waitFor (:proc grim) 10 TimeUnit/SECONDS)]
            (if (and grim-ok? (zero? (:exit @grim)))
              (file->b64-image tmp)
              (screenshot-error "gnome-screenshot and grim both failed.")))))
      (finally (.delete tmp)))))

(defn take-screenshot []
  (let [ws (emacsclient-eval "(framep (selected-frame))")]
    (case ws
      "ns"   (screenshot-macos)
      "x"    (screenshot-x11)
      "pgtk" (screenshot-pgtk)
      (screenshot-error
       (str "Unsupported window system: " ws ". Requires GUI Emacs.")))))
(defn handle-request [{:strs [id method params]}]
  (case method
    "initialize"
    {:jsonrpc "2.0" :id id
     :result {:protocolVersion "2024-11-05"
              :capabilities {:tools {}}
              :serverInfo server-info}}
    
    "notifications/initialized" nil
    
    "tools/list"
    {:jsonrpc "2.0" :id id
     :result {:tools [elisp-eval-tool screenshot-tool]}}
    
    "tools/call"
    (let [{tool "name" args "arguments"} params]
      {:jsonrpc "2.0" :id id
       :result (case tool
                 "elisp-eval"       (eval-elisp args)
                 "emacs-screenshot" (take-screenshot)
                 {:content [{:type "text" :text (str "Unknown tool: " tool)}]
                  :isError true})})
    
    ;; Unknown method - ignore
    nil))

(doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (when-not (str/blank? line)
    (when-let [res (handle-request (json/parse-string line))]
      (println (json/generate-string res))
      (flush))))
