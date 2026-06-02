#!/usr/bin/env bb
(ns playwright-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

(load-file "server.bb")

(deftest build-args-own-profile-test
  (testing "own-profile emits pinned package + --user-data-dir, no attach"
    (let [args (build-args {:version "0.0.41"
                            :mode :own-profile
                            :user-data-dir "/tmp/p"
                            :viewport {:w 1440 :h 900}
                            :allowed-origins ["https://*.pte.qlikdev.com"
                                              "https://*.qlik-stage.com"]
                            :output-dir "/tmp/out"
                            :save-trace? true})]
      (is (= ["npx" "-y" "@playwright/mcp@0.0.41"
              "--user-data-dir" "/tmp/p"
              "--viewport-size" "1440,900"
              "--allowed-origins" "https://*.pte.qlikdev.com;https://*.qlik-stage.com"
              "--output-dir" "/tmp/out"
              "--save-trace"]
             args))
      (is (not (some #{"--cdp-endpoint"} args)) "own-profile must not attach"))))

(deftest build-args-attach-test
  (testing "attach emits --cdp-endpoint and drops the profile dir"
    (let [args (build-args {:version "0.0.41"
                            :mode :attach
                            :cdp-endpoint "http://127.0.0.1:9222"
                            :user-data-dir "/tmp/ignored"})
          i (.indexOf args "--cdp-endpoint")]
      (is (nat-int? i))
      (is (= "http://127.0.0.1:9222" (nth args (inc i))))
      (is (not (some #{"--user-data-dir"} args)) "attach must not pass a profile dir"))))

(deftest build-args-home-expansion-test
  (testing "a leading ~ in paths is expanded from $HOME"
    (let [args (build-args {:user-data-dir "~/p"})
          i (.indexOf args "--user-data-dir")
          v (nth args (inc i))]
      (is (str/starts-with? v (System/getenv "HOME")))
      (is (not (str/includes? v "~"))))))

(deftest build-args-extra-args-test
  (testing "extra-args are appended verbatim, stringified"
    (let [args (build-args {:extra-args ["--no-sandbox" "--device" "iPhone 15"]})]
      (is (= ["--no-sandbox" "--device" "iPhone 15"] (vec (take-last 3 args)))))))

(deftest build-args-defaults-test
  (testing "empty config still yields safe defaults: latest pkg, headed, scoped origins, persistent profile"
    (let [args (build-args {})]
      (is (= "@playwright/mcp@latest" (nth args 2)))
      (is (not (some #{"--headless"} args)))
      (is (some #{"--allowed-origins"} args) "origin allowlist applied by default")
      (is (some #{"--user-data-dir"} args) "persistent profile by default"))))
