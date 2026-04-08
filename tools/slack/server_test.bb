#!/usr/bin/env bb
(ns slack-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]))

;; Load server definitions.
(load-file "server.bb")

;;; ---------- parse-slack-url ----------

(deftest parse-slack-url-channel-message-test
  (testing "standard channel message URL"
    (let [url "https://myteam.slack.com/archives/C123ABC/p1234567890123456"
          res (parse-slack-url url)]
      (is (= "myteam" (:workspace res)))
      (is (= "C123ABC" (:channel-id res)))
      (is (= "1234567890.123456" (:ts res)))
      (is (nil? (:thread-ts res)))
      (is (= "myteam.slack.com" (:host res))))))

(deftest parse-slack-url-thread-reply-test
  (testing "thread reply URL with thread_ts param"
    (let [url "https://acme.slack.com/archives/C999XYZ/p1700000000111222?thread_ts=1700000000.000100&cid=C999XYZ"
          res (parse-slack-url url)]
      (is (= "acme" (:workspace res)))
      (is (= "C999XYZ" (:channel-id res)))
      (is (= "1700000000.111222" (:ts res)))
      (is (= "1700000000.000100" (:thread-ts res))))))

(deftest parse-slack-url-dm-test
  (testing "DM message URL (D-prefixed channel)"
    (let [url "https://team.slack.com/archives/D0ABCDEF/p1609459200000300"
          res (parse-slack-url url)]
      (is (= "team" (:workspace res)))
      (is (= "D0ABCDEF" (:channel-id res)))
      (is (= "1609459200.000300" (:ts res))))))

(deftest parse-slack-url-invalid-test
  (testing "non-Slack URL returns nil"
    (is (nil? (parse-slack-url "https://example.com/foo")))
    (is (nil? (parse-slack-url "")))))

;;; ---------- make-permalink ----------

(deftest make-permalink-test
  (testing "constructs permalink from parts"
    (is (= "https://myteam.slack.com/archives/C123ABC/p1234567890123456"
           (make-permalink "myteam.slack.com" "C123ABC" "1234567890.123456")))))

(deftest make-permalink-round-trip-test
  (testing "parse-slack-url output feeds make-permalink for round-trip"
    (let [url    "https://foo.slack.com/archives/CABC123/p1600000000654321"
          parsed (parse-slack-url url)
          rebuilt (make-permalink (:host parsed) (:channel-id parsed) (:ts parsed))]
      (is (= url rebuilt)))))

;;; ---------- format-ts ----------

(deftest format-ts-test
  (testing "epoch string to formatted date"
    (let [result (format-ts "1700000000")]
      ;; 2023-11-14 in UTC
      (is (some? result))
      (is (re-find #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$" result))))

  (testing "nil input returns nil"
    (is (nil? (format-ts nil))))

  (testing "non-numeric returns nil"
    (is (nil? (format-ts "not-a-number")))))

;;; ---------- normalize-host ----------

(deftest normalize-host-test
  (testing "bare workspace name gets .slack.com appended"
    (is (= "myteam.slack.com" (normalize-host "myteam"))))

  (testing "already-qualified host passes through"
    (is (= "myteam.slack.com" (normalize-host "myteam.slack.com")))))

;;; ---------- tool schemas ----------

(deftest tool-schemas-valid-test
  (testing "all tools have name, description, inputSchema with type object"
    (doseq [t tools]
      (is (string? (:name t)) (str "missing name in " t))
      (is (string? (:description t)) (str "missing description in " (:name t)))
      (is (map? (:inputSchema t)) (str "missing inputSchema in " (:name t)))
      (is (= "object" (get-in t [:inputSchema :type]))
          (str "inputSchema type not object in " (:name t))))))

;;; ---------- handle-request ----------

(deftest handle-request-initialize-test
  (let [resp (handle-request {"id" 1 "method" "initialize" "params" {}})]
    (is (= 1 (:id resp)))
    (is (= "2.0" (:jsonrpc resp)))
    (is (= "2024-11-05" (get-in resp [:result :protocolVersion])))))

(deftest handle-request-tools-list-test
  (let [resp (handle-request {"id" 2 "method" "tools/list" "params" {}})]
    (is (= 2 (:id resp)))
    (is (= (count tools) (count (get-in resp [:result :tools]))))))
