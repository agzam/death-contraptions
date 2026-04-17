(ns org-roam-mcp.util
  (:require [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.net HttpURLConnection Socket URL]
           [java.security MessageDigest]
           [java.time Instant]))

(def ^:private home (System/getProperty "user.home"))

(defn expand-home
  "Expand ~/... to absolute path."
  [path]
  (if (str/starts-with? path "~/")
    (str home (subs path 1))
    path))

(defn contract-home
  "Replace home prefix with ~/ for display."
  [path]
  (if (str/starts-with? path home)
    (str "~" (subs path (count home)))
    path))

(defn log
  "Log a message to stderr with timestamp."
  [& args]
  (binding [*out* *err*]
    (println (str "[" (Instant/now) "]") (str/join " " args))
    (flush)))

(defn sha256
  "SHA-256 hex digest of a string."
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

;; ---------------------------------------------------------------------------
;; SSH tunnel management
;; ---------------------------------------------------------------------------

(defn port-open?
  "Check if a TCP port is accepting connections on the given host."
  [host port timeout-ms]
  (try
    (with-open [^Socket sock (Socket.)]
      (.connect sock (java.net.InetSocketAddress. ^String host (int port)) (int timeout-ms))
      true)
    (catch Exception _ false)))

(defn ollama-healthy?
  "HTTP GET /api/tags to verify Ollama actually responds, not just that the
   local TCP port is bound. A zombie SSH tunnel (alive ssh process, bound
   local port, dead remote) passes `port-open?` but fails real HTTP, so we
   need this to decide whether to restart the tunnel."
  [ollama-url]
  (try
    (let [url  (URL. (str ollama-url "/api/tags"))
          conn ^HttpURLConnection (.openConnection url)]
      (try
        (.setConnectTimeout conn 500)
        (.setReadTimeout conn 1500)
        (.setRequestMethod conn "GET")
        (= 200 (.getResponseCode conn))
        (finally
          (.disconnect conn))))
    (catch Exception _ false)))

(defn- parse-ollama-host-port
  "Extract host and port from an Ollama URL string."
  [url]
  (let [u (java.net.URI. url)]
    [(.getHost u) (let [p (.getPort u)] (if (neg? p) 11434 p))]))

(defonce ^:private ssh-tunnel-process (atom nil))

(defonce ^:private tunnel-lock (Object.))

(defn stop-ssh-tunnel!
  "Destroy the SSH tunnel process if one is running."
  []
  (when-let [^Process proc @ssh-tunnel-process]
    (when (.isAlive proc)
      (log "Stopping SSH tunnel")
      (.destroy proc))
    (reset! ssh-tunnel-process nil)))

(defn ensure-ssh-tunnel!
  "Idempotent: ensure Ollama is reachable, starting an SSH tunnel if needed.
   Safe to call on hot paths - fast and quiet when the tunnel is healthy.
   Uses `ollama-healthy?` (real HTTP) so zombie SSH tunnels with bound local
   sockets but dead remotes get detected and restarted.
   Uses a lock so concurrent callers don't race on tunnel startup.
   Returns true if Ollama is reachable after this call."
  [{:keys [ollama-url ssh-tunnel]}]
  (cond
    ;; Fast happy path - real HTTP probe, no log
    (ollama-healthy? ollama-url)
    true

    ;; Tunnel not configured - nothing we can do
    (not (:enabled ssh-tunnel))
    (do (log "WARN: Ollama unreachable at" ollama-url "and SSH tunnel not enabled")
        false)

    :else
    (locking tunnel-lock
      ;; Re-check inside the lock - another thread may have already fixed it
      (if (ollama-healthy? ollama-url)
        true
        (let [[_ port] (parse-ollama-host-port ollama-url)
              {:keys [^String host remote-port local-port]} ssh-tunnel
              local-port  (or local-port port)
              remote-port (or remote-port 11434)
              cmd ["ssh" "-N" "-L"
                   (str local-port ":localhost:" remote-port)
                   host]]
          ;; Clean up any dead/zombie previous process before starting a new one
          (stop-ssh-tunnel!)
          (log "Starting SSH tunnel:" (str/join " " cmd))
          (try
            (let [proc (-> (ProcessBuilder. ^java.util.List cmd)
                           (.redirectError ProcessBuilder$Redirect/INHERIT)
                           .start)]
              (reset! ssh-tunnel-process proc)
              ;; Wait for tunnel to establish. Use ollama-healthy? (not
              ;; port-open?) so we don't declare success on the mere local
              ;; port binding before the SSH session is actually carrying
              ;; traffic.
              (loop [attempts 0]
                (cond
                  (ollama-healthy? ollama-url)
                  (do (log "SSH tunnel established, Ollama reachable")
                      true)

                  (< 10 attempts)
                  (do (log "WARN: SSH tunnel failed to establish after 10 attempts")
                      false)

                  :else
                  (do (Thread/sleep 1000)
                      (recur (inc attempts))))))
            (catch Exception e
              (log "WARN: failed to start SSH tunnel:" (or (ex-message e) (str (class e))))
              false)))))))
