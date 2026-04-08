#!/usr/bin/env bb
;; MCP server for Splunk: search logs, list indexes, explore metadata.
;; Borrows session credentials from Brave browser on macOS.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[babashka.http-client :as http])

;;; ---------- Config ----------

(def splunk-host (or (System/getenv "SPLUNK_HOST") ""))

(def brave-cookies-db
  (str (System/getProperty "user.home")
       "/Library/Application Support/BraveSoftware/Brave-Browser/Default/Cookies"))

;;; ---------- State ----------

(def credentials (atom nil))  ;; {:session "..." :csrf "..." :port "8443"}
(def last-refresh (atom 0))
(def refresh-interval-ms 60000)

;;; ---------- Shell helpers ----------

(defn sh
  "Run command, return trimmed stdout on success, nil on failure."
  [& args]
  (let [{:keys [exit out]} (apply shell/sh args)]
    (when (zero? exit)
      (let [s (str/trim out)]
        (when-not (str/blank? s) s)))))

;;; ---------- Cookie decryption (Chromium v10 scheme) ----------

(defn get-keychain-password []
  (or (sh "security" "find-generic-password" "-s" "Brave Safe Storage" "-w")
      (throw (ex-info "Could not retrieve Brave keychain password" {}))))

(defn derive-key
  "PBKDF2 key derivation: SHA1, 1003 iterations, 16-byte key."
  [password]
  (sh "bash" "-c"
      (str "openssl kdf -keylen 16"
           " -kdfopt digest:SHA1"
           " -kdfopt 'pass:" password "'"
           " -kdfopt hexsalt:73616c747973616c74"
           " -kdfopt iter:1003"
           " -binary PBKDF2 | xxd -p")))

(defn decrypt-cookie
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

(defn discover-splunk-port
  "Find the Splunk port from splunkd_<port> cookie name."
  []
  (let [tmp (doto (java.io.File/createTempFile "brave-" ".db") (.deleteOnExit))
        path (.getAbsolutePath tmp)]
    (try
      (shell/sh "cp" brave-cookies-db path)
      (when-let [name (sh "sqlite3" path
                          (str "SELECT name FROM cookies "
                               "WHERE name LIKE 'splunkd_%' "
                               "AND host_key='" splunk-host "' LIMIT 1;"))]
        (second (re-find #"splunkd_(\d+)" name)))
      (finally (.delete tmp)))))

;;; ---------- Credential management ----------

(defn ensure-credentials!
  ([] (ensure-credentials! false))
  ([force?]
   (when (or force? (nil? @credentials))
     (let [port    (or (discover-splunk-port)
                       (throw (ex-info (str "No Splunk cookies in Brave for " splunk-host) {})))
           key-hex (or (derive-key (get-keychain-password))
                       (throw (ex-info "PBKDF2 key derivation failed" {})))
           session (or (decrypt-cookie (str "splunkd_" port) splunk-host key-hex)
                       (throw (ex-info "Could not decrypt splunkd session cookie" {})))
           csrf    (or (decrypt-cookie (str "splunkweb_csrf_token_" port) splunk-host key-hex)
                       (throw (ex-info "Could not decrypt CSRF token" {})))]
       (reset! credentials {:session session :csrf csrf :port port})
       (reset! last-refresh (System/currentTimeMillis))))))

;;; ---------- Splunk API layer ----------

(defn- cookie-header [{:keys [session csrf port]}]
  (str "splunkd_" port "=" session
       "; splunkweb_csrf_token_" port "=" csrf))

(defn- base-url [path]
  (str "https://" splunk-host "/en-US/splunkd/__raw" path))

(defn- session-expired? [status body]
  (or (= 401 status)
      (= 403 status)
      (and (string? body)
           (re-find #"(?i)unauthorized|session|login" body))))

(defn- should-retry? [status body]
  (and (session-expired? status body)
       (< refresh-interval-ms
          (- (System/currentTimeMillis) @last-refresh))))

(defn splunk-get
  "Authenticated GET to Splunk REST API via web proxy.
  Auto-retries once on session expiry."
  [path & {:keys [params] :or {params {}}}]
  (let [do-req (fn []
                 (let [creds @credentials
                       resp  (http/get (base-url path)
                                       {:headers      {"Cookie" (cookie-header creds)
                                                       "Accept" "application/json"}
                                        :query-params (merge {"output_mode" "json"} params)
                                        :timeout      45000
                                        :throw        false})]
                   {:status (:status resp)
                    :body   (try (json/parse-string (:body resp) true)
                                 (catch Exception _ (:body resp)))}))
        {:keys [status body] :as resp} (do-req)]
    (if (should-retry? status body)
      (do (ensure-credentials! true) (do-req))
      resp)))

(defn splunk-post
  "Authenticated POST to Splunk REST API. Auto-retries once on session expiry."
  [path form-params]
  (let [do-req (fn []
                 (let [creds @credentials
                       resp  (http/post
                              (base-url path)
                              {:headers     {"Cookie"            (cookie-header creds)
                                             "X-Splunk-Form-Key" (:csrf creds)
                                             "X-Requested-With"  "XMLHttpRequest"
                                             "Content-Type"      "application/x-www-form-urlencoded"
                                             "Accept"            "application/json"}
                               :form-params (merge {"output_mode" "json"} form-params)
                               :timeout     30000
                               :throw       false})]
                   {:status (:status resp)
                    :body   (try (json/parse-string (:body resp) true)
                                 (catch Exception _ (:body resp)))}))
        {:keys [status body] :as resp} (do-req)]
    (if (should-retry? status body)
      (do (ensure-credentials! true) (do-req))
      resp)))

(defn splunk-search-async
  "Run SPL search via async job API: create job, poll until done, fetch results."
  [spl & {:keys [params max-results] :or {params {} max-results 100}}]
  (let [;; 1. Create search job
        create-params (merge {"search" spl
                              "exec_mode" "normal"
                              "max_count" (str max-results)}
                             (dissoc params "count"))
        {:keys [status body]} (splunk-post "/services/search/jobs" create-params)]
    (if-not (= 201 status)
      {:error (str "Failed to create search job (HTTP " status "): " (pr-str body))}
      (let [sid (get-in body [:sid])]
        ;; 2. Poll for completion (up to 120s)
        (loop [elapsed 0]
          (Thread/sleep 2000)
          (let [{:keys [body]} (splunk-get (str "/services/search/jobs/" sid))
                state (get-in body [:entry 0 :content :dispatchState])
                done? (= "DONE" state)
                failed? (= "FAILED" state)]
            (cond
              done?
              ;; 3. Fetch results
              (let [{res-status :status res-body :body}
                    (splunk-get (str "/services/search/jobs/" sid "/results")
                                :params {"count" (str max-results)
                                         "output_mode" "json"})]
                (if (= 200 res-status)
                  {:results (get-in res-body [:results])}
                  {:error (str "Failed to fetch results (HTTP " res-status ")")}))

              failed?
              {:error (str "Search job failed: " (get-in body [:entry 0 :content :messages]))}

              (< 120000 (+ elapsed 2000))
              {:error "Search timed out after 120s"}

              :else
              (recur (+ elapsed 2000)))))))))

(defn splunk-search-export
  "Run SPL search via export endpoint (POST). Returns raw NDJSON body.
  Auto-retries once on session expiry."
  [spl & {:keys [params] :or {params {}}}]
  (let [do-req (fn []
                 (let [creds @credentials
                       resp  (http/post
                              (base-url "/services/search/jobs/export")
                              {:headers     {"Cookie"            (cookie-header creds)
                                             "X-Splunk-Form-Key" (:csrf creds)
                                             "X-Requested-With"  "XMLHttpRequest"
                                             "Content-Type"      "application/x-www-form-urlencoded"
                                             "Accept"            "application/json"}
                               :form-params (merge {"search"      spl
                                                    "output_mode" "json"}
                                                   params)
                               :timeout      45000
                               :throw       false})]
                   {:status (:status resp) :body (:body resp)}))
        {:keys [status body] :as resp} (do-req)]
    (if (should-retry? status body)
      (do (ensure-credentials! true) (do-req))
      resp)))

;;; ---------- Result formatting ----------

(defn parse-ndjson
  "Parse newline-delimited JSON from search/jobs/export."
  [body]
  (when (and body (string? body))
    (->> (str/split-lines body)
         (remove str/blank?)
         (keep #(try (json/parse-string % true)
                     (catch Exception _ nil))))))

(defn format-search-results [results max-n]
  (let [data       (keep :result results)
        total      (count data)
        truncated? (< max-n total)
        shown      (take max-n data)]
    (if (empty? shown)
      "No results found."
      (let [fields (->> shown
                        (mapcat keys)
                        (distinct)
                        ;; _time and _raw first, then alphabetical
                        ((fn [ks]
                           (let [pri (filter #{:_time :_raw} ks)
                                 rst (sort-by name (remove #{:_time :_raw} ks))]
                             (concat pri rst)))))]
        (str (when truncated?
               (str "Showing " max-n " of " total " results\n\n"))
             (str/join
              "\n\n"
              (map-indexed
               (fn [i row]
                 (str "--- Result " (inc i) " ---\n"
                      (str/join
                       "\n"
                       (keep (fn [f]
                               (when-let [v (get row f)]
                                 (str (name f) ": " v)))
                             fields))))
               shown)))))))

;;; ---------- Tool: splunk-search ----------

(defn format-async-results
  "Format results from the async job API."
  [results max-n]
  (let [total      (count results)
        truncated? (< max-n total)
        shown      (take max-n results)]
    (if (empty? shown)
      "No results found."
      (let [fields (->> shown
                        (mapcat keys)
                        (distinct)
                        ((fn [ks]
                           (let [pri (filter #{:_time :_raw} ks)
                                 rst (sort-by name (remove #{:_time :_raw} ks))]
                             (concat pri rst)))))]
        (str (when truncated?
               (str "Showing " max-n " of " total " results\n\n"))
             (str/join
              "\n\n"
              (map-indexed
               (fn [i row]
                 (str "--- Result " (inc i) " ---\n"
                      (str/join
                       "\n"
                       (keep (fn [f]
                               (when-let [v (get row f)]
                                 (str (name f) ": " v)))
                             fields))))
               shown)))))))

(defn do-search [{:strs [query earliest_time latest_time max_results]}]
  (try
    (ensure-credentials!)
    (let [max-n   (min (or (some-> max_results parse-long) 100) 10000)
          spl     (if (str/starts-with? (str/trim query) "|")
                    query
                    (str "search " query))
          params  (cond-> {}
                    earliest_time       (assoc "earliest_time" earliest_time)
                    latest_time         (assoc "latest_time" latest_time)
                    (nil? earliest_time) (assoc "earliest_time" "-24h")
                    (nil? latest_time)   (assoc "latest_time" "now"))
          {:keys [results error]} (splunk-search-async spl
                                                       :params params
                                                       :max-results max-n)]
      (if error
        {:content [{:type "text" :text (str "Splunk error: " error)}]
         :isError true}
        {:content [{:type "text"
                    :text (format-async-results results max-n)}]}))
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- Tool: splunk-indexes ----------

(defn do-indexes [{:strs [include_internal]}]
  (try
    (ensure-credentials!)
    (let [{:keys [status body]} (splunk-get "/services/data/indexes"
                                            :params {"count" "0"})
          entries (:entry body)]
      (if (= 200 status)
        (let [indexes (->> entries
                           (map (fn [e]
                                  {:name  (:name e)
                                   :events (get-in e [:content :totalEventCount])
                                   :size   (get-in e [:content :currentDBSizeMB])}))
                           (remove (fn [{:keys [name]}]
                                     (and (not= "true" include_internal)
                                          (str/starts-with? name "_"))))
                           (sort-by :name))]
          {:content [{:type "text"
                      :text (if (empty? indexes)
                              "No indexes found."
                              (str "Indexes (" (count indexes) "):\n\n"
                                   (str/join
                                    "\n"
                                    (map (fn [{:keys [name events size]}]
                                           (str "  " name
                                                " - events: " (or events "?")
                                                ", size: " (or size "?") " MB"))
                                         indexes))))}]})
        {:content [{:type "text"
                    :text (str "Splunk error (HTTP " status "): " (pr-str body))}]
         :isError true}))
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- Tool: splunk-search-metadata ----------

(defn do-metadata [{:strs [index metadata_type]}]
  (try
    (ensure-credentials!)
    (let [types   (if metadata_type
                    [(str/lower-case metadata_type)]
                    ["hosts" "sources" "sourcetypes"])
          results (for [t types
                        :let [spl (str "| metadata type=" t " index=" index
                                       " | sort -totalCount | head 50")
                              {:keys [results error]}
                              (splunk-search-async
                               spl :params {"earliest_time" "-7d"
                                            "latest_time"   "now"}
                               :max-results 50)]]
                    {:type    t
                     :entries (if error [] results)
                     :error   error})
          text    (str/join
                   "\n\n"
                   (for [{:keys [type entries error]} results]
                     (str "== " (str/upper-case type)
                          " (index=" index ") ==\n"
                          (cond
                            error
                            (str "  (error: " error ")")

                            (empty? entries)
                            "  (none)"

                            :else
                            (str/join
                             "\n"
                             (map (fn [e]
                                    (let [;; metadata returns singular field names
                                          field (keyword (str/replace type #"s$" ""))
                                          v (or (get e field) "?")]
                                      (str "  " v
                                           " - count: " (or (:totalCount e) "?")
                                           ", first: " (or (:firstTime e) "?")
                                           ", last: " (or (:recentTime e) "?"))))
                                  entries))))))]
      {:content [{:type "text" :text text}]})
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- MCP Server ----------

(def server-info {:name "splunk" :version "1.0.0"})

(def tools
  [{:name        "splunk-search"
    :description "Run an SPL search query against Splunk and return results. Prepends 'search' keyword automatically for non-piped queries. Use Splunk Search Processing Language (SPL)."
    :inputSchema
    {:type       "object"
     :properties {:query         {:type        "string"
                                  :description "SPL query string (e.g. 'index=main error' or '| makeresults | eval x=1')"}
                  :earliest_time {:type        "string"
                                  :description "Start of time range (default: '-24h'). Splunk time modifiers: -1h, -7d, -30m, 2024-01-01T00:00:00, epoch"}
                  :latest_time   {:type        "string"
                                  :description "End of time range (default: 'now')"}
                  :max_results   {:type        "string"
                                  :description "Max results to return (default: '100', max: '10000')"}}
     :required   ["query"]}}

   {:name        "splunk-indexes"
    :description "List available Splunk indexes with event counts and sizes. Useful for discovering what data exists before writing searches."
    :inputSchema
    {:type       "object"
     :properties {:include_internal {:type        "string"
                                     :description "Set to 'true' to include internal indexes (prefixed with _). Default: false."}}}}

   {:name        "splunk-search-metadata"
    :description "Discover hosts, sources, and sourcetypes for a Splunk index. Returns top 50 values by count. Useful for understanding what data an index contains."
    :inputSchema
    {:type       "object"
     :properties {:index         {:type        "string"
                                  :description "Splunk index name to inspect"}
                  :metadata_type {:type        "string"
                                  :description "Specific type: 'hosts', 'sources', or 'sourcetypes'. Omit for all three."}}
     :required   ["index"]}}])

(defn handle-request [{:strs [id method params]}]
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
                 "splunk-search"          (do-search args)
                 "splunk-indexes"         (do-indexes args)
                 "splunk-search-metadata" (do-metadata args)
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
