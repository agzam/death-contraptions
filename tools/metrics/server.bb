#!/usr/bin/env bb
;; MCP server for Grafana metrics: query PromQL, search dashboards, read panels.
;; Borrows the session cookie from Brave on macOS (like the splunk tool) and
;; auto-opens the login when the session is stale.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[babashka.http-client :as http])

;;; ---------- Config ----------

(def ^:private script-dir
  (-> *file* io/file .getParentFile .getCanonicalPath))

(def ^:private config
  (let [f (io/file script-dir "config.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      {})))

(def grafana-host (or (:host config) ""))
(def ^:private base-url (str "https://" grafana-host))

(def ^:private base-domain
  "The registrable parent domain (last two labels) - the LIKE filter for the
  cookie lookup. Lets a parent-domain SSO cookie (.qlikcloud.io) be picked up
  alongside the host's own grafana_session."
  (->> (str/split grafana-host #"\.")
       (take-last 2)
       (str/join ".")))

(def ^:private auto-login? (get config :auto-login? true))
(def ^:private login-timeout-ms (get config :login-timeout-ms 30000))
(def ^:private browser-app (get config :browser-app "Brave Browser"))
(def ^:private poll-ms 2000)

(def ^:private brave-cookies-db
  (str (System/getProperty "user.home")
       "/Library/Application Support/BraveSoftware/Brave-Browser/Default/Cookies"))

;;; ---------- State ----------

(def ^:private creds (atom nil))           ;; {:cookie "name=val; ..."}
(def ^:private auth-ok-until (atom 0))      ;; epoch ms; skip re-probe until then
(def ^:private auth-ttl-ms 60000)
(def ^:private last-bootstrap (atom 0))     ;; throttle browser re-opens
(def ^:private last-refresh (atom 0))       ;; throttle 401 retries
(def ^:private refresh-interval-ms 60000)
(def ^:private datasources-cache (atom nil))

;;; ---------- Shell helpers ----------

(defn ^:private sh
  "Run command, return trimmed stdout on success, nil on failure."
  [& args]
  (let [{:keys [exit out]} (apply shell/sh args)]
    (when (zero? exit)
      (let [s (str/trim out)]
        (when-not (str/blank? s) s)))))

;;; ---------- Cookie decryption (Chromium v10 scheme) ----------

(defn ^:private get-keychain-password
  "Brave stores its cookie encryption key in the macOS Keychain under 'Brave Safe Storage'."
  []
  (or (sh "security" "find-generic-password" "-s" "Brave Safe Storage" "-w")
      (throw (ex-info "Could not retrieve Brave keychain password" {}))))

(defn ^:private derive-key
  "PBKDF2 key derivation: SHA1, 1003 iterations, 16-byte key."
  [password]
  (sh "bash" "-c"
      (str "openssl kdf -keylen 16"
           " -kdfopt digest:SHA1"
           " -kdfopt 'pass:" password "'"
           " -kdfopt hexsalt:73616c747973616c74"
           " -kdfopt iter:1003"
           " -binary PBKDF2 | xxd -p")))

(defn ^:private decrypt-cookie
  "Decrypt a Chromium cookie from Brave's SQLite DB.
  Copies DB to avoid lock contention with the browser."
  [cookie-name host-key aes-key-hex]
  (let [tmp-db  (doto (java.io.File/createTempFile "brave-" ".db") (.deleteOnExit))
        tmp-enc (doto (java.io.File/createTempFile "brave-" ".bin") (.deleteOnExit))
        tmp-dec (doto (java.io.File/createTempFile "brave-" ".bin") (.deleteOnExit))
        db-path  (.getAbsolutePath tmp-db)
        enc-path (.getAbsolutePath tmp-enc)
        dec-path (.getAbsolutePath tmp-dec)]
    (try
      (shell/sh "cp" brave-cookies-db db-path)
      (shell/sh "sqlite3" db-path
                (str "SELECT writefile('" enc-path
                     "', substr(encrypted_value, 4)) "
                     "FROM cookies WHERE name='" cookie-name
                     "' AND host_key='" host-key "' LIMIT 1;"))
      (when (and (.exists tmp-enc) (pos? (.length tmp-enc)))
        (shell/sh "openssl" "enc" "-aes-128-cbc" "-d"
                  "-K" aes-key-hex
                  "-iv" "20202020202020202020202020202020"
                  "-nopad"
                  "-in" enc-path "-out" dec-path)
        ;; Skip 32-byte domain hash prefix, strip PKCS7 padding
        (let [raw (sh "bash" "-c"
                      (str "dd if='" dec-path "' bs=1 skip=32 2>/dev/null"
                           " | perl -pe 's/[\\x01-\\x10]+$//'"))]
          (when (and raw (not (str/blank? raw)))
            raw)))
      (finally
        (.delete tmp-db)
        (.delete tmp-enc)
        (.delete tmp-dec)))))

;;; ---------- Cookie selection ----------

(defn ^:private list-cookie-rows
  "List candidate cookies for the host's parent domain: {:name :host-key :expires}.
  expires is Chromium epoch (microseconds since 1601)."
  []
  (let [tmp (doto (java.io.File/createTempFile "brave-" ".db") (.deleteOnExit))
        path (.getAbsolutePath tmp)]
    (try
      (shell/sh "cp" brave-cookies-db path)
      (when-let [out (sh "sqlite3" path
                         (str "SELECT name || '|' || host_key || '|' || expires_utc "
                              "FROM cookies WHERE host_key LIKE '%" base-domain "';"))]
        (->> (str/split-lines out)
             (remove str/blank?)
             (keep (fn [line]
                     (let [[nm hk exp] (str/split line #"\|")]
                       (when (and nm hk)
                         {:name nm :host-key hk :expires (or (parse-long (str exp)) 0)}))))))
      (finally (.delete tmp)))))

(defn ^:private host-matches?
  "Would a cookie with host-key be sent to host? Exact match, or domain-cookie
  (leading dot) suffix match."
  [host host-key]
  (if (str/starts-with? host-key ".")
    (or (= host (subs host-key 1))
        (str/ends-with? host host-key))
    (= host host-key)))

(defn ^:private chromium-now
  "Now in Chromium cookie epoch (microseconds since 1601-01-01 UTC)."
  []
  (* (+ (System/currentTimeMillis) 11644473600000) 1000))

(defn ^:private cookie-live?
  "A cookie is usable if it is a session cookie (expires 0) or not yet expired."
  [expires]
  (or (zero? expires)
      (< (chromium-now) expires)))

(defn ^:private build-cookie-header
  "Decrypt every live cookie whose domain serves the host and join them into a
  Cookie header value. nil when none are usable."
  [aes-key-hex]
  (let [pairs (->> (list-cookie-rows)
                   (filter (fn [{:keys [host-key expires]}]
                             (and (host-matches? grafana-host host-key)
                                  (cookie-live? expires))))
                   (keep (fn [{:keys [name host-key]}]
                           (when-let [v (decrypt-cookie name host-key aes-key-hex)]
                             (str name "=" v)))))]
    (when (seq pairs)
      (str/join "; " pairs))))

;;; ---------- Authentication + auto-bootstrap ----------

(defn ^:private refresh-cookies!
  "Re-read and cache the Cookie header from Brave."
  []
  (let [key-hex (derive-key (get-keychain-password))
        header  (when key-hex (build-cookie-header key-hex))]
    (reset! creds {:cookie header})
    header))

(defn ^:private probe-ok?
  "True when the cached cookie authenticates to Grafana (GET /api/user = 200)."
  []
  (boolean
   (when-let [h (:cookie @creds)]
     (try
       (= 200 (:status (http/get (str base-url "/api/user")
                                 {:headers {"Cookie" h "Accept" "application/json"}
                                  :timeout 15000
                                  :throw   false})))
       (catch Exception _ false)))))

(defn ^:private open-browser!
  "Bring up the Grafana login in the everyday browser so SSO can (re)establish the
  session - silent when the upstream IdP session is still valid."
  []
  (shell/sh "open" "-a" browser-app base-url))

(defn ^:private not-logged-in!
  [opened?]
  (throw (ex-info
          (str "Not authenticated to " grafana-host "."
               (if opened?
                 (str " A " browser-app " window was opened - finish login (SSO/MFA)"
                      " there, then retry.")
                 " Log in at the metrics site in your browser, then retry."))
          {:auth true})))

(defn ^:private bootstrap-login!
  "Open the browser once and poll cookies + /api/user until auth succeeds or the
  budget runs out. Throttled so back-to-back tool calls don't reopen the browser."
  []
  (when-not auto-login?
    (not-logged-in! false))
  (let [now (System/currentTimeMillis)]
    (when (< (- now @last-bootstrap) login-timeout-ms)
      (not-logged-in! true))
    (reset! last-bootstrap now))
  (open-browser!)
  (loop [waited 0]
    (Thread/sleep poll-ms)
    (refresh-cookies!)
    (cond
      (probe-ok?)                              true
      (< login-timeout-ms (+ waited poll-ms))  (not-logged-in! true)
      :else                                    (recur (+ waited poll-ms)))))

(defn ensure-authenticated!
  "Ensure we hold a cookie that authenticates to Grafana. Probes at most once per
  TTL; on a missing/expired session, auto-bootstraps a browser login. force?
  bypasses the TTL (used after a mid-flight 401)."
  ([] (ensure-authenticated! false))
  ([force?]
   (if (and (not force?)
            (:cookie @creds)
            (< (System/currentTimeMillis) @auth-ok-until))
     true
     (do
       (when (or force? (nil? (:cookie @creds)))
         (refresh-cookies!))
       (when-not (probe-ok?)
         (bootstrap-login!))
       (reset! auth-ok-until (+ (System/currentTimeMillis) auth-ttl-ms))
       true))))

;;; ---------- Grafana HTTP layer ----------

(defn ^:private auth-failure?
  [status]
  (contains? #{401 403} status))

(defn grafana-get
  "Authenticated GET against Grafana. Retries once on 401/403 after re-auth
  (throttled to avoid hammering keychain decryption)."
  [path & {:keys [params] :or {params {}}}]
  (ensure-authenticated!)
  (let [do-req (fn []
                 (let [resp (http/get (str base-url path)
                                      {:headers      {"Cookie" (:cookie @creds)
                                                      "Accept" "application/json"}
                                       :query-params params
                                       :timeout      45000
                                       :throw        false})]
                   {:status (:status resp)
                    :body   (try (json/parse-string (:body resp) true)
                                 (catch Exception _ (:body resp)))}))
        {:keys [status] :as resp} (do-req)]
    (if (and (auth-failure? status)
             (< refresh-interval-ms (- (System/currentTimeMillis) @last-refresh)))
      (do (reset! last-refresh (System/currentTimeMillis))
          (ensure-authenticated! true)
          (do-req))
      resp)))

;;; ---------- Grafana API helpers ----------

;; parse-time is defined in the Time parsing section below; forward-declared so
;; the query builder compiles.
(declare parse-time)

(defn ^:private get-datasources
  "Fetch and cache the datasource list."
  []
  (or @datasources-cache
      (let [{:keys [status body]} (grafana-get "/api/datasources")]
        (when (and (= 200 status) (sequential? body))
          (reset! datasources-cache body))
        body)))

(defn ^:private resolve-ds-uid
  "Resolve a datasource arg (uid or name) to a uid. Blank picks the default, then
  the first prometheus datasource, then the first of any."
  [ds]
  (let [dss (get-datasources)]
    (when (sequential? dss)
      (if (str/blank? (str ds))
        (:uid (or (first (filter :isDefault dss))
                  (first (filter #(= "prometheus" (:type %)) dss))
                  (first dss)))
        (:uid (or (first (filter #(= ds (:uid %)) dss))
                  (first (filter #(= ds (:name %)) dss))))))))

(defn ^:private proxy-path
  [uid suffix]
  (str "/api/datasources/proxy/uid/" uid suffix))

(defn ^:private promql
  "Range or instant PromQL query via the datasource proxy (native Prometheus API)."
  [uid {:keys [query start end step instant]}]
  (if instant
    (grafana-get (proxy-path uid "/api/v1/query")
                 :params {"query" query
                          "time"  (parse-time (or end "now"))})
    (grafana-get (proxy-path uid "/api/v1/query_range")
                 :params {"query" query
                          "start" (parse-time (or start "-1h"))
                          "end"   (parse-time (or end "now"))
                          "step"  (str (or step "60"))})))

;;; ---------- Time parsing ----------

(def ^:private unit-seconds {"s" 1 "m" 60 "h" 3600 "d" 86400 "w" 604800})

(defn parse-time
  "Convert a time spec to Unix epoch seconds (string) for the Prometheus API.
  Accepts 'now', relative offsets like '-15m'/'-2h'/'-7d', or passes through an
  already-absolute value (epoch seconds or RFC3339)."
  [t]
  (let [t (str/trim (str t))]
    (cond
      (or (str/blank? t) (= "now" t))
      (str (quot (System/currentTimeMillis) 1000))

      (re-find #"^-\d+[smhdw]$" t)
      (let [n   (parse-long (re-find #"\d+" t))
            u   (subs t (dec (count t)))
            now (quot (System/currentTimeMillis) 1000)]
        (str (- now (* n (unit-seconds u)))))

      :else t)))

;;; ---------- Result formatting ----------

(defn fmt-metric
  "Render a Prometheus metric label set as name{k=\"v\", ...}."
  [m]
  (let [nm     (:__name__ m)
        labels (dissoc m :__name__)
        ls     (->> labels
                    (map (fn [[k v]] (str (name k) "=\"" v "\"")))
                    (str/join ", "))]
    (str (or nm "")
         (when (seq labels) (str "{" ls "}")))))

(defn format-prom
  "Render a Prometheus query response. Instant -> 'metric => value'; range ->
  'metric  (N pts, last=value)'."
  [body max-n]
  (let [{:keys [status data error]} body]
    (cond
      (not= "success" status)
      (str "Query error: " (or error (pr-str body)))

      (empty? (:result data))
      "No data."

      :else
      (let [rt    (:resultType data)
            res   (:result data)
            total (count res)
            shown (take max-n res)]
        (str (when (< max-n total) (str "Showing " max-n " of " total " series\n\n"))
             (str/join
              "\n"
              (map (fn [s]
                     (if (= "matrix" rt)
                       (let [vs      (:values s)
                             [_ lv]  (last vs)]
                         (str (fmt-metric (:metric s))
                              "  (" (count vs) " pts, last=" lv ")"))
                       (let [[_ v] (:value s)]
                         (str (fmt-metric (:metric s)) " => " v))))
                   shown)))))))

(defn format-datasources
  [dss]
  (if-not (sequential? dss)
    "No datasources."
    (str "Datasources (" (count dss) "):\n\n"
         (str/join
          "\n"
          (map (fn [d]
                 (str "  " (:name d)
                      " [" (:type d) "]"
                      " uid=" (:uid d)
                      (when (:isDefault d) " (default)")))
               dss)))))

(defn format-dashboards
  [items]
  (if (empty? items)
    "No dashboards found."
    (str "Dashboards (" (count items) "):\n\n"
         (str/join
          "\n"
          (map (fn [d]
                 (str "  " (:title d)
                      "  uid=" (:uid d)
                      (when-let [f (:folderTitle d)] (str "  folder=" f))
                      (when-let [u (:url d)] (str "\n    " u))))
               items)))))

(defn ^:private panel-queries
  [panel]
  (keep (fn [t]
          (let [e (:expr t)]
            (when-not (str/blank? (str e)) e)))
        (:targets panel)))

(defn format-dashboard-detail
  "Show a dashboard's title, variables, and each panel's PromQL targets - so the
  exact queries a dashboard runs can be read and reused."
  [body]
  (let [dash   (:dashboard body)
        title  (:title dash)
        url    (get-in body [:meta :url])
        vars   (->> (get-in dash [:templating :list])
                    (keep :name))
        panels (->> (:panels dash)
                    (mapcat (fn [p] (cons p (:panels p)))) ;; flatten rows' nested panels
                    (remove #(= "row" (:type %)))
                    (filter :type))]
    (str "Dashboard: " title
         (when url (str "\n  " url))
         (when (seq vars) (str "\n  variables: " (str/join ", " vars)))
         "\n\nPanels (" (count panels) "):\n"
         (str/join
          "\n"
          (map (fn [p]
                 (let [qs (panel-queries p)]
                   (str "  - " (or (:title p) "(untitled)") " [" (:type p) "]"
                        (when (seq qs)
                          (str "\n" (str/join "\n" (map #(str "      " %) qs)))))))
               panels)))))

(defn format-metric-names
  [names]
  (let [total (count names)
        cap   200
        shown (take cap (sort names))]
    (if (zero? total)
      "No metric names found."
      (str (when (< cap total) (str "Showing " cap " of " total " metric names\n\n"))
           (str/join "\n" shown)))))

;;; ---------- Tool: metrics-query ----------

(defn do-query
  [{:strs [query datasource start end step instant max_results]}]
  (try
    (ensure-authenticated!)
    (let [uid (resolve-ds-uid datasource)]
      (if-not uid
        {:content [{:type "text"
                    :text (str "No matching datasource"
                               (when-not (str/blank? (str datasource))
                                 (str " for '" datasource "'"))
                               ". Use metrics-list-datasources to see options.")}]
         :isError true}
        (let [max-n (min (or (some-> max_results parse-long) 50) 1000)
              {:keys [status body]} (promql uid {:query   query
                                                 :start   start
                                                 :end     end
                                                 :step    step
                                                 :instant (= "true" (str instant))})]
          (if (= 200 status)
            {:content [{:type "text" :text (format-prom body max-n)}]}
            {:content [{:type "text"
                        :text (str "Grafana error (HTTP " status "): " (pr-str body))}]
             :isError true}))))
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- Tool: metrics-list-datasources ----------

(defn do-datasources
  [_]
  (try
    (ensure-authenticated!)
    (let [{:keys [status body]} (grafana-get "/api/datasources")]
      (if (= 200 status)
        {:content [{:type "text" :text (format-datasources body)}]}
        {:content [{:type "text"
                    :text (str "Grafana error (HTTP " status "): " (pr-str body))}]
         :isError true}))
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- Tool: metrics-search-dashboards ----------

(defn do-search-dashboards
  [{:strs [query limit]}]
  (try
    (ensure-authenticated!)
    (let [n (min (or (some-> limit parse-long) 30) 100)
          {:keys [status body]}
          (grafana-get "/api/search"
                       :params (cond-> {"type"  "dash-db"
                                        "limit" (str n)}
                                 (not (str/blank? (str query))) (assoc "query" query)))]
      (if (= 200 status)
        {:content [{:type "text" :text (format-dashboards body)}]}
        {:content [{:type "text"
                    :text (str "Grafana error (HTTP " status "): " (pr-str body))}]
         :isError true}))
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- Tool: metrics-get-dashboard ----------

(defn do-get-dashboard
  [{:strs [uid]}]
  (try
    (ensure-authenticated!)
    (if (str/blank? (str uid))
      {:content [{:type "text" :text "uid is required."}] :isError true}
      (let [{:keys [status body]} (grafana-get (str "/api/dashboards/uid/" uid))]
        (if (= 200 status)
          {:content [{:type "text" :text (format-dashboard-detail body)}]}
          {:content [{:type "text"
                      :text (str "Grafana error (HTTP " status "): " (pr-str body))}]
           :isError true})))
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- Tool: metrics-metric-names ----------

(defn do-metric-names
  [{:strs [datasource] :as args}]
  (try
    (ensure-authenticated!)
    (let [uid (resolve-ds-uid datasource)
          flt (get args "filter")]
      (if-not uid
        {:content [{:type "text"
                    :text "No matching datasource. Use metrics-list-datasources."}]
         :isError true}
        (let [{:keys [status body]} (grafana-get (proxy-path uid "/api/v1/label/__name__/values"))]
          (if (and (= 200 status) (= "success" (:status body)))
            (let [names    (:data body)
                  filtered (if (str/blank? (str flt))
                             names
                             (filter #(str/includes? (str/lower-case %)
                                                     (str/lower-case flt))
                                     names))]
              {:content [{:type "text" :text (format-metric-names filtered)}]})
            {:content [{:type "text"
                        :text (str "Grafana error (HTTP " status "): " (pr-str body))}]
             :isError true}))))
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- MCP Server ----------

(def server-info {:name "metrics" :version "1.0.0"})

(def tools
  [{:name        "metrics-query"
    :description "Run a PromQL query against a Grafana datasource (range by default, or instant)."
    :inputSchema
    {:type       "object"
     :properties {:query       {:type        "string"
                                :description "PromQL expression (e.g. 'rate(http_requests_total[5m])')"}
                  :datasource  {:type        "string"
                                :description "Datasource uid or name. Default: the default/first Prometheus datasource."}
                  :start       {:type        "string"
                                :description "Range start (default '-1h'). '-15m', '-6h', epoch seconds, or RFC3339."}
                  :end         {:type        "string"
                                :description "Range end (default 'now')."}
                  :step        {:type        "string"
                                :description "Range step in seconds or a duration like '30s' (default '60')."}
                  :instant     {:type        "string"
                                :description "Set 'true' for an instant query at 'end' instead of a range."}
                  :max_results {:type        "string"
                                :description "Max series to return (default '50', max '1000')."}}
     :required   ["query"]}}

   {:name        "metrics-list-datasources"
    :description "List Grafana datasources (uid, name, type) so queries can target the right backend."
    :inputSchema {:type "object" :properties {}}}

   {:name        "metrics-search-dashboards"
    :description "Search dashboards by text - the fast path from a ticket symptom to an existing dashboard."
    :inputSchema
    {:type       "object"
     :properties {:query {:type        "string"
                          :description "Search terms (service, symptom, team). Omit to list dashboards."}
                  :limit {:type        "string"
                          :description "Max dashboards to return (default '30', max '100')."}}}}

   {:name        "metrics-get-dashboard"
    :description "Show a dashboard's panels and their PromQL targets by uid, so the exact queries can be reused."
    :inputSchema
    {:type       "object"
     :properties {:uid {:type        "string"
                        :description "Dashboard uid (from metrics-search-dashboards)."}}
     :required   ["uid"]}}

   {:name        "metrics-metric-names"
    :description "List or substring-search metric names available in a datasource."
    :inputSchema
    {:type       "object"
     :properties {:datasource {:type        "string"
                               :description "Datasource uid or name. Default: the default/first Prometheus datasource."}
                  :filter     {:type        "string"
                               :description "Case-insensitive substring to filter metric names."}}}}])

(defn handle-request
  "Dispatch MCP JSON-RPC requests - routes initialize, tools/list, and tools/call
  to their handlers, returning nil for notifications and unknown methods."
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
                 "metrics-query"             (do-query args)
                 "metrics-list-datasources"  (do-datasources args)
                 "metrics-search-dashboards" (do-search-dashboards args)
                 "metrics-get-dashboard"     (do-get-dashboard args)
                 "metrics-metric-names"      (do-metric-names args)
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
