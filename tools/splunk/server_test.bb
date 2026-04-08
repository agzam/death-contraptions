#!/usr/bin/env bb
(ns splunk-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]))

(load-file "server.bb")

;;; ---------- cookie-header ----------

(deftest cookie-header-test
  (is (= "splunkd_8443=SESS123; splunkweb_csrf_token_8443=CSRF456"
         (#'cookie-header {:session "SESS123" :csrf "CSRF456" :port "8443"}))))

;;; ---------- base-url ----------

(deftest base-url-test
  (is (= (str "https://" splunk-host "/en-US/splunkd/__raw/services/search/jobs")
         (#'base-url "/services/search/jobs"))))

;;; ---------- session-expired? ----------

(deftest session-expired?-test
  (testing "401 and 403 are expired"
    (is (#'session-expired? 401 ""))
    (is (#'session-expired? 403 "")))
  (testing "200 is not expired"
    (is (not (#'session-expired? 200 "ok"))))
  (testing "body containing auth keywords triggers expiry"
    (is (#'session-expired? 200 "Unauthorized access"))
    (is (#'session-expired? 200 "session has ended"))
    (is (#'session-expired? 200 "please login again")))
  (testing "nil body does not blow up"
    (is (not (#'session-expired? 200 nil)))))

;;; ---------- parse-ndjson ----------

(deftest parse-ndjson-test
  (testing "parses multiple JSON lines"
    (let [input "{\"a\":1}\n{\"b\":2}\n"
          parsed (parse-ndjson input)]
      (is (= 2 (count parsed)))
      (is (= 1 (:a (first parsed))))
      (is (= 2 (:b (second parsed))))))
  (testing "skips blank lines"
    (is (= 1 (count (parse-ndjson "\n{\"x\":1}\n\n")))))
  (testing "skips malformed lines"
    (is (= 1 (count (parse-ndjson "{\"ok\":1}\nnot-json\n")))))
  (testing "nil/empty input"
    (is (nil? (parse-ndjson nil)))
    (is (empty? (parse-ndjson "")))))

;;; ---------- format-search-results ----------

(deftest format-search-results-test
  (testing "empty results"
    (is (= "No results found." (format-search-results [] 10))))
  (testing "results with no :result key"
    (is (= "No results found." (format-search-results [{:preview true}] 10))))
  (testing "basic formatting"
    (let [results [{:result {:_time "2025-01-01" :_raw "error line" :host "web1"}}
                   {:result {:_time "2025-01-02" :_raw "warn line" :host "web2"}}]
          out (format-search-results results 10)]
      (is (re-find #"Result 1" out))
      (is (re-find #"Result 2" out))
      (is (re-find #"_time: 2025-01-01" out))
      (is (re-find #"_raw: error line" out))
      (is (re-find #"host: web1" out))))
  (testing "truncation message"
    (let [results (mapv (fn [i] {:result {:n i}}) (range 5))
          out (format-search-results results 3)]
      (is (re-find #"Showing 3 of 5" out))
      (is (re-find #"Result 3" out))
      (is (not (re-find #"Result 4" out)))))
  (testing "_time and _raw appear before other fields"
    (let [results [{:result {:zebra "z" :_raw "raw" :_time "t" :alpha "a"}}]
          out (format-search-results results 10)
          pri-pos (min (str/index-of out "_time:") (str/index-of out "_raw:"))
          a-pos (str/index-of out "alpha:")
          z-pos (str/index-of out "zebra:")]
      ;; Both _time and _raw appear before alphabetical fields
      (is (< pri-pos a-pos))
      (is (< a-pos z-pos)))))

;;; ---------- format-async-results ----------

(deftest format-async-results-test
  (testing "empty results"
    (is (= "No results found." (format-async-results [] 10))))
  (testing "basic formatting"
    (let [results [{:_time "2025-01-01" :_raw "error" :host "h1"}]
          out (format-async-results results 10)]
      (is (re-find #"Result 1" out))
      (is (re-find #"_time: 2025-01-01" out))
      (is (re-find #"host: h1" out))))
  (testing "truncation"
    (let [results (mapv (fn [i] {:n i}) (range 5))
          out (format-async-results results 2)]
      (is (re-find #"Showing 2 of 5" out)))))

;;; ---------- Tool schemas ----------

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
    (is (= "2024-11-05" (get-in resp [:result :protocolVersion])))
    (is (= server-info (get-in resp [:result :serverInfo])))))

(deftest handle-request-tools-list-test
  (let [resp (handle-request {"id" 2 "method" "tools/list" "params" {}})]
    (is (= 2 (:id resp)))
    (is (= (count tools) (count (get-in resp [:result :tools]))))))
