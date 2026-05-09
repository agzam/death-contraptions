(ns nrepl-mcp.sessions
  "nREPL session management. Clones a session per port so namespace
   bindings, defs, and *1/*e persist across MCP tool calls.
   Validates persisted sessions on load; auto-reclones if stale."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl-mcp.client :as client]))

(def ^:private sessions (atom {})) ;; port -> session-id

(def ^:private session-dir
  (str (System/getProperty "java.io.tmpdir") "/eca-nrepl-sessions"))

(defn- session-file [port]
  (io/file session-dir (str port ".session")))

(defn- load-session
  "Read a persisted session-id from disk."
  [port]
  (let [f (session-file port)]
    (when (.isFile f)
      (let [s (str/trim (slurp f))]
        (when-not (str/blank? s) s)))))

(defn- save-session [port session-id]
  (let [f (session-file port)]
    (.mkdirs (.getParentFile f))
    (spit f session-id)))

(defn- delete-session-file [port]
  (let [f (session-file port)]
    (when (.exists f) (.delete f))))

(defn- clone-session!
  "Send clone op to nREPL, returns new session-id or nil."
  [host port]
  (try
    (let [conn (client/get-connection! host port)
          responses (client/nrepl-op! conn {"op" "clone"} :timeout-ms 5000)]
      (some #(get % "new-session") responses))
    (catch Exception _ nil)))

(defn- validate-session
  "Check if a session-id is still alive on the nREPL server.
   Sends a trivial eval; if it returns normally the session exists."
  [host port session-id]
  (try
    (let [conn (client/get-connection! host port)
          responses (client/nrepl-op!
                     conn
                     {"op" "eval" "code" "true" "session" session-id}
                     :timeout-ms 3000)]
      ;; If the session is unknown, nREPL creates a transient one and
      ;; the response comes back without error. But the returned
      ;; session field won't match our session-id on some implementations.
      ;; Check that at least one response has our session-id.
      (some #(= session-id (get % "session")) responses))
    (catch Exception _ false)))

(defn get-session!
  "Get or create a persistent nREPL session for host:port.
   Checks in-memory cache, then disk (with validation), then clones new."
  [host port]
  (or (get @sessions port)
      (when-let [persisted (load-session port)]
        (if (validate-session host port persisted)
          (do (swap! sessions assoc port persisted)
              persisted)
          (do (delete-session-file port)
              nil)))
      (when-let [sid (clone-session! host port)]
        (swap! sessions assoc port sid)
        (save-session port sid)
        sid)))

(defn reset-session!
  "Close current session and create a fresh one."
  [host port]
  (when-let [old-sid (get @sessions port)]
    (try
      (let [conn (client/get-connection! host port)]
        (client/nrepl-op! conn {"op" "close" "session" old-sid} :timeout-ms 2000))
      (catch Exception _)))
  (swap! sessions dissoc port)
  (delete-session-file port)
  (get-session! host port))

(defn evict-session!
  "Remove a session from cache without closing it on the server.
   Used when the server is known to be unreachable."
  [port]
  (swap! sessions dissoc port)
  (delete-session-file port))

(defn clear-all! []
  (reset! sessions {}))
