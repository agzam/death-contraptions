#!/usr/bin/env bb

;; One call returns a board's context plus its pickable backlog candidates, so
;; the commands never hand-write jq at runtime.
;;
;; The static board facts (filter, story-points field, blocked status ids,
;; columns) are cached under ~/.cache/jira-board/; the backlog itself is
;; volatile and is fetched fresh every run, filtered to pickable (unassigned,
;; status category "new", not in a hold lane), and projected to the fields the
;; command clusters on - all deterministically.
;;
;; Not an MCP server: invoked by absolute path from the commands, never
;; registered in setup.bb or loaded into a session.
;;
;; Usage: bb jira-board.bb [<board-id>] [--scope backlog|sprint|both] [--refresh] [--plan]
;;                           [--retro] [--sprint active|last|<id>]
;; Prints one EDN map: {:board {...} :scope s :total N :pickable M :candidates [...]}
;;
;; --plan: planning projection for the sprint-planning-prep command - the active
;; sprint's unfinished tickets (done/rejected dropped) first, then the whole
;; backlog in rank order, no pickable filter. Each candidate carries :assignee, a
;; :sprint flag, and a clipped :description; :pickable is omitted. Reuses the same
;; cached board facts, so the two commands never re-resolve the board.
;;
;; --retro: retrospective projection for the sprint-retro command - one sprint's
;; whole story, classified by Jira's own sprint report (completed / spillover /
;; punted / added-during) and enriched from each issue's changelog (carryover,
;; status timeline, rework, handoffs, flags) plus clipped comments, with a
;; per-person rollup and the board's velocity trend. --sprint picks which sprint
;; (default the active one; "last" = most recent closed; or an explicit id) so it
;; serves both a post-close retro and a mid-sprint glimpse. Reuses the same cached
;; board facts as the other two projections.

(require '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def ^:private gpg-config
  "/Users/ryl/GitHub/agzam/death-contraptions/local-config.edn.gpg")
(def ^:private fallback-board "3018")
(def ^:private cache-file
  (io/file (str (System/getenv "HOME") "/.cache/jira-board/cache.edn")))
(def ^:private cache-ttl-ms (* 30 24 60 60 1000)) ; 30 days

;; ---------- pure: cli ----------

(defn parse-cli
  "Parse [<board-id>] [--scope x] [--refresh] [--plan] [--retro] [--sprint s] into
  {:board :scope :refresh? :plan? :retro? :sprint-sel}."
  [args]
  (loop [xs (seq args) board nil scope "backlog" refresh? false plan? false retro? false sprint-sel nil]
    (if (empty? xs)
      {:board board :scope scope :refresh? refresh? :plan? plan? :retro? retro? :sprint-sel sprint-sel}
      (let [x (first xs)]
        (cond
          (= x "--refresh") (recur (rest xs) board scope true plan? retro? sprint-sel)
          (= x "--plan")    (recur (rest xs) board scope refresh? true retro? sprint-sel)
          (= x "--retro")   (recur (rest xs) board scope refresh? plan? true sprint-sel)
          (= x "--scope")   (recur (drop 2 xs) board (or (second xs) scope) refresh? plan? retro? sprint-sel)
          (= x "--sprint")  (recur (drop 2 xs) board scope refresh? plan? retro? (or (second xs) sprint-sel))
          (str/starts-with? x "--") (recur (rest xs) board scope refresh? plan? retro? sprint-sel)
          (nil? board)      (recur (rest xs) x scope refresh? plan? retro? sprint-sel)
          :else             (recur (rest xs) board scope refresh? plan? retro? sprint-sel))))))

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

;; ---------- pure: retro ----------

(defn select-sprint
  "Choose the sprint to retro from the board's sprints. sel: numeric id -> that
  sprint; \"last\" -> most recent closed; nil/\"active\" -> the active sprint,
  falling back to the most recent closed when none is active (retro run after the
  next sprint already started)."
  [sprints sel]
  (let [closed (->> sprints (filter #(= "closed" (:state %))) (sort-by :startDate))
        latest-closed (last closed)
        active (first (filter #(= "active" (:state %)) sprints))]
    (cond
      (and sel (re-matches #"\d+" (str sel))) (first (filter #(= (str (:id %)) (str sel)) sprints))
      (= sel "last") latest-closed
      :else (or active latest-closed))))

(defn- days-between [^java.time.Instant a ^java.time.Instant b]
  (.toDays (java.time.Duration/between a b)))

(defn sprint-meta
  "Project a sprint to display facts + a day budget relative to now, so the same
  output frames a post-close retro and a mid-sprint glimpse."
  [sprint now]
  (let [start (some-> (:startDate sprint) java.time.Instant/parse)
        end (some-> (:endDate sprint) java.time.Instant/parse)
        active? (= "active" (:state sprint))]
    {:id (:id sprint)
     :name (:name sprint)
     :goal (not-empty (:goal sprint))
     :state (:state sprint)
     :start (some-> (:startDate sprint) (subs 0 10))
     :end (some-> (:endDate sprint) (subs 0 10))
     :complete-date (some-> (:completeDate sprint) (subs 0 10))
     :days-total (when (and start end) (days-between start end))
     :days-elapsed (when start (days-between start (if (and end (.isAfter now end)) end now)))
     :days-remaining (when (and active? end) (max 0 (days-between now end)))
     :ended? (boolean (and end (.isAfter now end)))}))

(defn carryover-info
  "Carryover = the issue sat in a sprint that started before this one. sprint-index
  maps id-string -> {:name :start}. Returns {:carryover? :carryover-from [names]}."
  [sprint-ids current-id current-start sprint-index]
  (let [priors (->> sprint-ids
                    (map str)
                    distinct
                    (remove #(= % (str current-id)))
                    (keep sprint-index)
                    (filter #(and (:start %) current-start (neg? (compare (:start %) current-start))))
                    (mapv :name))]
    {:carryover? (boolean (seq priors)) :carryover-from priors}))

(defn status-timeline
  "Status transitions from a changelog, oldest first: [{:at :by :from :to}]."
  [histories]
  (->> histories
       (mapcat (fn [h]
                 (for [it (:items h) :when (= "status" (str/lower-case (str (:field it))))]
                   {:at (:created h)
                    :by (get-in h [:author :displayName])
                    :from (:fromString it)
                    :to (:toString it)})))
       (sort-by :at)
       vec))

(defn stage-order
  "Map lower-cased column name -> its board index, for ordering status names."
  [columns]
  (into {} (map-indexed (fn [i n] [(str/lower-case (str n)) i]) columns)))

(defn- hold-name? [s]
  (boolean (re-find #"(?i)blocked|on hold|waiting" (str s))))

(defn- stage-idx
  "Resolve a status name to a board column index, exact then substring either way."
  [stage-ord nm]
  (when nm
    (let [n (str/lower-case nm)]
      (or (get stage-ord n)
          (some (fn [[k v]] (when (or (str/includes? n k) (str/includes? k n)) v)) stage-ord)))))

(defn rework-count
  "Backward status moves (to an earlier column) that don't involve a hold lane -
  a proxy for review bounce-backs / reopens."
  [timeline stage-ord]
  (->> timeline
       (filter (fn [{:keys [from to]}]
                 (let [fi (stage-idx stage-ord from) ti (stage-idx stage-ord to)]
                   (and fi ti (< ti fi) (not (hold-name? from)) (not (hold-name? to))))))
       count))

(defn handoffs
  "Assignee changes from a changelog, oldest first: [{:from :to :at}]."
  [histories]
  (->> histories
       (mapcat (fn [h]
                 (for [it (:items h) :when (re-matches #"(?i)assignee" (str (:field it)))]
                   {:from (:fromString it) :to (:toString it) :at (:created h)})))
       (sort-by :at)
       vec))

(defn flagged-count
  "How many times an impediment flag was raised (Flagged set to a non-blank value)."
  [histories]
  (->> histories
       (mapcat :items)
       (filter #(and (re-matches #"(?i)flagged" (str (:field %)))
                     (not (str/blank? (str (:toString %))))))
       count))

(def ^:private max-comment-chars 400)
(def ^:private max-comments 15)

(defn project-comments
  "The most recent comments, clipped, with a total count so truncation is visible.
  The retro's stochastic layer reads these; retrieval stays deterministic here."
  [comment-field]
  (let [cs (:comments comment-field)]
    {:count (or (:total comment-field) (count cs))
     :items (->> cs
                 (take-last max-comments)
                 (mapv (fn [c] {:who (get-in c [:author :displayName])
                                :when (some-> (:created c) (subs 0 10))
                                :body (clip (:body c) max-comment-chars)})))}))

(defn- gh-base
  "Minimal ticket projection from a sprint-report record, for the rare issue the
  by-key fetch does not return (e.g. later deleted)."
  [gh]
  {:key (:key gh)
   :type (:typeName gh)
   :priority (:priorityName gh)
   :status (get-in gh [:status :name])
   :summary (:summary gh)
   :points (get-in gh [:estimateStatistic :statFieldValue :value])})

(defn enrich-retro
  "Merge a sprint-report record (bucket, sprint membership, epic) with the by-key
  issue (fields + changelog) into one retro ticket carrying the deterministic
  signals the command reasons over."
  [story-points-field sprint-index current-id current-start added-set stage-ord bucket idx {:keys [gh issue]}]
  (let [f (:fields issue)
        hist (get-in issue [:changelog :histories])
        tl (status-timeline hist)
        base (if issue (dissoc (project story-points-field idx issue) :rank) (gh-base gh))]
    (merge base
           (carryover-info (:sprintIds gh) current-id current-start sprint-index)
           {:bucket bucket
            :assignee (or (get-in f [:assignee :displayName]) (:assigneeName gh))
            :epic (or (:parent base) (:epic gh))
            :epic-summary (get-in gh [:epicField :summary])
            :added-during? (contains? added-set (:key gh))
            :resolved (some-> (:resolutiondate f) (subs 0 10))
            :timeline tl
            :rework (rework-count tl stage-ord)
            :handoffs (handoffs hist)
            :flags (flagged-count hist)
            :description (some-> (:description f) (clip max-desc-chars))
            :comments (project-comments (:comment f))})))

(defn person-rollup
  "Aggregate candidates by assignee - who completed / carried / spilled what, and
  their rework/handoff/flag load - sorted by completed points."
  [candidates]
  (let [pts (fn [xs] (reduce + 0 (keep :points xs)))]
    (->> candidates
         (filter :assignee)
         (group-by :assignee)
         (map (fn [[nm cs]]
                (let [by-bucket (group-by :bucket cs)]
                  {:name nm
                   :completed (mapv :key (:completed by-bucket))
                   :completed-pts (pts (:completed by-bucket))
                   :spillover (mapv :key (:not-completed by-bucket))
                   :spillover-pts (pts (:not-completed by-bucket))
                   :carryover (mapv :key (filter :carryover? cs))
                   :added (mapv :key (filter :added-during? cs))
                   :rework (reduce + 0 (map :rework cs))
                   :handoffs (reduce + 0 (map (comp count :handoffs) cs))
                   :flags (reduce + 0 (map :flags cs))})))
         (sort-by (comp - :completed-pts))
         vec)))

(defn velocity-view
  "Name and order the last few velocity entries (estimated vs completed points)."
  [entries sprint-index]
  (->> entries
       (map (fn [[sid v]]
              (let [id (name sid)]
                {:sprint (get-in sprint-index [id :name])
                 :start (get-in sprint-index [id :start])
                 :estimated (get-in v [:estimated :value])
                 :completed (get-in v [:completed :value])})))
       (filter :sprint)
       (sort-by :start)
       (take-last 6)
       (mapv #(dissoc % :start))))

(defn est-sum
  "Normalize a greenhopper estimate-sum text: it returns the string \"null\" when
  a sprint has no summed estimate."
  [x]
  (when (and x (not= "null" (str x))) (str x)))

(defn focus-set
  "The deterministic deep-dive shortlist the retro command reads first, keyed by
  reason (values are ticket keys): all spillover and all punted (always dug into),
  friction (completed but with rework, a raised flag, or 2+ handoffs), carryover
  wins (completed after migrating in from an earlier sprint), and headline
  (pointed completions, biggest first). Selection is deterministic, so the command
  never re-derives it from :candidates."
  [candidates]
  (let [completed (filter #(= :completed (:bucket %)) candidates)
        reassigned? (fn [c] (some #(and (:from %) (:to %)) (:handoffs c)))
        friction? (fn [c] (or (pos? (long (or (:rework c) 0)))
                              (pos? (long (or (:flags c) 0)))
                              (boolean (reassigned? c))))]
    {:spillover (mapv :key (filter #(= :not-completed (:bucket %)) candidates))
     :punted (mapv :key (filter #(= :punted (:bucket %)) candidates))
     :friction (mapv :key (filter friction? completed))
     :carryover-wins (mapv :key (filter :carryover? completed))
     :headline (->> completed
                    (filter #(and (:points %) (pos? (:points %))))
                    (sort-by (comp - :points))
                    (mapv :key))}))

(defn retro-summary
  "Headline counts + point sums for the sprint. :pointed-count = how many committed
  tickets carry an estimate, so the points burn can be read against how much of the
  work was estimated at all."
  [buckets sums added-count candidates]
  (let [committed (set (concat (:completed buckets) (:not-completed buckets)))]
    {:committed-count (count committed)
     :completed-count (count (:completed buckets))
     :spillover-count (count (:not-completed buckets))
     :punted-count (count (:punted buckets))
     :completed-elsewhere-count (count (:completed-elsewhere buckets))
     :added-during-count added-count
     :carryover-count (count (filter :carryover? candidates))
     :pointed-count (count (filter #(and (committed (:key %)) (:points %)) candidates))
     :points sums}))

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

(defn fetch-sprints
  "All active + closed sprints for the board (paginated)."
  [board-id]
  (loop [start 0 acc []]
    (let [{:keys [values isLast]}
          (jira-get (str "/rest/agile/1.0/board/" board-id
                         "/sprint?state=active,closed&maxResults=50&startAt=" start))]
      (if (or isLast (empty? values))
        (into acc values)
        (recur (+ start (count values)) (into acc values))))))

(defn fetch-sprint-report
  "Jira's own sprint report - the buckets its Sprint Report UI shows (completed,
  not-completed, punted, completed-elsewhere), plus keys added during the sprint
  and the estimate sums. The authoritative, deterministic classification."
  [board-id sprint-id]
  (:contents (jira-get (str "/rest/greenhopper/1.0/rapid/charts/sprintreport?rapidViewId="
                            board-id "&sprintId=" sprint-id))))

(defn fetch-velocity
  "Per-sprint estimated vs completed points for the board."
  [board-id]
  (:velocityStatEntries (jira-get (str "/rest/greenhopper/1.0/rapid/charts/velocity?rapidViewId=" board-id))))

(defn fetch-issues-by-keys
  "Issue details + changelog for a set of keys via the enhanced jql endpoint
  (token-paginated), chunked so the key list stays a sane query length. Fetching
  by key - not by sprint - is what recovers punted issues, which were removed from
  the sprint and so no longer match sprint = <id>."
  [keys fields]
  (->> (partition-all 80 keys)
       (mapcat
        (fn [chunk]
          (loop [token nil acc []]
            (let [jql (str "key in (" (str/join "," chunk) ")")
                  path (str "/rest/api/2/search/jql?maxResults=100&expand=changelog&fields=" fields
                            "&jql=" (str/replace jql " " "%20")
                            (when token (str "&nextPageToken=" token)))
                  {:keys [issues isLast nextPageToken]} (jira-get path)
                  acc' (into acc issues)]
              (if (or isLast (empty? issues)) acc' (recur nextPageToken acc'))))))
       vec))

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

(defn run-backlog [ctx scope]
  (let [spf (:story-points-field ctx)
        board-id (:board-id ctx)
        issues (fetch-issues board-id scope (field-list spf))
        candidates (pickable-candidates spf (set (:blocked-status-ids ctx)) issues)]
    {:board (dissoc ctx :cached-at)
     :scope scope
     :total (count issues)
     :pickable (count candidates)
     :candidates candidates}))

(defn run-plan [ctx]
  (let [spf (:story-points-field ctx)
        board-id (:board-id ctx)
        fields (str (field-list spf) ",description")
        sprint (vec (filter active? (fetch-sprint board-id fields)))
        sprint-keys (into #{} (map :key) sprint)
        backlog (fetch-backlog board-id fields)
        issues (into sprint (remove #(sprint-keys (:key %)) backlog))]
    {:board (dissoc ctx :cached-at)
     :total (count issues)
     :sprint (count sprint)
     :candidates (plan-candidates spf sprint-keys issues)}))

(defn run-retro
  "Assemble one sprint's retro picture: Jira's sprint-report buckets, per-issue
  changelog signals, the velocity trend, and a per-person rollup."
  [ctx sprint-sel]
  (let [spf (:story-points-field ctx)
        board-id (:board-id ctx)
        stage-ord (stage-order (:columns ctx))
        sprints (fetch-sprints board-id)
        sprint (select-sprint sprints sprint-sel)]
    (when-not sprint
      (throw (ex-info "no matching sprint (try --sprint active|last|<id>)" {:sel sprint-sel})))
    (let [sid (:id sprint)
          current-start (:startDate sprint)
          sprint-index (into {} (map (fn [s] [(str (:id s)) {:name (:name s) :start (:startDate s)}]))
                             sprints)
          report (fetch-sprint-report board-id sid)
          bucket-recs {:completed (:completedIssues report)
                       :not-completed (:issuesNotCompletedInCurrentSprint report)
                       :punted (:puntedIssues report)
                       :completed-elsewhere (:issuesCompletedInAnotherSprint report)}
          added-set (into #{} (map #(if (keyword? %) (name %) (str %)))
                          (keys (:issueKeysAddedDuringSprint report)))
          gh-by-key (into {} (for [[b recs] bucket-recs r recs] [(:key r) (assoc r :_bucket b)]))
          all-keys (vec (distinct (mapcat (fn [[_ recs]] (map :key recs)) bucket-recs)))
          fields (str (field-list spf) ",description,resolutiondate,comment")
          issue-by-key (into {} (map (juxt :key identity)) (fetch-issues-by-keys all-keys fields))
          candidates (vec (map-indexed
                           (fn [i k]
                             (let [gh (gh-by-key k)]
                               (enrich-retro spf sprint-index sid current-start added-set stage-ord
                                             (:_bucket gh) (inc i) {:gh gh :issue (issue-by-key k)})))
                           all-keys))
          buckets (into {} (map (fn [[b recs]] [b (mapv :key recs)]) bucket-recs))
          sums {:committed (est-sum (get-in report [:allIssuesEstimateSum :text]))
                :completed (est-sum (get-in report [:completedIssuesEstimateSum :text]))
                :spillover (est-sum (get-in report [:issuesNotCompletedEstimateSum :text]))}]
      {:board (dissoc ctx :cached-at)
       :sprint (sprint-meta sprint (java.time.Instant/now))
       :velocity (velocity-view (fetch-velocity board-id) sprint-index)
       :summary (retro-summary buckets sums (count added-set) candidates)
       :buckets buckets
       :focus (focus-set candidates)
       :candidates candidates
       :people (person-rollup candidates)})))

(defn -main [& raw]
  (let [{:keys [board scope refresh? plan? retro? sprint-sel]} (parse-cli raw)]
    (if (and board (not (re-matches #"\d+" (str board))))
      (binding [*out* *err*]
        (println (str "jira-board: '" board "' is not a numeric board id; "
                      "resolve a name/project to an id first, then pass it."))
        (System/exit 2))
      (let [ctx (board-context! board refresh?)]
        (prn
         (cond
           retro? (run-retro ctx sprint-sel)
           plan?  (run-plan ctx)
           :else  (run-backlog ctx scope)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
