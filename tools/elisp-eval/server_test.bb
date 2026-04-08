#!/usr/bin/env bb
(ns elisp-eval-server-test
  (:require [clojure.test :refer [deftest is testing]]))

(load-file "server.bb")

(deftest truncate-test
  (testing "short string passes through"
    (is (= "hello" (truncate "hello"))))

  (testing "long string gets truncated with message"
    (let [long-str (apply str (repeat 60000 "x"))
          result   (truncate long-str)]
      (is (< (count result) (count long-str)))
      (is (re-find #"output truncated at 50000 chars" result))
      (is (re-find #"60000 total" result)))))

(deftest tool-schemas-valid-test
  (testing "all tools have name, description, inputSchema"
    (doseq [t [elisp-eval-tool screenshot-tool]]
      (is (string? (:name t)) (str "missing name in " t))
      (is (string? (:description t)) (str "missing description in " (:name t)))
      (is (map? (:inputSchema t)) (str "missing inputSchema in " (:name t)))
      (is (= "object" (get-in t [:inputSchema :type]))
          (str "inputSchema type not object in " (:name t))))))

(deftest handle-request-initialize-test
  (let [resp (handle-request {"id" 1 "method" "initialize" "params" {}})]
    (is (= 1 (:id resp)))
    (is (= "2.0" (:jsonrpc resp)))
    (is (= "2024-11-05" (get-in resp [:result :protocolVersion])))
    (is (= "elisp-eval" (get-in resp [:result :serverInfo :name])))))

(deftest handle-request-tools-list-test
  (let [resp (handle-request {"id" 2 "method" "tools/list" "params" {}})]
    (is (= 2 (:id resp)))
    (let [tool-list (get-in resp [:result :tools])]
      (is (= 2 (count tool-list)))
      (is (= #{"elisp-eval" "emacs-screenshot"}
             (set (map :name tool-list)))))))

(deftest screenshot-error-test
  (testing "returns error content map"
    (let [result (screenshot-error "something broke")]
      (is (= "something broke" (get-in result [:content 0 :text])))
      (is (true? (:isError result))))))
