#!/usr/bin/env bb
(ns jira-board-test
  (:require [clojure.test :refer [deftest is testing]]))

;; Load the script for its pure functions; the -main guard keeps it from
;; resolving/fetching on load.
(load-file "jira-board.bb")

(def sample-config
  {:name "SAC Pipeline (Pod 2)"
   :type "scrum"
   :filter {:id "17148"}
   :estimation {:field {:displayName "Story Points" :fieldId "customfield_10034"}}
   :columnConfig
   {:columns [{:name "To Do"       :statuses [{:id "10000"}]}
              {:name "Blocked"     :statuses [{:id "10065"} {:id "10046"}]}
              {:name "In Progress" :statuses [{:id "3"}]}
              {:name "Done"        :statuses [{:id "10001"}]}]}})

(def issue
  {:key "SAC-1"
   :fields {:assignee nil
            :status {:id "10000" :name "New" :statusCategory {:key "new"}}
            :issuetype {:name "Task"}
            :priority {:name "3 - Medium"}
            :components [{:name "Sources"}]
            :labels ["performance"]
            :parent {:key "SAC-100"}
            :issuelinks [{:type {:name "Blocks"}
                          :outwardIssue {:key "SAC-2"
                                         :fields {:status {:statusCategory {:key "done"}}}}}]
            :created "2026-04-22T10:00:00.000Z"
            :updated "2026-06-04T10:00:00.000Z"
            :summary "Do a thing"
            :customfield_10034 3}})

(def issue-assigned (assoc-in issue [:fields :assignee] {:displayName "Someone"}))
(def issue-blocked  (assoc-in issue [:fields :status] {:id "10065" :name "Blocked" :statusCategory {:key "new"}}))
(def issue-done     (assoc-in issue [:fields :status] {:id "10001" :name "Done" :statusCategory {:key "done"}}))

;; ---------- cli ----------

(deftest parse-cli-test
  (let [base {:board nil :scope "backlog" :refresh? false :plan? false :retro? false :sprint-sel nil}]
    (is (= base (parse-cli [])))
    (is (= (assoc base :board "3018") (parse-cli ["3018"])))
    (is (= (assoc base :refresh? true) (parse-cli ["--refresh"])))
    (is (= (assoc base :board "2985" :scope "both") (parse-cli ["--scope" "both" "2985"])))
    (is (= (assoc base :board "2985" :scope "sprint" :refresh? true) (parse-cli ["2985" "--scope" "sprint" "--refresh"])))
    (is (= (assoc base :board "3018" :plan? true) (parse-cli ["3018" "--plan"])))
    (is (= (assoc base :board "3018" :retro? true) (parse-cli ["3018" "--retro"])))
    (is (= (assoc base :retro? true :sprint-sel "last") (parse-cli ["--retro" "--sprint" "last"])))
    (is (= (assoc base :board "2985" :retro? true :sprint-sel "21217")
           (parse-cli ["2985" "--retro" "--sprint" "21217"])))))

;; ---------- board context ----------

(deftest pick-board-precedence-test
  (testing "explicit arg wins; default resolver not consulted"
    (let [called? (atom false)]
      (is (= ["2985" false] (pick-board "2985" "999" "3018" #(do (reset! called? true) "x"))))
      (is (false? @called?))))
  (testing "env beats cached default"
    (is (= ["999" false] (pick-board nil "999" "3018" (constantly "x")))))
  (testing "cached default used before resolving; blanks skipped"
    (is (= ["3018" false] (pick-board "   " nil "3018" (constantly "x")))))
  (testing "resolver only when nothing else supplies an id, and flagged to cache"
    (is (= ["7" true] (pick-board nil nil nil (constantly "7"))))))

(deftest hold-status-ids-test
  (is (= #{"10065" "10046"} (set (hold-status-ids sample-config))))
  (is (= [] (hold-status-ids {:columnConfig {:columns [{:name "To Do" :statuses [{:id "1"}]}]}}))))

(deftest derive-board-context-test
  (let [ctx (derive-board-context "3018" sample-config)]
    (is (= "3018" (:board-id ctx)))
    (is (= "SAC Pipeline (Pod 2)" (:name ctx)))
    (is (= "17148" (:filter-id ctx)))
    (is (= "customfield_10034" (:story-points-field ctx)))
    (is (= #{"10065" "10046"} (set (:blocked-status-ids ctx))))
    (is (= ["To Do" "Blocked" "In Progress" "Done"] (:columns ctx)))))

;; ---------- candidates ----------

(deftest pickable?-test
  (let [blocked #{"10065" "10046"}]
    (is (true?  (boolean (pickable? blocked issue))))
    (is (false? (boolean (pickable? blocked issue-assigned))))
    (is (false? (boolean (pickable? blocked issue-blocked))))
    (is (false? (boolean (pickable? blocked issue-done))))))

(deftest project-test
  (let [p (project "customfield_10034" 5 issue)]
    (is (= "SAC-1" (:key p)))
    (is (= 5 (:rank p)))
    (is (= "Task" (:type p)))
    (is (= "3 - Medium" (:priority p)))
    (is (= ["Sources"] (:components p)))
    (is (= ["performance"] (:labels p)))
    (is (= "SAC-100" (:parent p)))
    (is (= [{:rel "Blocks" :key "SAC-2" :cat "done"}] (:links p)))
    (is (= "2026-04-22" (:created p)))
    (is (= "2026-06-04" (:updated p)))
    (is (= 3 (:points p)))
    (is (= "Do a thing" (:summary p)))))

(deftest pickable-candidates-test
  (testing "filters to pickable and preserves full-order rank"
    (let [out (pickable-candidates "customfield_10034" #{"10065" "10046"}
                                   [issue-assigned issue issue-blocked])]
      (is (= 1 (count out)))
      (is (= "SAC-1" (:key (first out))))
      (is (= 2 (:rank (first out)))))))

(deftest active?-test
  (is (true?  (boolean (active? issue))))
  (is (false? (boolean (active? issue-done)))))

(deftest plan-candidates-test
  (testing "keeps every issue in rank order, tags sprint membership, adds assignee + description"
    (let [a (assoc issue-assigned :key "SAC-1")
          b (assoc (assoc-in issue [:fields :description] "hello") :key "SAC-2")
          out (plan-candidates "customfield_10034" #{"SAC-1"} [a b])]
      (is (= 2 (count out)))
      (is (= [1 2] (mapv :rank out)))
      (is (true?  (:sprint (first out))))
      (is (false? (:sprint (second out))))
      (is (= "Someone" (:assignee (first out))))
      (is (nil? (:assignee (second out))))
      (is (= "hello" (:description (second out))))
      (is (= "Do a thing" (:summary (first out))))))
  (testing "clips long descriptions and strips carriage returns"
    (let [long-desc (apply str (repeat 2000 "x"))
          out (plan-candidates "customfield_10034" #{}
                               [(assoc-in issue [:fields :description] (str "a\r\nb" long-desc))])]
      (is (< (count (:description (first out))) 1700))
      (is (not (.contains (:description (first out)) "\r"))))))

;; ---------- retro ----------

(def sprints
  [{:id 100 :name "beat 11" :state "closed" :startDate "2026-05-01T00:00:00.000Z" :endDate "2026-05-14T00:00:00.000Z"}
   {:id 101 :name "beat 12" :state "closed" :startDate "2026-05-15T00:00:00.000Z" :endDate "2026-05-28T00:00:00.000Z"}
   {:id 102 :name "beat 13" :state "active" :startDate "2026-05-29T00:00:00.000Z" :endDate "2026-06-11T00:00:00.000Z" :goal ""}])

(deftest select-sprint-test
  (testing "default and \"active\" pick the active sprint"
    (is (= 102 (:id (select-sprint sprints nil))))
    (is (= 102 (:id (select-sprint sprints "active")))))
  (testing "\"last\" picks the most recent closed sprint"
    (is (= 101 (:id (select-sprint sprints "last")))))
  (testing "a numeric selector picks that sprint by id"
    (is (= 100 (:id (select-sprint sprints "100"))))
    (is (nil? (select-sprint sprints "999"))))
  (testing "with no active sprint, the default falls back to the most recent closed"
    (is (= 101 (:id (select-sprint (butlast sprints) nil))))))

(deftest sprint-meta-test
  (testing "active sprint mid-flight: elapsed + remaining split the total"
    (let [m (sprint-meta (nth sprints 2) (java.time.Instant/parse "2026-06-01T00:00:00.000Z"))]
      (is (= "beat 13" (:name m)))
      (is (= "active" (:state m)))
      (is (nil? (:goal m)))                 ; "" normalizes to nil
      (is (= "2026-05-29" (:start m)))
      (is (= 13 (:days-total m)))
      (is (= 3 (:days-elapsed m)))
      (is (= 10 (:days-remaining m)))
      (is (false? (:ended? m)))))
  (testing "closed/past sprint: elapsed caps at total, no remaining, ended"
    (let [m (sprint-meta (assoc (nth sprints 2) :state "closed")
                         (java.time.Instant/parse "2026-07-01T00:00:00.000Z"))]
      (is (= 13 (:days-elapsed m)))
      (is (nil? (:days-remaining m)))
      (is (true? (:ended? m)))))
  (testing "active but past its end date (retro run after the buzzer): ended, none remaining"
    (let [m (sprint-meta (nth sprints 2) (java.time.Instant/parse "2026-06-20T00:00:00.000Z"))]
      (is (= "active" (:state m)))
      (is (true? (:ended? m)))
      (is (= 0 (:days-remaining m))))))

(def sprint-index
  {"100" {:name "beat 11" :start "2026-05-01T00:00:00.000Z"}
   "101" {:name "beat 12" :start "2026-05-15T00:00:00.000Z"}
   "102" {:name "beat 13" :start "2026-05-29T00:00:00.000Z"}})

(deftest carryover-info-test
  (testing "a prior (earlier-starting) sprint makes it carryover, named"
    (is (= {:carryover? true :carryover-from ["beat 12"]}
           (carryover-info [102 101] 102 "2026-05-29T00:00:00.000Z" sprint-index))))
  (testing "only the current sprint => not carryover"
    (is (= {:carryover? false :carryover-from []}
           (carryover-info [102] 102 "2026-05-29T00:00:00.000Z" sprint-index))))
  (testing "a later sprint (started after current) does not count"
    (is (false? (:carryover? (carryover-info [102 100] 100 "2026-05-01T00:00:00.000Z" sprint-index))))))

(def histories
  [{:created "2026-05-30T09:00:00.000Z" :author {:displayName "Ann"}
    :items [{:field "status" :fromString "To Do" :toString "In Progress"}
            {:field "assignee" :fromString nil :toString "Ann"}]}
   {:created "2026-06-02T09:00:00.000Z" :author {:displayName "Ann"}
    :items [{:field "status" :fromString "In Progress" :toString "Code Review"}]}
   {:created "2026-06-03T09:00:00.000Z" :author {:displayName "Bob"}
    :items [{:field "status" :fromString "Code Review" :toString "In Progress"} ; bounce back = rework
            {:field "assignee" :fromString "Ann" :toString "Bob"}]}             ; handoff
   {:created "2026-06-04T09:00:00.000Z" :author {:displayName "Bob"}
    :items [{:field "status" :fromString "In Progress" :toString "Blocked"}]}   ; into hold lane, not rework
   {:created "2026-06-05T09:00:00.000Z" :author {:displayName "Bob"}
    :items [{:field "Flagged" :fromString nil :toString "Impediment"}]}
   {:created "2026-06-06T09:00:00.000Z" :author {:displayName "Bob"}
    :items [{:field "Flagged" :fromString "Impediment" :toString ""}]}])        ; flag cleared

(deftest status-timeline-test
  (let [tl (status-timeline histories)]
    (is (= 4 (count tl)))                                   ; only status items
    (is (= ["To Do" "In Progress" "Code Review" "In Progress"] (mapv :from tl)))
    (is (= (mapv :at tl) (vec (sort (mapv :at tl)))))       ; sorted oldest-first
    (is (= "Bob" (:by (nth tl 2))))))

(deftest rework-count-test
  (let [ord (stage-order ["To Do" "Blocked" "In Progress" "Code Review" "Validation" "Done"])
        tl (status-timeline histories)]
    (is (= {"to do" 0 "blocked" 1 "in progress" 2 "code review" 3 "validation" 4 "done" 5} ord))
    ;; Code Review -> In Progress counts; In Progress -> Blocked (hold lane) does not.
    (is (= 1 (rework-count tl ord)))))

(deftest handoffs-test
  (let [hs (handoffs histories)]
    (is (= [{:from nil :to "Ann" :at "2026-05-30T09:00:00.000Z"}
            {:from "Ann" :to "Bob" :at "2026-06-03T09:00:00.000Z"}] hs))))

(deftest flagged-count-test
  (is (= 1 (flagged-count histories)))                      ; one raise, ignoring the clear
  (is (= 0 (flagged-count []))))

(deftest project-comments-test
  (testing "keeps total, clips bodies"
    (let [pc (project-comments {:total 2 :comments [{:author {:displayName "Ann"} :created "2026-06-01T09:00:00.000Z" :body (apply str (repeat 600 "x"))}
                                                    {:author {:displayName "Bob"} :created "2026-06-02T09:00:00.000Z" :body "short"}]})]
      (is (= 2 (:count pc)))
      (is (= 2 (count (:items pc))))
      (is (= "Ann" (:who (first (:items pc)))))
      (is (= "2026-06-01" (:when (first (:items pc)))))
      (is (< (count (:body (first (:items pc)))) 420))))
  (testing "caps the number of comments to the most recent"
    (let [many (mapv (fn [i] {:author {:displayName "X"} :created "2026-06-01T09:00:00.000Z" :body (str i)})
                     (range 30))
          pc (project-comments {:total 30 :comments many})]
      (is (= 30 (:count pc)))
      (is (= 15 (count (:items pc))))
      (is (= "29" (:body (last (:items pc))))))))            ; kept the newest

(def gh-record
  {:key "SAC-1" :typeName "Task" :priorityName "3 - Medium" :status {:name "Done"}
   :summary "Do the thing" :assigneeName "Ann" :sprintIds [102 101]
   :epic "EP-9" :epicField {:summary "the epic"}
   :estimateStatistic {:statFieldValue {:value 3}}})

(def jql-issue
  {:key "SAC-1"
   :fields {:assignee {:displayName "Bob"}
            :status {:name "Done" :statusCategory {:key "done"}}
            :issuetype {:name "Task"} :priority {:name "3 - Medium"}
            :components [{:name "Sources"}] :labels [] :parent nil :issuelinks []
            :created "2026-05-29T00:00:00.000Z" :updated "2026-06-06T00:00:00.000Z"
            :resolutiondate "2026-06-05T12:00:00.000Z"
            :description "desc"
            :comment {:total 1 :comments [{:author {:displayName "Ann"} :created "2026-06-01T09:00:00.000Z" :body "hey"}]}
            :customfield_10034 3
            :changelog nil}
   :changelog {:histories histories}})

(deftest enrich-retro-test
  (let [ord (stage-order ["To Do" "Blocked" "In Progress" "Code Review" "Validation" "Done"])
        c (enrich-retro "customfield_10034" sprint-index 102 "2026-05-29T00:00:00.000Z"
                        #{"SAC-1"} ord :completed 1 {:gh gh-record :issue jql-issue})]
    (testing "base ticket fields come through project; rank is dropped"
      (is (= "SAC-1" (:key c)))
      (is (= "Task" (:type c)))
      (is (= ["Sources"] (:components c)))
      (is (= 3 (:points c)))
      (is (not (contains? c :rank))))
    (testing "retro signals"
      (is (= :completed (:bucket c)))
      (is (= "Bob" (:assignee c)))                          ; live field wins over the report snapshot
      (is (true? (:carryover? c)))
      (is (= ["beat 12"] (:carryover-from c)))
      (is (true? (:added-during? c)))
      (is (= "2026-06-05" (:resolved c)))
      (is (= 1 (:rework c)))
      (is (= 2 (count (:handoffs c))))
      (is (= 1 (:flags c)))
      (is (= "the epic" (:epic-summary c)))
      (is (= 1 (:count (:comments c)))))))

(deftest enrich-retro-missing-issue-test
  (testing "an issue absent from the by-key fetch still projects from the report record"
    (let [ord (stage-order ["To Do" "In Progress" "Done"])
          c (enrich-retro "customfield_10034" sprint-index 102 "2026-05-29T00:00:00.000Z"
                          #{} ord :punted 1 {:gh gh-record :issue nil})]
      (is (= "SAC-1" (:key c)))
      (is (= "Task" (:type c)))
      (is (= "Done" (:status c)))
      (is (= :punted (:bucket c)))
      (is (= "Ann" (:assignee c)))                          ; falls back to the report's assigneeName
      (is (= [] (:timeline c)))
      (is (= 0 (:rework c))))))

(deftest person-rollup-test
  (let [cands [{:key "A" :assignee "Ann" :bucket :completed :points 3 :carryover? true :added-during? false :rework 1 :handoffs [{}] :flags 0}
               {:key "B" :assignee "Ann" :bucket :not-completed :points 2 :carryover? false :added-during? true :rework 0 :handoffs [] :flags 1}
               {:key "C" :assignee "Bob" :bucket :completed :points 5 :carryover? false :added-during? false :rework 0 :handoffs [] :flags 0}
               {:key "D" :assignee nil :bucket :completed :points 1 :carryover? false :added-during? false :rework 0 :handoffs [] :flags 0}]
        people (person-rollup cands)]
    (is (= ["Bob" "Ann"] (mapv :name people)))              ; sorted by completed points desc
    (let [ann (first (filter #(= "Ann" (:name %)) people))]
      (is (= ["A"] (:completed ann)))
      (is (= 3 (:completed-pts ann)))
      (is (= ["B"] (:spillover ann)))
      (is (= 2 (:spillover-pts ann)))
      (is (= ["A"] (:carryover ann)))
      (is (= ["B"] (:added ann)))
      (is (= 1 (:rework ann)))
      (is (= 1 (:handoffs ann)))
      (is (= 1 (:flags ann))))
    (is (nil? (some #(nil? (:name %)) people)))))            ; unassigned excluded

(deftest velocity-view-test
  (let [entries {:100 {:estimated {:value 13} :completed {:value 8}}
                 :102 {:estimated {:value 6} :completed {:value 4}}
                 :999 {:estimated {:value 9} :completed {:value 9}}} ; not on this board -> dropped
        v (velocity-view entries sprint-index)]
    (is (= [{:sprint "beat 11" :estimated 13 :completed 8}
            {:sprint "beat 13" :estimated 6 :completed 4}] v))))

(deftest est-sum-test
  (is (= "5.0" (est-sum "5.0")))
  (is (nil? (est-sum "null")))                             ; greenhopper's empty-sum sentinel
  (is (nil? (est-sum nil))))

(deftest retro-summary-test
  (let [buckets {:completed ["A" "B"] :not-completed ["C"] :punted ["D" "E"] :completed-elsewhere []}
        sums {:committed "24.0" :completed "5.0" :spillover "16.0"}
        cands [{:key "A" :points 3 :carryover? true} {:key "B" :carryover? false}
               {:key "C" :points 2 :carryover? true} {:key "D" :points 5 :carryover? false}]
        s (retro-summary buckets sums 4 cands)]
    (is (= 3 (:committed-count s)))                          ; completed + not-completed
    (is (= 2 (:completed-count s)))
    (is (= 1 (:spillover-count s)))
    (is (= 2 (:punted-count s)))
    (is (= 4 (:added-during-count s)))
    (is (= 2 (:carryover-count s)))
    (is (= 2 (:pointed-count s)))                            ; A + C; B unpointed, D is punted (not committed)
    (is (= sums (:points s)))))

(deftest focus-set-test
  (let [cands [{:key "DONE1" :bucket :completed :points 3 :carryover? false :rework 0 :flags 0 :handoffs []}
               {:key "DONE2" :bucket :completed :points 5 :carryover? true :rework 0 :flags 0 :handoffs [{:from nil :to "A"}]}
               {:key "REWORK" :bucket :completed :points nil :carryover? false :rework 1 :flags 0 :handoffs []}
               {:key "FLAG" :bucket :completed :points nil :carryover? false :rework 0 :flags 2 :handoffs []}
               {:key "REASSIGN" :bucket :completed :carryover? false :rework 0 :flags 0 :handoffs [{:from "A" :to "B"}]}
               {:key "INITASSIGN" :bucket :completed :carryover? false :rework 0 :flags 0 :handoffs [{:from nil :to "A"}]}
               {:key "SPILL1" :bucket :not-completed :points 8}
               {:key "PUNT1" :bucket :punted}
               {:key "ELSE" :bucket :completed-elsewhere}]
        f (focus-set cands)]
    (is (= ["SPILL1"] (:spillover f)))
    (is (= ["PUNT1"] (:punted f)))
    (testing "friction = completed with rework, a flag, or a real (non-initial) reassignment"
      (is (= ["REWORK" "FLAG" "REASSIGN"] (:friction f))))
    (is (= ["DONE2"] (:carryover-wins f)))
    (testing "headline = pointed completions, biggest first"
      (is (= ["DONE2" "DONE1"] (:headline f))))))
