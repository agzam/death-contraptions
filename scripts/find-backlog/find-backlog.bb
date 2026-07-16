#!/usr/bin/env bb

;; One call returns a board's context plus its pickable backlog candidates, so
;; the find-backlog-work command never hand-writes jq at runtime.
;;
;; The static board facts (filter, story-points field, blocked status ids,
;; columns) are cached under ~/.cache/find-backlog-work/; the backlog itself is
;; volatile and is fetched fresh every run, filtered to pickable (unassigned,
;; status category "new", not in a hold lane), and projected to the fields the
;; command clusters on - all deterministically.
;;
;; Not an MCP server: invoked by absolute path from the command, never
;; registered in setup.bb or loaded into a session.
;;
;; Usage: bb find-backlog.bb [<board-id>] [--scope backlog|sprint|both] [--refresh] [--plan]
;; Prints one EDN map: {:board {...} :scope s :total N :pickable M :candidates [...]}
;;
;; --plan: planning projection for the sprint-planning-prep command - the active
;; sprint's unfinished tickets (done/rejected dropped) first, then the whole
;; backlog in rank order, no pickable filter. Each candidate carries :assignee, a
;; :sprint flag, and a clipped :description; :pickable is omitted. Reuses the same
;; cached board facts, so the two commands never re-resolve the board.

(require '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def ^:private gpg-config
  "/Users/ryl/GitHub/agzam/death-contraptions/local-config.edn.gpg")
(def ^:private fallback-board "3018")
(def ^:private cache-file
  (io/file (str (System/getenv "HOME") "/.cache/find-backlog-work/cache.edn")))
(def ^:private cache-ttl-ms (* 30 24 60 60 1000)) ; 30 days

;; ---------- pure: cli ----------

(defn parse-cli
  "Parse [<board-id>] [--scope x] [--refresh] [--plan] into {:board :scope :refresh? :plan?}."
  [args]
  (loop [xs (seq args) board nil scope "backlog" refresh? false plan? false]
    (if (empty? xs)
      {:board board :scope scope :refresh? refresh? :plan? plan?}
      (let [x (first xs)]
        (cond
          (= x "--refresh") (recur (rest xs) board scope true plan?)
          (= x "--plan")    (recur (rest xs) board scope refresh? true)
          (= x "--scope")   (recur (drop 2 xs) board (or (second xs) scope) refresh? plan?)
          (str/starts-with? x "--") (recur (rest xs) board scope refresh? plan?)
          (nil? board)      (recur (rest xs) x scope refresh? plan?)
          :else             (recur (rest xs) board scope refresh? plan?))))))

;; ---------- pure: board context ----------

(defn- ne "Trimmed non-empty string, or nil." [s]
  (some-> s str str/trim not-empty))

(defn pick-board
  "Choose [board-id store-default?]; resolve-default runs only as last resort."
  [arg env cached-default resolve-default]
  (if-let [x (or (ne arg) (ne env) (ne cached-default))]
    [x false]
    [(resolve-default) true]))

(defn hold-status-ids
  "Status ids of hold/wait lanes - columns named Blocked / On hold / Waiting."
  [board-config]
  (->> (get-in board-config [:columnConfig :columns])
       (filter #(re-find #"(?i)blocked|on hold|waiting" (str (:name %))))
       (mapcat :statuses)
       (keep :id)
       (map str)
       distinct
       vec))

(defn derive-board-context [board-id board-config]
  {:board-id (str board-id)
   :name (:name board-config)
   :type (:type board-config)
   :filter-id (get-in board-config [:filter :id])
   :story-points-field (get-in board-config [:estimation :field :fieldId])
   :blocked-status-ids (hold-status-ids board-config)
   :columns (mapv :name (get-in board-config [:columnConfig :columns]))})

;; ---------- pure: candidates ----------

(defn pickable?
  "Unassigned, not started (statusCategory new), and not in a hold lane."
  [blocked-ids issue]
  (let [f (:fields issue)]
    (and (nil? (:assignee f))
         (= "new" (get-in f [:status :statusCategory :key]))
         (not (contains? blocked-ids (get-in f [:status :id]))))))

(defn project
  "Reduce an issue to the fields the command clusters and evaluates on. rank is
  the 1-based position in the full (unfiltered) board order = its priority."
  [story-points-field rank issue]
  (let [f (:fields issue)
        target (fn [l] (or (:inwardIssue l) (:outwardIssue l)))]
    (cond-> {:key (:key issue)
             :rank rank
             :type (get-in f [:issuetype :name])
             :priority (get-in f [:priority :name])
             :status (get-in f [:status :name])
             :components (mapv :name (:components f))
             :labels (vec (:labels f))
             :parent (get-in f [:parent :key])
             :links (mapv (fn [l] {:rel (get-in l [:type :name])
                                   :key (:key (target l))
                                   :cat (get-in (target l) [:fields :status :statusCategory :key])})
                          (:issuelinks f))
             :created (some-> (:created f) (subs 0 10))
             :updated (some-> (:updated f) (subs 0 10))
             :summary (:summary f)}
      story-points-field (assoc :points (get f (keyword story-points-field))))))

(defn pickable-candidates
  "Filter issues to pickable, preserving full-order rank, then project them."
  [story-points-field blocked-ids issues]
  (->> issues
       (map-indexed (fn [i iss] [(inc i) iss]))
       (filter (fn [[_ iss]] (pickable? blocked-ids iss)))
       (mapv (fn [[rank iss]] (project story-points-field rank iss)))))

(def ^:private max-desc-chars 1600)

(defn- clip
  "Drop carriage returns and cap s at n chars so descriptions stay readable in EDN."
  [s n]
  (let [s (str/replace (str s) "\r" "")]
    (if (< n (count s)) (str (subs s 0 n) "...") s)))

(defn active?
  "False for done-category statuses (Done, Rejected) - finished work planning skips."
  [issue]
  (not= "done" (get-in issue [:fields :status :statusCategory :key])))

(defn plan-candidates
  "Project every issue in rank order with no pickable filter, adding assignee, a
  :sprint flag (true when the key is in sprint-keys), and a clipped description -
  what the planning command explains."
  [story-points-field sprint-keys issues]
  (->> issues
       (map-indexed
        (fn [i iss]
          (assoc (project story-points-field (inc i) iss)
                 :assignee (get-in iss [:fields :assignee :displayName])
                 :sprint (contains? sprint-keys (:key iss))
                 :description (some-> (get-in iss [:fields :description]) (clip max-desc-chars)))))
       vec))

;; ---------- effects: jira / gpg / cache ----------

(defn- jira-get [path]
  (let [{:keys [exit out err]} (proc/sh ["jira" "req" path])]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "jira req failed: " path) {:exit exit :err (str/trim (str err))})))))

(defn config-board-id
  "Board id from :qlik-verify :jira :board in the encrypted config, or nil."
  []
  (let [{:keys [exit out]} (proc/sh ["gpg" "--quiet" "--decrypt" gpg-config])]
    (when (zero? exit)
      (ne (get-in (edn/read-string out) [:qlik-verify :jira :board])))))

(defn fetch-board-config [board-id]
  (jira-get (str "/rest/agile/1.0/board/" board-id "/configuration")))

(defn- paginate
  "Collect .issues across pages from an agile endpoint; path-fn takes startAt."
  [path-fn]
  (loop [start 0 acc []]
    (let [{:keys [issues total]} (jira-get (path-fn start))
          acc' (into acc (or issues []))]
      (if (and (seq issues) (< (count acc') (or total 0)))
        (recur (count acc') acc')
        acc'))))

(defn- field-list [story-points-field]
  (str "summary,status,assignee,issuetype,priority,labels,components,parent,issuelinks,created,updated"
       (when story-points-field (str "," story-points-field))))

(defn fetch-backlog [board-id fields]
  (paginate (fn [start]
              (str "/rest/agile/1.0/board/" board-id "/backlog?maxResults=100&startAt=" start
                   "&fields=" fields))))

(defn fetch-sprint [board-id fields]
  (when-let [sid (-> (jira-get (str "/rest/agile/1.0/board/" board-id "/sprint?state=active"))
                     :values first :id)]
    (paginate (fn [start]
                (str "/rest/agile/1.0/board/" board-id "/sprint/" sid "/issue?maxResults=100&startAt=" start
                     "&fields=" fields)))))

(defn fetch-issues [board-id scope fields]
  (case scope
    "sprint" (vec (fetch-sprint board-id fields))
    "both"   (let [b (fetch-backlog board-id fields)
                   seen (into #{} (map :key) b)]
               (into b (remove #(seen (:key %)) (fetch-sprint board-id fields))))
    (fetch-backlog board-id fields)))

(defn- cache-fresh? []
  (and (.exists cache-file)
       (< (- (System/currentTimeMillis) (.lastModified cache-file)) cache-ttl-ms)))

(defn- read-cache []
  (try (edn/read-string (slurp cache-file)) (catch Exception _ {})))

(defn- write-cache! [cache]
  (io/make-parents cache-file)
  (spit cache-file (pr-str cache)))

(defn board-context!
  "Resolve the board id (arg/env/cache/gpg) and return its cached/derived
  context, writing the cache when freshly resolved or fetched."
  [arg refresh?]
  (let [env (System/getenv "BACKLOG_BOARD")
        cache (if (and (not refresh?) (cache-fresh?)) (read-cache) {})
        [board-id store-default?] (pick-board arg env
                                              (get-in cache [:default :board-id])
                                              #(or (config-board-id) fallback-board))
        cached-ctx (get-in cache [:boards board-id])
        ctx (or cached-ctx
                (assoc (derive-board-context board-id (fetch-board-config board-id))
                       :cached-at (str (java.time.Instant/now))))
        dirty? (or store-default? (nil? cached-ctx))
        cache' (cond-> cache
                 store-default? (assoc-in [:default :board-id] board-id)
                 true (assoc-in [:boards board-id] ctx))]
    (when dirty? (write-cache! cache'))
    ctx))

(defn -main [& raw]
  (let [{:keys [board scope refresh? plan?]} (parse-cli raw)]
    (if (and board (not (re-matches #"\d+" (str board))))
      (binding [*out* *err*]
        (println (str "find-backlog: '" board "' is not a numeric board id; "
                      "resolve a name/project to an id first, then pass it."))
        (System/exit 2))
      (let [ctx (board-context! board refresh?)
            spf (:story-points-field ctx)
            board-id (:board-id ctx)]
        (prn
         (if plan?
           (let [fields (str (field-list spf) ",description")
                 sprint (vec (filter active? (fetch-sprint board-id fields)))
                 sprint-keys (into #{} (map :key) sprint)
                 backlog (fetch-backlog board-id fields)
                 issues (into sprint (remove #(sprint-keys (:key %)) backlog))]
             {:board (dissoc ctx :cached-at)
              :total (count issues)
              :sprint (count sprint)
              :candidates (plan-candidates spf sprint-keys issues)})
           (let [issues (fetch-issues board-id scope (field-list spf))
                 candidates (pickable-candidates spf (set (:blocked-status-ids ctx)) issues)]
             {:board (dissoc ctx :cached-at)
              :scope scope
              :total (count issues)
              :pickable (count candidates)
              :candidates candidates})))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
