(ns org-roam-mcp.util
  (:require [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.net Socket]
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

(defn- parse-ollama-host-port
  "Extract host and port from an Ollama URL string."
  [url]
  (let [u (java.net.URI. url)]
    [(.getHost u) (let [p (.getPort u)] (if (neg? p) 11434 p))]))

(defonce ^:private ssh-tunnel-process (atom nil))

(defn ensure-ssh-tunnel!
  "Start an SSH tunnel if configured and the Ollama port is not yet reachable.
   Returns true if Ollama is reachable after this call."
  [{:keys [ollama-url ssh-tunnel]}]
  (let [[host port] (parse-ollama-host-port ollama-url)]
    (if (port-open? host port 2000)
      (do (log "Ollama reachable at" ollama-url)
          true)
      (if-not (:enabled ssh-tunnel)
        (do (log "WARN: Ollama unreachable at" ollama-url "and SSH tunnel not enabled")
            false)
        (let [{:keys [^String host remote-port local-port]} ssh-tunnel
              local-port (or local-port port)
              remote-port (or remote-port 11434)
              cmd ["ssh" "-N" "-L"
                   (str local-port ":localhost:" remote-port)
                   host]]
          (log "Starting SSH tunnel:" (str/join " " cmd))
          (try
            (let [proc (-> (ProcessBuilder. ^java.util.List cmd)
                           (.redirectError ProcessBuilder$Redirect/INHERIT)
                           .start)]
              (reset! ssh-tunnel-process proc)
              ;; Wait for tunnel to establish
              (loop [attempts 0]
                (cond
                  (port-open? "localhost" local-port 1000)
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

(defn stop-ssh-tunnel!
  "Destroy the SSH tunnel process if one is running."
  []
  (when-let [^Process proc @ssh-tunnel-process]
    (when (.isAlive proc)
      (log "Stopping SSH tunnel")
      (.destroy proc))
    (reset! ssh-tunnel-process nil)))
