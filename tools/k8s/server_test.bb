#!/usr/bin/env bb
(ns k8s-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [clojure.set :as set]))

;; Load server definitions without triggering side effects.
;; We re-define the pure functions here by loading the relevant parts.
(load-file "server.bb")

(deftest truthy?-test
  (is (truthy? true))
  (is (truthy? "true"))
  (is (not (truthy? false)))
  (is (not (truthy? nil)))
  (is (not (truthy? "false"))))

(deftest kubectl-args-test
  (testing "empty params"
    (is (= [] (kubectl-args {}))))

  (testing "namespace only"
    (is (= ["-n" "kube-system"] (kubectl-args {"namespace" "kube-system"}))))

  (testing "all_namespaces boolean"
    (is (= ["-A"] (kubectl-args {"all_namespaces" true}))))

  (testing "all_namespaces string"
    (is (= ["-A"] (kubectl-args {"all_namespaces" "true"}))))

  (testing "combined flags"
    (is (= ["--kubeconfig" "/tmp/kc" "--context" "prod" "-n" "default"]
           (kubectl-args {"kubeconfig" "/tmp/kc"
                          "context"    "prod"
                          "namespace"  "default"})))))

(deftest pod-summary-test
  (let [pod {"metadata" {"name" "nginx-abc" "namespace" "default"
                         "creationTimestamp" "2025-01-01T00:00:00Z"}
             "spec"     {"nodeName" "node-1"}
             "status"   {"phase" "Running"
                         "containerStatuses"
                         [{"name" "nginx" "ready" true "restartCount" 3}
                          {"name" "sidecar" "ready" false "restartCount" 1}]}}
        s (pod-summary pod)]
    (is (= "nginx-abc" (:name s)))
    (is (= "default" (:namespace s)))
    (is (= "1/2" (:ready s)))
    (is (= 4 (:restarts s)))
    (is (= "node-1" (:node s))))

  (testing "waiting state reason overrides phase"
    (let [pod {"metadata" {"name" "crash" "namespace" "ns"}
               "status"   {"phase" "Running"
                           "containerStatuses"
                           [{"ready" false "restartCount" 5
                             "state" {"waiting" {"reason" "CrashLoopBackOff"}}}]}}]
      (is (= "CrashLoopBackOff" (:status (pod-summary pod)))))))

(deftest deployment-summary-test
  (let [dep {"metadata" {"name" "web" "namespace" "prod"
                         "creationTimestamp" "2025-01-01T00:00:00Z"}
             "spec"     {"replicas" 3}
             "status"   {"readyReplicas" 2 "updatedReplicas" 3 "availableReplicas" 2}}
        s (deployment-summary dep)]
    (is (= "web" (:name s)))
    (is (= "2/3" (:ready s)))
    (is (= 3 (:up-to-date s)))
    (is (= 2 (:available s)))))

(deftest service-summary-test
  (let [svc {"metadata" {"name" "api" "namespace" "prod"}
             "spec"     {"type" "ClusterIP" "clusterIP" "10.0.0.1"
                         "ports" [{"port" 80 "protocol" "TCP"}
                                  {"port" 443 "protocol" "TCP"}]}}
        s (service-summary svc)]
    (is (= "api" (:name s)))
    (is (= "ClusterIP" (:type s)))
    (is (= ["80/TCP" "443/TCP"] (:ports s)))))

(deftest generic-summary-test
  (let [r {"kind" "ConfigMap"
           "metadata" {"name" "cfg" "namespace" "ns"
                       "creationTimestamp" "2025-01-01T00:00:00Z"}}
        s (generic-summary r)]
    (is (= "cfg" (:name s)))
    (is (= "ConfigMap" (:kind s)))))

(deftest summarize-resource-dispatches-test
  (is (= "Running" (-> (summarize-resource
                        {"kind" "Pod"
                         "metadata" {"name" "p" "namespace" "n"}
                         "status" {"phase" "Running" "containerStatuses" []}})
                       :status)))
  (is (contains? (summarize-resource
                  {"kind" "Deployment"
                   "metadata" {"name" "d" "namespace" "n"}
                   "spec" {"replicas" 1}
                   "status" {}})
                 :up-to-date))
  (is (contains? (summarize-resource
                  {"kind" "Service"
                   "metadata" {"name" "s" "namespace" "n"}
                   "spec" {"ports" []}})
                 :type))
  (is (= "ConfigMap"
         (:kind (summarize-resource
                 {"kind" "ConfigMap"
                  "metadata" {"name" "c" "namespace" "n"}})))))

(deftest mask-secret-data-test
  (testing "secrets get redacted"
    (let [secret {"kind" "Secret"
                  "metadata" {"name" "s"}
                  "data" {"token" "abc123" "key" "xyz"}}
          masked (mask-secret-data secret)]
      (is (= "***REDACTED***" (get-in masked ["data" "token"])))
      (is (= "***REDACTED***" (get-in masked ["data" "key"])))))

  (testing "non-secrets pass through"
    (let [cm {"kind" "ConfigMap"
              "data" {"key" "value"}}]
      (is (= "value" (get-in (mask-secret-data cm) ["data" "key"]))))))

(deftest mask-secrets-in-list-test
  (let [lst {"kind" "List"
             "items" [{"kind" "Secret" "data" {"a" "1"}}
                      {"kind" "ConfigMap" "data" {"b" "2"}}]}
        masked (mask-secrets-in-list lst)]
    (is (= "***REDACTED***" (get-in masked ["items" 0 "data" "a"])))
    (is (= "2" (get-in masked ["items" 1 "data" "b"])))))

(deftest truncate-test
  (is (= "short" (truncate "short")))
  (let [long-str (apply str (repeat 60000 "x"))]
    (is (< (count (truncate long-str)) 51000))
    (is (re-find #"truncated" (truncate long-str)))))

(deftest text-result-test
  (let [r (text-result "hello")]
    (is (= "hello" (get-in r [:content 0 :text])))
    (is (not (contains? r :isError)))))

(deftest error-result-test
  (let [r (error-result "bad")]
    (is (= "bad" (get-in r [:content 0 :text])))
    (is (true? (:isError r)))))

(deftest json-result-test
  (let [r (json-result {"foo" "bar"})
        parsed (json/parse-string (get-in r [:content 0 :text]))]
    (is (= "bar" (get parsed "foo")))))

(deftest item-key-test
  (is (= "ns/pod-1" (item-key {"metadata" {"namespace" "ns" "name" "pod-1"}})))
  (is (= "/" (item-key {"metadata" {}}))))

(deftest watch-key-test
  (is (= "pods|ns|app=web||"
         (watch-key {"kind" "pods" "namespace" "ns" "label_selector" "app=web"}))))

(deftest diff-resources-test
  (let [prev [{"metadata" {"namespace" "n" "name" "a"} "spec" {"v" 1}}
              {"metadata" {"namespace" "n" "name" "b"} "spec" {"v" 2}}]
        curr [{"metadata" {"namespace" "n" "name" "b"} "spec" {"v" 99}}
              {"metadata" {"namespace" "n" "name" "c"} "spec" {"v" 3}}]
        diff (diff-resources prev curr)]
    (is (= 1 (count (:added diff))))
    (is (= "n/c" (item-key (first (:added diff)))))
    (is (= 1 (count (:removed diff))))
    (is (= "n/a" (item-key (first (:removed diff)))))
    (is (= 1 (count (:changed diff))))
    (is (= "n/b" (:key (first (:changed diff))))))

  (testing "no changes"
    (let [items [{"metadata" {"namespace" "n" "name" "x"}}]
          diff (diff-resources items items)]
      (is (empty? (:added diff)))
      (is (empty? (:removed diff)))
      (is (empty? (:changed diff))))))

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
    (is (= "2024-11-05" (get-in resp [:result :protocolVersion])))))

(deftest handle-request-tools-list-test
  (let [resp (handle-request {"id" 2 "method" "tools/list" "params" {}})]
    (is (= 2 (:id resp)))
    (is (= (count tools) (count (get-in resp [:result :tools]))))))
