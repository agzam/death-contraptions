(ns org-roam-mcp.emacs
  "Emacsclient wrapper for note CRUD operations.
   All write operations go through Emacs to ensure proper :ID: generation,
   vulpea DB sync, and buffer management."
  (:require [clojure.string :as str]))

(def ^:private default-timeout-ms 15000)

(defn emacsclient-eval
  "Evaluate elisp via emacsclient. Returns the printed result string.
   Throws on timeout, Emacs server not running, or eval error."
  ([elisp] (emacsclient-eval elisp default-timeout-ms))
  ([elisp timeout-ms]
   (let [proc (.start (ProcessBuilder.
                       ["emacsclient" "--eval" (str elisp)]))
         exited (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
     (if-not exited
       (do (.destroyForcibly proc)
           (throw (ex-info "emacsclient timed out" {:timeout-ms timeout-ms})))
       (let [exit (.exitValue proc)
             stdout (slurp (.getInputStream proc))
             stderr (slurp (.getErrorStream proc))]
         (when-not (zero? exit)
           (throw (ex-info (str "emacsclient failed: " (str/trim stderr))
                           {:exit exit :stderr stderr})))
         (str/trim stdout))))))

(defn available?
  "Check if Emacs server is running and responsive."
  []
  (try
    (let [result (emacsclient-eval "(+ 1 1)" 5000)]
      (= "2" result))
    (catch Exception _
      false)))

(defn eval-sexp
  "Evaluate an elisp sexp, parse the result.
   Wraps the form in progn for multi-form expressions.
   Returns the raw result string from emacsclient."
  [sexp]
  (emacsclient-eval sexp))
