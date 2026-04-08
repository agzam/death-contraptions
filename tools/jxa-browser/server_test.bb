#!/usr/bin/env bb
(ns jxa-browser-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

(load-file "server.bb")

(deftest wrap-result-ok-test
  (testing "ok value produces content text"
    (let [r (wrap-result {:ok "some text"})]
      (is (= "some text" (get-in r [:content 0 :text])))
      (is (not (contains? r :isError))))))

(deftest wrap-result-error-test
  (testing "error value produces isError content"
    (let [r (wrap-result {:error "something failed"})]
      (is (= "Error: something failed" (get-in r [:content 0 :text])))
      (is (true? (:isError r))))))

(deftest grep-lines-exact-match-test
  (let [text "line one\nline two\nline three\nline four\nline five"
        result (grep-lines text "three")]
    (is (str/includes? result "three"))
    (is (re-find #"»" result) "matched line should be marked")))

(deftest grep-lines-regex-test
  (let [text (str/join "\n" (map #(str "line-" %) (range 1 21)))
        result (grep-lines text "line-10" :ctx 1)]
    (is (re-find #"line-10" result))
    (is (re-find #"»" result) "matched line is marked")
    (is (str/includes? result "line-9") "context before")
    (is (str/includes? result "line-11") "context after")
    (is (not (str/includes? result "line-7")) "outside context window")))

(deftest grep-lines-no-match-test
  (let [text "alpha\nbeta\ngamma"
        result (grep-lines text "zzz")]
    (is (str/blank? result))))

(deftest grep-lines-context-window-test
  (testing "context lines surround matches"
    (let [lines (str/join "\n" (map #(str "line-" %) (range 1 21)))
          result (grep-lines lines "line-10" :ctx 2)]
      (is (re-find #"line-8" result) "context before")
      (is (re-find #"line-12" result) "context after")
      (is (not (str/includes? result "line-7")) "outside context window"))))

(deftest tool-schemas-valid-test
  (testing "all tools have name, description, inputSchema"
    (doseq [t tools]
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
    (is (= "jxa-browser" (get-in resp [:result :serverInfo :name])))))

(deftest handle-request-tools-list-test
  (let [resp (handle-request {"id" 2 "method" "tools/list" "params" {}})]
    (is (= 2 (:id resp)))
    (let [tool-list (get-in resp [:result :tools])]
      (is (= (count tools) (count tool-list)))
      (is (every? #(string? (:name %)) tool-list)))))
