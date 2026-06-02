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
(def ^:private heartbeat-interval-ms 30000)
(def ^:private heartbeat-started? (atom false))

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

(def close-connection! evict-connection!)

;; --- message sending/receiving (before get-connection! for heartbeat) ---

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

;; --- heartbeat (depends on nrepl-op!, must precede get-connection!) ---

(defn- ping-connection!
  "Send a describe op to verify the connection is alive.
   Returns true if healthy, false if dead (and evicts it)."
  [[k conn]]
  (try
    (nrepl-op! conn {"op" "describe"} :timeout-ms 3000)
    true
    (catch Exception _
      (disconnect! conn)
      (swap! connections dissoc k)
      false)))

(defn- start-heartbeat! []
  (when (compare-and-set! heartbeat-started? false true)
    (future
      (loop []
        (Thread/sleep heartbeat-interval-ms)
        (doseq [entry @connections]
          (ping-connection! entry))
        (recur)))))

;; --- connection cache ---

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
        (start-heartbeat!)
        new-conn))))

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

;; --- cljs/nbb await -------------------------------------------------------
;; nbb's nREPL returns the promise object, not its resolved value, and its
;; *1/*2/*3/*e history vars are GLOBAL (shared across sessions, not per-session
;; - verified). For real-REPL fidelity we:
;;   1. kick off a wrapper that runs CODE inside a promise chain, capturing the
;;      resolved value in *nre-val* and the REAL error object (a synchronous
;;      throw OR an async rejection alike) in *nre-err*;
;;   2. poll one form that, once settled, hands back the value (so it lands in
;;      *1) or RE-THROWS the real error (so nREPL binds *e and the MCP renders a
;;      genuine error - not an "ERR ..." string).
;; Running CODE inside (p/then (fn [_] CODE)) funnels sync throws and async
;; rejections through the same p/catch. promesa is built into nbb; require it
;; as `p` first so its macros are available when the kickoff is analyzed.

(def ^:private await-require "(require (quote [promesa.core :as p]))")
(def ^:private await-pre
  (str "(do (def *nre-pending* true) (def *nre-err* nil) (def *nre-val* nil)"
       " (-> (p/resolved nil) (p/then (fn [_nre] "))
(def ^:private await-post
  (str ")) (p/then (fn [x] (def *nre-val* x) (def *nre-pending* false)))"
       " (p/catch (fn [e] (def *nre-err* e) (def *nre-pending* false))))"
       " :nre/kicked)"))

(defn await-kickoff-code
  "Wrap CODE so its eventual value lands in *nre-val* and any failure (sync
   throw or async rejection) lands in *nre-err* as the REAL error object.
   Returns :nre/kicked immediately; the promise settles on a later tick. Pure."
  [code]
  (str await-pre code await-post))

;; One poll form, double duty: still pending -> a sentinel keyword; settled ->
;; hand back the value (which becomes *1) or throw the failure (which binds *e
;; and surfaces as a genuine nREPL error -> the MCP marks isError).
;; We throw a fresh js/Error carrying the captured MESSAGE rather than the raw
;; rejection object: throwing some host error classes (e.g. Playwright's) trips
;; nbb's "nth not supported" printer quirk and loses the real message. The
;; original object stays in *nre-err* for inspection.
(def ^:private await-poll-form
  (str "(if *nre-pending* :nre/await-pending"
       " (if (some? *nre-err*)"
       " (throw (js/Error. (or (.-message *nre-err*) (str *nre-err*))))"
       " *nre-val*))"))
(def ^:private await-pending-value ":nre/await-pending")

(defn eval-code-await
  "Evaluate CODE on a cljs/nbb nREPL and resolve its promise before returning,
   with real-REPL fidelity: the resolved value becomes *1, while a failure
   re-throws the real error so *e is bound and the result is a genuine error
   (not an \"ERR \" string). Returns the same result shape as eval-code."
  [{:keys [host port code session ns timeout-ms]
    :or {host "localhost" timeout-ms 30000}}]
  (let [ev (fn [c tmo] (eval-code {:host host :port port :code c :session session :ns ns :timeout-ms tmo}))]
    (ev await-require 5000)
    (let [kicked (ev (await-kickoff-code code) 10000)]
      (if (some? (:ex kicked))
        kicked                                  ;; CODE failed to compile/eval up front
        (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
          (loop []
            (let [r (ev await-poll-form 5000)]
              (cond
                (some? (:ex r))                       r   ;; settled with a real error
                (not= await-pending-value (:value r)) r   ;; settled with a value
                (< deadline (System/currentTimeMillis))
                {:value "nil" :err "await timeout" :timed-out? true}
                :else (do (Thread/sleep 150) (recur))))))))))

(defn close-all!
  []
  (reset! heartbeat-started? false)
  (doseq [[_ conn] @connections]
    (disconnect! conn))
  (reset! connections {}))
