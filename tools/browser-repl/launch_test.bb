#!/usr/bin/env bb
(ns browser-repl-launch-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.cli :as cli]))

(load-file "launch.bb")

(deftest nbb-cmd-test
  (testing "argv uses the tool's local nbb bin + nrepl-server :port"
    (is (= ["/tool/node_modules/.bin/nbb" "nrepl-server" ":port" "4321"]
           (nbb-cmd "/tool" 4321)))))

(deftest session-config-test
  (testing "fresh: only mode + headless?, nils dropped"
    (is (= {:mode :fresh :headless? false}
           (session-config {:mode :fresh :headless false}))))
  (testing "defaults: missing mode -> :fresh, missing headless -> false"
    (is (= {:mode :fresh :headless? false} (session-config {}))))
  (testing "persistent carries :user-data-dir"
    (is (= {:mode :persistent :headless? true :user-data-dir "/p"}
           (session-config {:mode :persistent :headless true :user-data-dir "/p"}))))
  (testing "attach carries :cdp-endpoint"
    (is (= {:mode :attach :headless? false :cdp-endpoint "http://127.0.0.1:9222"}
           (session-config {:mode :attach :cdp-endpoint "http://127.0.0.1:9222"})))))

(deftest init-forms-test
  (let [cfg                                    {:mode :fresh :headless? false}
        [require-f config-f start-f :as forms] (init-forms cfg)]
    (testing "three forms, in order: require, configure!, start!"
      (is (= 3 (count forms)))
      (is (= "(require (quote [browser-repl]))" require-f))
      (is (str/starts-with? config-f "(browser-repl/configure! "))
      (is (= "(browser-repl/start!)" start-f)))
    (testing "no load-file (nbb's SCI lacks it)"
      (is (not (str/includes? (str/join " " forms) "load-file"))))
    (testing "configure! arg round-trips to the config map"
      (let [inner (subs config-f
                        (count "(browser-repl/configure! ")
                        (dec (count config-f)))]
        (is (= cfg (edn/read-string inner)))))))

(deftest await-wrap-test
  (let [w (await-wrap "(browser-repl/start!)")]
    (testing "defs the pending flag, embeds the form, ends with the kicked keyword"
      (is (str/starts-with? w "(do (def *launch-pending* true)"))
      (is (str/includes? w "(browser-repl/start!)"))
      (is (str/includes? w "*launch-val*"))
      (is (str/ends-with? w ":launch/kicked)")))
    (testing "has an error branch that stashes ERR"
      (is (str/includes? w "ERR ")))))

(deftest free-port-test
  (testing "returns a usable TCP port number"
    (let [p (free-port)]
      (is (integer? p))
      (is (< 0 p 65536)))))

(deftest cli-parse-test
  (testing "defaults: fresh mode, headed"
    (let [o (cli/parse-opts [] {:spec cli-spec})]
      (is (= :fresh (:mode o)))
      (is (false? (:headless o)))))
  (testing "flags parse: mode keyword, headless boolean, port long"
    (let [o (cli/parse-opts ["--mode" "persistent" "--headless" "--port" "4321"]
                            {:spec cli-spec})]
      (is (= :persistent (:mode o)))
      (is (true? (:headless o)))
      (is (= 4321 (:port o)))))
  (testing "attach endpoint + port-file-dir parse as strings"
    (let [o (cli/parse-opts ["--mode" "attach"
                             "--cdp-endpoint" "http://127.0.0.1:9222"
                             "--port-file-dir" "/w"]
                            {:spec cli-spec})]
      (is (= "http://127.0.0.1:9222" (:cdp-endpoint o)))
      (is (= "/w" (:port-file-dir o))))))
