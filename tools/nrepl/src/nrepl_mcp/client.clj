(ns nrepl-mcp.client
  "Minimal nREPL client using Babashka's built-in bencode.
   Manages persistent connections keyed by host:port with auto-reconnect."
  (:require [bencode.core :as b]
            [clojure.string :as str])
  (:import [java.net Socket SocketTimeoutException InetSocketAddress]
           [java.io PushbackInputStream IOException]))

(def ^:private connections (atom {}))
(def ^:private msg-counter (atom 0))

(def ^:private connect-timeout-ms 5000)

(defn- next-id [] (str (swap! msg-counter inc)))

(defn- decode-val
  "Convert bencode byte arrays to strings recursively."
  [v]
  (cond
    (bytes? v) (String. ^bytes v "UTF-8")
    (sequential? v) (mapv decode-val v)
    (map? v) (into {} (map (fn [[k v]] [(decode-val k) (decode-val v)]) v))
    :else v))

(defn- decode-msg
  [m]
  (when m
    (into {} (map (fn [[k v]] [k (decode-val v)]) m))))

(defn connect!
  "Open a socket to an nREPL server. Returns {:socket :in :out :host :port}."
  [host port]
  (let [s (Socket.)]
    (.connect s (InetSocketAddress. ^String host (int port)) connect-timeout-ms)
    (let [out (.getOutputStream s)
          in (PushbackInputStream. (.getInputStream s))]
      {:socket s :in in :out out :host host :port port})))

(defn disconnect!
  [{:keys [socket]}]
  (when (and socket (not (.isClosed ^Socket socket)))
    (try (.close ^Socket socket) (catch Exception _))))

(defn- evict-connection!
  "Close and remove a cached connection."
  [host port]
  (let [k [host port]]
    (when-let [conn (get @connections k)]
      (disconnect! conn)
      (swap! connections dissoc k))))

(defn get-connection!
  "Get a cached connection or create a new one for host:port."
  [host port]
  (let [k [host port]]
    (if-let [conn (get @connections k)]
      (if (.isClosed ^Socket (:socket conn))
        (do (swap! connections dissoc k)
            (get-connection! host port))
        conn)
      (let [new-conn (connect! host port)]
        (swap! connections assoc k new-conn)
        new-conn))))

(def close-connection! evict-connection!)

(defn- send-msg!
  [{:keys [out]} msg]
  (b/write-bencode out msg)
  (.flush ^java.io.OutputStream out))

(defn- recv-until-done
  "Read bencode messages until status contains 'done' or timeout expires."
  [{:keys [socket in]} timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [msgs []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (neg? remaining)
          (conj msgs {"status" ["timeout"]})
          (let [_    (.setSoTimeout ^Socket socket (int (max 100 remaining)))
                read (try
                       {:msg (decode-msg (b/read-bencode in))}
                       (catch SocketTimeoutException _
                         {:timeout true}))]
            (if (:timeout read)
              (conj msgs {"status" ["timeout"]})
              (if-let [msg (:msg read)]
                (let [msgs   (conj msgs msg)
                      status (get msg "status")]
                  (if (and status (some #{"done"} status))
                    msgs
                    (recur msgs)))
                msgs))))))))

(defn nrepl-op!
  "Send an nREPL op and collect all responses until done."
  [conn op-map & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [id (next-id)
        msg (assoc op-map "id" id)]
    (send-msg! conn msg)
    (recv-until-done conn timeout-ms)))

(defn- aggregate-responses
  "Collapse multi-message nREPL responses into a single result map.
   Returns only the last value (like a REPL prompt)."
  [responses]
  (let [values (keep #(get % "value") responses)
        out-parts (keep #(get % "out") responses)
        err-parts (keep #(get % "err") responses)
        ex (some #(get % "ex") responses)
        root-ex (some #(get % "root-ex") responses)
        final-ns (some #(get % "ns") (reverse responses))
        all-status (into #{} (mapcat #(get % "status" []) responses))]
    {:value (last values)
     :out (str/join out-parts)
     :err (str/join err-parts)
     :ex ex
     :root-ex root-ex
     :ns final-ns
     :status all-status
     :timed-out? (contains? all-status "timeout")}))

(defn eval-code
  "Evaluate Clojure code on an nREPL server. On IOException (broken
   connection), evicts the connection and retries once."
  [{:keys [host port code ns session timeout-ms]
    :or {host "localhost" timeout-ms 30000}}]
  (letfn [(do-eval [retry?]
            (try
              (let [conn (get-connection! host port)
                    op (cond-> {"op" "eval" "code" code}
                         ns (assoc "ns" ns)
                         session (assoc "session" session))
                    responses (nrepl-op! conn op :timeout-ms timeout-ms)]
                (aggregate-responses responses))
              (catch IOException e
                (evict-connection! host port)
                (if retry?
                  (do-eval false)
                  (throw e)))))]
    (do-eval true)))

(defn close-all!
  []
  (doseq [[_ conn] @connections]
    (disconnect! conn))
  (reset! connections {}))
