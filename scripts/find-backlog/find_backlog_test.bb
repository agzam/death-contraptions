#!/usr/bin/env bb
(ns find-backlog-test
  (:require [clojure.test :refer [deftest is testing]]))

;; Load the script for its pure functions; the -main guard keeps it from
;; resolving/fetching on load.
(load-file "find-backlog.bb")

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
  (is (= {:board nil :scope "backlog" :refresh? false :plan? false} (parse-cli [])))
  (is (= {:board "3018" :scope "backlog" :refresh? false :plan? false} (parse-cli ["3018"])))
  (is (= {:board nil :scope "backlog" :refresh? true :plan? false} (parse-cli ["--refresh"])))
  (is (= {:board "2985" :scope "both" :refresh? false :plan? false} (parse-cli ["--scope" "both" "2985"])))
  (is (= {:board "2985" :scope "sprint" :refresh? true :plan? false} (parse-cli ["2985" "--scope" "sprint" "--refresh"])))
  (is (= {:board "3018" :scope "backlog" :refresh? false :plan? true} (parse-cli ["3018" "--plan"]))))

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
