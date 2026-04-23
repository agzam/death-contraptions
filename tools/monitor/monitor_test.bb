#!/usr/bin/env bb
(ns monitor-test
  (:require [clojure.test :refer [deftest is testing]]))

;; Load the script for its pure functions; the -main guard keeps run!/preflight!
;; from firing on load.
(load-file "monitor.bb")

(deftest parse-args-regex
  (is (= {:source "kubectl get events -w"
          :filter-type :regex
          :filter-expr "ERROR"
          :live false
          :max-runtime nil
          :max-matches 1
          :digest-interval nil
          :digest-top 20}
         (parse-args ["--source" "kubectl get events -w" "--regex" "ERROR"]))))

(deftest parse-args-grep
  (is (= {:source "tail -F log"
          :filter-type :grep
          :filter-expr "timeout"
          :live false
          :max-runtime nil
          :max-matches 1
          :digest-interval nil
          :digest-top 20}
         (parse-args ["--source" "tail -F log" "--grep" "timeout"]))))

(deftest parse-args-jq
  (is (= {:source "kubectl get events -o json -w"
          :filter-type :jq
          :filter-expr "select(.type == \"Warning\")"
          :live false
          :max-runtime nil
          :max-matches 1
          :digest-interval nil
          :digest-top 20}
         (parse-args ["--source" "kubectl get events -o json -w"
                      "--jq" "select(.type == \"Warning\")"]))))

(deftest parse-args-live
  (testing "--live flips max-matches default to unlimited"
    (let [spec (parse-args ["--source" "x" "--regex" "y" "--live"])]
      (is (true? (:live spec)))
      (is (nil? (:max-matches spec)))))
  (testing "--live plus explicit --max-matches respects the explicit value"
    (let [spec (parse-args ["--source" "x" "--regex" "y" "--live" "--max-matches" "3"])]
      (is (true? (:live spec)))
      (is (= 3 (:max-matches spec)))))
  (testing "without --live, live is false"
    (let [spec (parse-args ["--source" "x" "--regex" "y"])]
      (is (false? (:live spec)))
      (is (= 1 (:max-matches spec))))))

(deftest elisp-string-escapes
  (testing "plain string"
    (is (= "\"hello\"" (elisp-string "hello"))))
  (testing "embedded double quote"
    (is (= "\"he said \\\"hi\\\"\"" (elisp-string "he said \"hi\""))))
  (testing "backslash"
    (is (= "\"back\\\\slash\"" (elisp-string "back\\slash"))))
  (testing "newline becomes \\n escape"
    (is (= "\"a\\nb\"" (elisp-string "a\nb")))))

(deftest parse-args-digest
  (testing "--digest-interval accepts positive int"
    (let [spec (parse-args ["--source" "x" "--regex" "y" "--digest-interval" "120"])]
      (is (= 120 (:digest-interval spec)))
      (is (= 20 (:digest-top spec)))))
  (testing "--digest-interval makes filter optional"
    (let [spec (parse-args ["--source" "x" "--digest-interval" "60"])]
      (is (= 60 (:digest-interval spec)))
      (is (nil? (:filter-type spec)))
      (is (nil? (:filter-expr spec)))))
  (testing "--digest-top overrides default"
    (let [spec (parse-args ["--source" "x" "--digest-interval" "60" "--digest-top" "5"])]
      (is (= 5 (:digest-top spec)))))
  (testing "--digest-interval flips :max-matches default to unlimited"
    (let [spec (parse-args ["--source" "x" "--digest-interval" "30"])]
      (is (nil? (:max-matches spec)))))
  (testing "without --digest-interval, filter is still required"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one of --jq"
          (parse-args ["--source" "x"]))))
  (testing "--digest-interval rejects non-positive"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--digest-interval must be a positive integer"
          (parse-args ["--source" "x" "--digest-interval" "0"])))))

(deftest normalize-line-transformations
  (testing "age column collapses"
    (is (= "default <AGE> Warning" (normalize-line "default 60m Warning")))
    (is (= "kyverno <AGE> Normal"  (normalize-line "kyverno 5s Normal"))))
  (testing "long digit runs collapse"
    (is (= "cronjob-<N>-foo" (normalize-line "cronjob-29616080-foo"))))
  (testing "hash suffix collapses at end of token"
    (is (= "pod/foo-<ID>" (normalize-line "pod/foo-a1b2c")))
    (is (= "pod/foo-<ID>:" (normalize-line "pod/foo-a1b2c:"))))
  (testing "combined: event line of same class folds"
    (let [a "default 60m Warning PolicyViolation pod/kyverno-cleanup-admission-reports-29616080-ltgtv fail:"
          b "default 61m Warning PolicyViolation pod/kyverno-cleanup-admission-reports-29616080-585r4 fail:"
          c "default 60m Warning PolicyViolation pod/kyverno-cleanup-admission-reports-29616999-aabbc fail:"]
      (is (= (normalize-line a) (normalize-line b) (normalize-line c)))))
  (testing "StatefulSet single-digit ordinals stay distinct"
    (is (not= (normalize-line "pod/postgresql-0 ready")
              (normalize-line "pod/postgresql-1 ready")))))

(deftest build-digest-message-format
  (testing "empty buckets produces header-only message"
    (is (= "monitor digest (60s window, 0 lines, 0 classes):"
           (build-digest-message 60 {} 0 20))))
  (testing "single bucket renders example line"
    (is (= "monitor digest (60s window, 1 lines, 1 classes):\n  1x foo-raw"
           (build-digest-message 60 {"foo-key" {:count 1 :example "foo-raw"}} 1 20))))
  (testing "counts sorted desc, example preserved"
    (let [buckets {"A" {:count 1  :example "raw-a"}
                   "B" {:count 10 :example "raw-b"}
                   "C" {:count 3  :example "raw-c"}}
          msg (build-digest-message 60 buckets 14 20)]
      (is (re-find #"10x raw-b[\s\S]*3x raw-c[\s\S]*1x raw-a" msg))))
  (testing "top-N truncates by count"
    (let [buckets {"a" {:count 5 :example "ra"}
                   "b" {:count 4 :example "rb"}
                   "c" {:count 3 :example "rc"}}
          msg (build-digest-message 60 buckets 12 2)]
      (is (re-find #"5x ra" msg))
      (is (re-find #"4x rb" msg))
      (is (not (re-find #"3x rc" msg))))))

(deftest add-to-digest-normalization
  (reset! digest-state {:buckets {} :total 0})
  (add-to-digest! "default 60m Warning pod/foo-29616080-ltgtv fail")
  (add-to-digest! "default 61m Warning pod/foo-29616999-585r4 fail")
  (add-to-digest! "default 60m Warning pod/bar-29616080-zzzzz fail")
  (testing "three lines collapse to two classes (foo vs bar)"
    (let [state @digest-state]
      (is (= 3 (:total state)))
      (is (= 2 (count (:buckets state))))))
  (testing "first raw line kept as example per bucket"
    (let [buckets (:buckets @digest-state)
          foo-example (->> buckets vals (filter #(str/includes? (:example %) "foo")) first :example)]
      (is (str/includes? foo-example "29616080-ltgtv")))))

(deftest parse-args-max-runtime
  (testing "valid positive integer"
    (is (= 60 (:max-runtime
               (parse-args ["--source" "x" "--regex" "y" "--max-runtime" "60"])))))
  (testing "non-numeric value"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--max-runtime must be a positive integer"
          (parse-args ["--source" "x" "--regex" "y" "--max-runtime" "abc"]))))
  (testing "zero rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--max-runtime must be a positive integer"
          (parse-args ["--source" "x" "--regex" "y" "--max-runtime" "0"]))))
  (testing "negative rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--max-runtime must be a positive integer"
          (parse-args ["--source" "x" "--regex" "y" "--max-runtime" "-5"])))))

(deftest parse-args-max-matches
  (testing "defaults to 1 when omitted"
    (is (= 1 (:max-matches
              (parse-args ["--source" "x" "--regex" "y"])))))
  (testing "explicit value overrides default"
    (is (= 5 (:max-matches
              (parse-args ["--source" "x" "--regex" "y" "--max-matches" "5"])))))
  (testing "non-numeric rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--max-matches must be a positive integer"
          (parse-args ["--source" "x" "--regex" "y" "--max-matches" "abc"]))))
  (testing "zero rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--max-matches must be a positive integer"
          (parse-args ["--source" "x" "--regex" "y" "--max-matches" "0"]))))
  (testing "negative rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--max-matches must be a positive integer"
          (parse-args ["--source" "x" "--regex" "y" "--max-matches" "-1"])))))

(deftest parse-args-errors
  (testing "missing source"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source is required"
          (parse-args ["--regex" "x"]))))
  (testing "missing filter"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one of --jq"
          (parse-args ["--source" "x"]))))
  (testing "two filters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
          (parse-args ["--source" "x" "--regex" "r" "--grep" "g"]))))
  (testing "unknown flag"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown flag"
          (parse-args ["--foo" "bar"]))))
  (testing "flag without value"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a value"
          (parse-args ["--source"]))))
  (testing "help"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"help"
          (parse-args ["--help"])))))

(deftest match-regex-hits-and-misses
  (is (= "ERROR: timeout" (match-regex "timeout" "ERROR: timeout")))
  (is (= "abc" (match-regex "b" "abc")))
  (is (nil? (match-regex "zzz" "abc")))
  (testing "empty haystack returns nil, not the empty string"
    (is (nil? (match-regex "x" "")))))

(deftest match-grep-hits-and-misses
  (is (= "ERROR: timeout" (match-grep "timeout" "ERROR: timeout")))
  (is (nil? (match-grep "zzz" "abc")))
  (testing "literal chars with regex meaning are treated as literal"
    (is (= "a.b" (match-grep "." "a.b")))
    (is (nil? (match-grep "." "abc")))))

(deftest match-jq-behavior
  (if-not (which "jq")
    (println "match-jq-behavior: jq not on PATH, skipping")
    (do
      (testing "select matches emit the selected value"
        (is (= "{\"type\":\"Warning\"}"
               (match-jq "select(.type == \"Warning\")"
                         "{\"type\":\"Warning\"}"))))
      (testing "select mismatches produce no match"
        (is (nil? (match-jq "select(.type == \"Warning\")"
                            "{\"type\":\"Normal\"}"))))
      (testing "select+reshape combines selection and projection"
        (is (= "{\"r\":\"Bad\"}"
               (match-jq "select(.type == \"Warning\") | {r: .reason}"
                         "{\"type\":\"Warning\",\"reason\":\"Bad\"}"))))
      (testing "literal null output does not count as a match"
        (is (nil? (match-jq "null" "{\"x\":1}"))))
      (testing "jq error on a line is treated as no-match, not a crash"
        (is (nil? (match-jq "select(.type == \"x\")" "not-json")))))))

(deftest make-matcher-dispatch
  (testing "regex spec"
    (let [f (make-matcher {:filter-type :regex :filter-expr "foo"})]
      (is (= "foobar" (f "foobar")))
      (is (nil? (f "bar")))))
  (testing "grep spec"
    (let [f (make-matcher {:filter-type :grep :filter-expr "foo"})]
      (is (= "foobar" (f "foobar"))))))
