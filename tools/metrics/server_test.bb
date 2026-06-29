#!/usr/bin/env bb
(ns metrics-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

(load-file "server.bb")

;;; ---------- parse-time ----------

(deftest parse-time-test
  (testing "now resolves to epoch seconds"
    (is (re-matches #"\d+" (parse-time "now")))
    (is (re-matches #"\d+" (parse-time ""))))
  (testing "relative offsets subtract from now"
    (let [before (quot (System/currentTimeMillis) 1000)
          got    (parse-long (parse-time "-1h"))
          after  (quot (System/currentTimeMillis) 1000)]
      (is (<= (- before 3600) got (- after 3600))))
    (let [before (quot (System/currentTimeMillis) 1000)
          got    (parse-long (parse-time "-15m"))
          after  (quot (System/currentTimeMillis) 1000)]
      (is (<= (- before 900) got (- after 900)))))
  (testing "absolute values pass through untouched"
    (is (= "1700000000" (parse-time "1700000000")))
    (is (= "2024-01-01T00:00:00Z" (parse-time "2024-01-01T00:00:00Z")))))

;;; ---------- fmt-metric ----------

(deftest fmt-metric-test
  (is (= "up{job=\"x\"}" (fmt-metric {:__name__ "up" :job "x"})))
  (is (= "up" (fmt-metric {:__name__ "up"})))
  (is (= "{job=\"x\"}" (fmt-metric {:job "x"}))))

;;; ---------- cookie expiry (expires_utc validity) ----------

(deftest cookie-live?-test
  (testing "session cookies (expires 0) count as live"
    (is (#'cookie-live? 0)))
  (testing "a future expiry is live"
    (is (#'cookie-live? (+ (#'chromium-now) 1000000000))))
  (testing "an ancient expiry is expired"
    (is (not (#'cookie-live? 1)))))

;;; ---------- host-matches? (which cookies to send) ----------

(deftest host-matches?-test
  (testing "exact host"
    (is (#'host-matches? "metrics.qlikcloud.io" "metrics.qlikcloud.io")))
  (testing "parent domain cookie reaches the subdomain"
    (is (#'host-matches? "metrics.qlikcloud.io" ".qlikcloud.io"))
    (is (#'host-matches? "qlikcloud.io" ".qlikcloud.io")))
  (testing "unrelated hosts do not match"
    (is (not (#'host-matches? "metrics.qlikcloud.io" "other.qlikcloud.io")))
    (is (not (#'host-matches? "metrics.qlikcloud.io" "evil.com")))))

;;; ---------- auth-failure? (retry decision) ----------

(deftest auth-failure?-test
  (is (#'auth-failure? 401))
  (is (#'auth-failure? 403))
  (is (not (#'auth-failure? 200)))
  (is (not (#'auth-failure? 500))))

;;; ---------- format-prom ----------

(deftest format-prom-test
  (testing "instant vector"
    (let [body {:status "success"
                :data   {:resultType "vector"
                         :result     [{:metric {:__name__ "up" :job "api"}
                                       :value  [123 "1"]}]}}]
      (is (str/includes? (format-prom body 10) "up{job=\"api\"} => 1"))))
  (testing "range matrix shows point count and last value"
    (let [body {:status "success"
                :data   {:resultType "matrix"
                         :result     [{:metric {:__name__ "rate"}
                                       :values [[1 "0.1"] [2 "0.2"]]}]}}
          out  (format-prom body 10)]
      (is (str/includes? out "2 pts"))
      (is (str/includes? out "last=0.2"))))
  (testing "empty result"
    (is (= "No data."
           (format-prom {:status "success" :data {:resultType "vector" :result []}} 10))))
  (testing "error surfaces"
    (is (str/includes? (format-prom {:status "error" :error "boom"} 10) "boom"))))

;;; ---------- format-datasources ----------

(deftest format-datasources-test
  (is (= "No datasources." (format-datasources nil)))
  (let [out (format-datasources [{:name "Prom" :type "prometheus" :uid "p1" :isDefault true}])]
    (is (str/includes? out "Prom"))
    (is (str/includes? out "uid=p1"))
    (is (str/includes? out "(default)"))))

;;; ---------- format-dashboards ----------

(deftest format-dashboards-test
  (is (= "No dashboards found." (format-dashboards [])))
  (let [out (format-dashboards [{:title "T" :uid "u1" :url "/d/u1" :folderTitle "F"}])]
    (is (str/includes? out "T"))
    (is (str/includes? out "uid=u1"))
    (is (str/includes? out "folder=F"))))

;;; ---------- format-dashboard-detail (panel PromQL extraction) ----------

(deftest format-dashboard-detail-test
  (let [body {:dashboard {:title      "D"
                          :templating {:list [{:name "ns"}]}
                          :panels     [{:type "timeseries" :title "P1" :targets [{:expr "up"}]}
                                       {:type "row" :title "r"}]}
              :meta      {:url "/d/x"}}
        out  (format-dashboard-detail body)]
    (is (str/includes? out "Dashboard: D"))
    (is (str/includes? out "P1"))
    (is (str/includes? out "up"))
    (is (str/includes? out "variables: ns"))
    (testing "row panels are dropped"
      (is (not (str/includes? out "[row]"))))))

;;; ---------- format-metric-names ----------

(deftest format-metric-names-test
  (is (= "No metric names found." (format-metric-names [])))
  (testing "sorted output"
    (is (= "a\nb" (format-metric-names ["b" "a"])))))

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

(deftest handle-request-unknown-tool-test
  (let [resp (handle-request {"id" 3 "method" "tools/call"
                              "params" {"name" "nope" "arguments" {}}})]
    (is (true? (get-in resp [:result :isError])))))
