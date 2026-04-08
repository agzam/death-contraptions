#!/usr/bin/env bb
;; MCP server for Slack: search messages and fetch threads.
;; Extracts credentials from the Slack desktop app on macOS.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[babashka.http-client :as http])

;;; ---------- State ----------

(def credentials (atom {}))   ;; {"host" -> {:token "xoxc-..." :cookie "xoxd-..."}}
(def default-host (atom nil))
(def user-cache (atom {}))    ;; {"host:uid" -> "display name"}
(def channel-cache (atom {})) ;; {"host:cid" -> "channel name"}
(def last-refresh (atom 0))   ;; epoch millis of last credential extraction
(def refresh-interval-ms 60000) ;; minimum ms between re-extractions

(def slack-data-dir
  (or (System/getenv "SLACK_DATA_DIR")
      (str (System/getProperty "user.home")
           "/Library/Application Support/Slack/")))

;;; ---------- Shell helpers ----------

(defn sh
  "Run command, return trimmed stdout on success, nil on failure."
  [& args]
  (let [{:keys [exit out]} (apply shell/sh args)]
    (when (zero? exit)
      (let [s (str/trim out)]
        (when-not (str/blank? s) s)))))

;;; ---------- Workspace discovery ----------

(def ^:private generic-slack-hosts
  "Slack subdomains that are not real workspaces."
  #{"app" "files" "s" "pp" "api" "edgeapi" "slack-edge"})

(defn- discover-workspaces-from-root-state
  "Read workspace domains from Slack's storage/root-state.json.
  Returns a seq of hostnames like (\"foo.slack.com\" \"bar.slack.com\"), or nil."
  []
  (try
    (let [state-file (str slack-data-dir "storage/root-state.json")
          content    (slurp state-file)
          state      (json/parse-string content)
          workspaces (get state "workspaces")]
      (when (map? workspaces)
        (->> (vals workspaces)
             (keep (fn [ws]
                     (when-let [domain (get ws "domain")]
                       (str domain ".slack.com"))))
             (vec)
             (not-empty))))
    (catch Exception _ nil)))

(defn- discover-workspaces-from-indexeddb
  "Find workspace hostnames from Slack's IndexedDB files.
  Returns a seq of hostnames like (\"foo.slack.com\" \"bar.slack.com\"), or nil."
  []
  (let [idb-dir (str slack-data-dir "IndexedDB/")]
    (when-let [out (sh "rg" "-aoN" "--no-filename"
                       "[a-z0-9-]+\\.slack\\.com"
                       idb-dir)]
      (->> (str/split-lines out)
           (distinct)
           (remove (fn [host]
                     (let [sub (first (str/split host #"\."))]
                       (contains? generic-slack-hosts sub))))
           (vec)
           (not-empty)))))

(defn discover-workspaces
  "Discover workspace hostnames. Tries root-state.json first, falls back to IndexedDB scan."
  []
  (or (discover-workspaces-from-root-state)
      (discover-workspaces-from-indexeddb)))

(defn extract-token-from-html
  "Fetch a workspace's homepage with the d cookie and extract api_token.
  Returns the xoxc token string or nil."
  [host cookie]
  (try
    (let [resp (http/get (str "https://" host "/")
                         {:headers {"Cookie" (str "d=" cookie ";")}
                          :follow-redirects true
                          :throw false})
          body (:body resp)]
      (when body
        (second (re-find #"\"api_token\":\"(xoxc-[^\"]+)\"" body))))
    (catch Exception _ nil)))

;;; ---------- Cookie decryption ----------

(defn get-keychain-password
  "Get the Slack Safe Storage password from macOS Keychain."
  []
  (let [service (or (System/getenv "SLACK_KEYCHAIN_SERVICE")
                    "Slack Safe Storage")]
    (or (sh "security" "find-generic-password" "-s" service "-w")
        (throw (ex-info "Could not retrieve Slack keychain password" {})))))

(defn decrypt-cookie
  "Decrypt the Slack 'd' cookie from the Cookies SQLite database.
  Multi-step: extract blob → keychain password → PBKDF2 → AES-CBC → strip padding."
  []
  (let [cookies-db (str slack-data-dir "Cookies")
        tmp-db     (doto (java.io.File/createTempFile "slack-cookies-" ".db")
                     (.deleteOnExit))
        tmp-enc    (doto (java.io.File/createTempFile "slack-enc-" ".bin")
                     (.deleteOnExit))
        tmp-dec    (doto (java.io.File/createTempFile "slack-dec-" ".bin")
                     (.deleteOnExit))
        db-path    (.getAbsolutePath tmp-db)
        enc-path   (.getAbsolutePath tmp-enc)
        dec-path   (.getAbsolutePath tmp-dec)]
    (try
      ;; 0. Copy the Cookies DB to avoid locking issues with the Slack app
      (shell/sh "cp" cookies-db db-path)

      ;; 1. Extract encrypted blob, skip 3-byte v10 prefix
      (shell/sh "sqlite3" db-path
                (str "SELECT writefile('" enc-path
                     "', substr(encrypted_value, 4)) "
                     "FROM cookies WHERE name='d' LIMIT 1;"))

      (when (and (.exists tmp-enc) (pos? (.length tmp-enc)))
        (let [pass     (get-keychain-password)
              salt-hex "73616c747973616c74"
              ;; 2. PBKDF2 key derivation (SHA1, 1003 iters, 16-byte key)
              key-hex  (sh "bash" "-c"
                           (str "openssl kdf -keylen 16"
                                " -kdfopt digest:SHA1"
                                " -kdfopt 'pass:" pass "'"
                                " -kdfopt hexsalt:" salt-hex
                                " -kdfopt iter:1003"
                                " -binary PBKDF2 | xxd -p"))
              iv-hex   "20202020202020202020202020202020"]

          (when key-hex
            ;; 3. AES-128-CBC decrypt
            (shell/sh "openssl" "enc" "-aes-128-cbc" "-d"
                      "-K" key-hex "-iv" iv-hex "-nopad"
                      "-in" enc-path "-out" dec-path)

            ;; 4. Skip 32-byte domain hash prefix, strip PKCS7 padding
            (let [raw (sh "bash" "-c"
                          (str "dd if='" dec-path "' bs=1 skip=32 2>/dev/null"
                               " | perl -pe 's/[\\x01-\\x10]+$//'"))]
              (when (and raw (str/starts-with? raw "xoxd-"))
                raw)))))
      (finally
        (.delete tmp-db)
        (.delete tmp-enc)
        (.delete tmp-dec)))))

;;; ---------- Credential management ----------

(defn- normalize-host [s]
  (cond-> s
    (not (str/includes? s ".slack.com"))
    (str ".slack.com")))

(defn ensure-credentials!
  "Discover workspaces, decrypt cookie, fetch tokens from workspace HTML.
  Runs lazily on first call; pass force? = true to re-extract."
  ([] (ensure-credentials! false))
  ([force?]
   (when (or force? (empty? @credentials))
     (when force?
       (reset! credentials {})
       (reset! default-host nil))
     (let [hosts  (or (seq (discover-workspaces))
                      (throw (ex-info "No workspaces found. Is the Slack app running and logged in?" {})))
           cookie (or (decrypt-cookie)
                      (throw (ex-info "Could not decrypt Slack session cookie." {})))]
       (doseq [host hosts
               :let [token (extract-token-from-html host cookie)]
               :when token]
         (swap! credentials assoc host {:token token :cookie cookie})
         (or @default-host (reset! default-host host)))
       ;; Allow env-var override for the default
       (let [h (some-> (System/getenv "SLACK_DEFAULT_HOST") normalize-host)]
         (when (and h (contains? @credentials h))
           (reset! default-host h)))
       (reset! last-refresh (System/currentTimeMillis))
       (or (seq @credentials)
           (throw (ex-info "No valid tokens found. Cookie may be expired - restart Slack app." {})))))))

(defn resolve-host
  "Turn an optional workspace name into a full host, or use the default.
  Attempts credential refresh on miss (throttled to once per 60s)."
  [workspace]
  (ensure-credentials!)
  (if-not workspace
    (or @default-host
        (throw (ex-info "No Slack workspaces found." {})))
    (let [h         (normalize-host workspace)
          not-found #(throw (ex-info (str "No credentials for workspace: " workspace
                                          ". Available: " (str/join ", " (keys @credentials)))
                                     {}))]
      (cond
        (contains? @credentials h)
        h

        (< (- (System/currentTimeMillis) @last-refresh) refresh-interval-ms)
        (not-found)

        :else
        (do (ensure-credentials! true)
            (if (contains? @credentials h) h (not-found)))))))

;;; ---------- API layer ----------

(def ^:private token-error?
  "Slack API errors that indicate expired or invalid credentials."
  #{"token_expired" "token_revoked" "invalid_auth" "not_authed" "account_inactive"})

(defn- api-request*
  "Single-attempt authenticated GET to a Slack API endpoint."
  [host endpoint params]
  (let [{:keys [token cookie]} (get @credentials host)]
    (when-not token
      (throw (ex-info (str "No credentials for " host) {})))
    (let [resp (http/get (str "https://slack.com/api/" endpoint)
                         {:headers      {"Authorization" (str "Bearer " token)
                                         "Cookie"        (str "d=" cookie ";")
                                         "Content-Type"  "application/json"}
                          :query-params params
                          :throw        false})]
      (json/parse-string (:body resp)))))

(defn api-request
  "Authenticated GET to a Slack API endpoint. Returns parsed JSON body.
  On token-related errors, forces credential refresh and retries once."
  [host endpoint params]
  (let [body (api-request* host endpoint params)]
    (if (and (not (get body "ok"))
             (token-error? (get body "error"))
             (< refresh-interval-ms
                (- (System/currentTimeMillis) @last-refresh)))
      (do (ensure-credentials! true)
          (api-request* host endpoint params))
      body)))

;;; ---------- Resolution ----------

(defn resolve-user
  "Resolve a Slack user ID to a display name (cached)."
  [host user-id]
  (let [k (str host ":" user-id)]
    (or (get @user-cache k)
        (try
          (let [resp    (api-request host "users.info" {"user" user-id})
                user    (get resp "user")
                profile (get user "profile")
                name    (or (not-empty (get profile "display_name"))
                            (not-empty (get user "real_name"))
                            (not-empty (get user "name"))
                            user-id)]
            (swap! user-cache assoc k name)
            name)
          (catch Exception _ user-id)))))

(defn resolve-channel
  "Resolve a Slack channel ID to a channel name (cached)."
  [host channel-id]
  (let [k (str host ":" channel-id)]
    (or (get @channel-cache k)
        (try
          (let [resp (api-request host "conversations.info"
                                  {"channel" channel-id})
                name (or (get-in resp ["channel" "name"]) channel-id)]
            (swap! channel-cache assoc k name)
            name)
          (catch Exception _ channel-id)))))

(defn resolve-mentions
  "Replace Slack mention markup with human-readable names."
  [host text]
  (if (and host text)
    (-> text
        ;; <@U123ABC> → @display-name
        (str/replace #"<@(U[A-Z0-9]+)>"
                     (fn [[_ uid]] (str "@" (resolve-user host uid))))
        ;; <#C123ABC|channel-name> → #channel-name
        (str/replace #"<#(C[A-Z0-9]+)\|([^>]*)>"
                     (fn [[_ cid name]]
                       (str "#" (if (not-empty name)
                                 name
                                 (resolve-channel host cid)))))
        ;; <url|label> → label (url)
        (str/replace #"<(https?://[^|>]+)\|([^>]+)>"
                     (fn [[_ url label]] (str label " (" url ")")))
        ;; <url> → bare url
        (str/replace #"<(https?://[^>]+)>" (fn [[_ url]] url)))
    (or text "")))

;;; ---------- Timestamp formatting ----------

(defn format-ts
  "Format a Slack epoch timestamp string to yyyy-MM-dd HH:mm."
  [ts]
  (when (and ts (string? ts))
    (try
      (let [epoch   (Double/parseDouble ts)
            instant (java.time.Instant/ofEpochSecond (long epoch))
            zdt     (.atZone instant (java.time.ZoneId/systemDefault))
            fmt     (java.time.format.DateTimeFormatter/ofPattern
                     "yyyy-MM-dd HH:mm")]
        (.format zdt fmt))
      (catch Exception _ nil))))

;;; ---------- URL parsing ----------

(defn parse-slack-url
  "Parse https://<ws>.slack.com/archives/<chan>/p<ts> into a map."
  [url]
  (when-let [[_ workspace channel-id raw-ts]
             (re-find #"https://([^/]+)\.slack\.com/archives/([^/]+)/p(\d+)"
                      url)]
    (let [n (count raw-ts)
          ts (str (subs raw-ts 0 (- n 6)) "." (subs raw-ts (- n 6)))
          thread-ts (second (re-find #"thread_ts=([0-9.]+)" url))]
      {:workspace  workspace
       :channel-id channel-id
       :ts         ts
       :thread-ts  thread-ts
       :host       (str workspace ".slack.com")})))

;;; ---------- Permalink construction ----------

(defn make-permalink
  "Construct a Slack message permalink from host, channel-id, and timestamp."
  [host channel-id ts]
  (let [ts-no-dot (str/replace ts "." "")]
    (str "https://" host "/archives/" channel-id "/p" ts-no-dot)))

;;; ---------- Tool: slack-search ----------

(defn format-search-results [resp host query]
  (if-not (get resp "ok")
    (str "Slack API error: " (get resp "error"))
    (let [messages (get resp "messages")
          matches  (get messages "matches")
          total    (get messages "total")
          paging   (get messages "paging")
          page     (get paging "page")
          pages    (get paging "pages")]
      (str "Found " total " result(s) for \"" query "\" in " host
           " (page " page "/" pages ")\n\n"
           (str/join
            "\n\n---\n\n"
            (map (fn [match]
                   (let [username     (or (get match "username") "unknown")
                         text         (or (not-empty (get match "text"))
                                         ;; fallback: attachment text
                                         (when-let [att (first
                                                         (get match "attachments"))]
                                           (or (get att "text")
                                               (get att "fallback")))
                                         "")
                         channel      (get match "channel")
                         channel-name (when channel (get channel "name"))
                         ts           (get match "ts")
                         permalink    (get match "permalink")
                         conv-type    (cond
                                        (get channel "is_im")   "DM"
                                        (get channel "is_mpim") "Group DM"
                                        (get channel "is_group") "Private"
                                        :else                    nil)
                         location     (str (when conv-type (str conv-type " "))
                                          (if channel-name
                                            (str "#" channel-name)
                                            "unknown"))
                         clean-text   (resolve-mentions host text)]
                     (str "@" username " in " location
                          " | " (or (format-ts ts) "unknown date")
                          "\n" clean-text
                          "\n🔗 " permalink)))
                 matches))))))

(defn do-search [{:strs [query workspace count page]}]
  (try
    (let [host   (resolve-host workspace)
          params {"query" query
                  "count" (or count "20")
                  "page"  (or page "1")}
          resp   (api-request host "search.messages" params)]
      {:content [{:type "text" :text (format-search-results resp host query)}]})
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- Tool: slack-fetch-thread ----------

(defn format-message
  "Format a single Slack message with permalink for traceability."
  [host channel-id msg indent]
  (let [user-id (get msg "user")
        author  (if user-id (resolve-user host user-id) "unknown")
        ts      (get msg "ts")
        text    (resolve-mentions host (or (get msg "text") ""))
        link    (when ts (make-permalink host channel-id ts))
        files   (get msg "files")
        file-lines (when (seq files)
                     (map (fn [f]
                            (let [name (or (get f "name") "file")
                                  url  (or (get f "url_private")
                                           (get f "permalink") "")]
                              (str indent "📎 " name " — " url)))
                          files))]
    (str indent "@" author " | " (or (format-ts ts) "unknown date")
         "\n" indent text
         (when (seq file-lines)
           (str "\n" (str/join "\n" file-lines)))
         (when link
           (str "\n" indent "🔗 " link)))))

(defn format-thread [messages host channel-id]
  (let [channel-name (resolve-channel host channel-id)]
    (str "Thread in #" channel-name " (" host ")\n\n"
         (str/join
          "\n\n"
          (map-indexed
           (fn [i msg]
             (format-message host channel-id msg (if (zero? i) "" "    ")))
           messages)))))

(defn do-fetch-thread [{:strs [url]}]
  (try
    (let [parsed    (or (parse-slack-url url)
                       (throw (ex-info (str "Could not parse Slack URL: " url) {})))
          {:keys [host channel-id ts thread-ts]} parsed
          _         (resolve-host host)
          parent-ts (or thread-ts ts)
          tresp     (api-request host "conversations.replies"
                                 {"channel" channel-id
                                  "ts"      parent-ts
                                  "limit"   "200"})
          messages  (when (get tresp "ok") (get tresp "messages"))]
      (if (seq messages)
        {:content [{:type "text"
                    :text (format-thread messages host channel-id)}]}
        ;; Fallback: single message via conversations.history
        (let [hresp (api-request host "conversations.history"
                                 {"channel"   channel-id
                                  "latest"    ts
                                  "inclusive"  "true"
                                  "limit"     "1"})
              msgs  (when (get hresp "ok") (get hresp "messages"))]
          (if (seq msgs)
            {:content [{:type "text"
                        :text (format-thread msgs host channel-id)}]}
            {:content [{:type "text"
                        :text (str "Could not fetch message from " url
                                   (when-let [err (or (get tresp "error")
                                                      (get hresp "error"))]
                                     (str ": " err)))}]
             :isError true}))))
    (catch Exception e
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
       :isError true})))

;;; ---------- MCP Server ----------

(def server-info {:name "slack" :version "1.0.0"})

(def tools
  [{:name        "slack-search"
    :description "Search Slack messages. Returns matching messages with author, channel, timestamp, and permalink. Uses the default workspace unless a specific one is provided."
    :inputSchema
    {:type       "object"
     :properties {:query     {:type        "string"
                              :description "Search query (supports Slack search operators: from:user in:channel before:date after:date has:link has:reaction etc.)"}
                  :workspace {:type        "string"
                              :description "Workspace name (e.g. 'myteam' or 'myteam.slack.com'). Optional — uses default if omitted."}
                  :count     {:type        "string"
                              :description "Results per page (default: '20')"}
                  :page      {:type        "string"
                              :description "Page number (default: '1')"}}
     :required   ["query"]}}

   {:name        "slack-fetch-thread"
    :description "Fetch a Slack conversation or thread given a Slack message URL. Returns the full thread with all replies, each with a permalink. Resolves user mentions to display names."
    :inputSchema
    {:type       "object"
     :properties {:url {:type        "string"
                        :description "Slack message URL (e.g. https://myteam.slack.com/archives/C123ABC/p1234567890123456)"}}
     :required   ["url"]}}])

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
                 "slack-search"       (do-search args)
                 "slack-fetch-thread" (do-fetch-thread args)
                 {:content [{:type "text"
                             :text (str "Unknown tool: " tool)}]
                  :isError true})})

    ;; Unknown method — ignore
    nil))

;;; ---------- Main loop ----------

(when (= *file* (System/getProperty "babashka.file"))
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (when-not (str/blank? line)
      (when-let [res (handle-request (json/parse-string line))]
        (println (json/generate-string res))
        (flush)))))
