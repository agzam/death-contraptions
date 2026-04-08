(ns org-roam-mcp.watcher-test
  (:require [clojure.test :refer [deftest is testing]]
            [org-roam-mcp.watcher :as watcher]))

;; excluded-dir? is private, access via var
(def ^:private excluded-dir? @#'watcher/excluded-dir?)

(deftest excluded-dir-test
  (testing "root-level match"
    (is (excluded-dir? "data" ["data/"]))
    (is (excluded-dir? "data/foo" ["data/"]))
    (is (excluded-dir? ".git" [".git/"])))
  (testing "nested segment match"
    (is (excluded-dir? "daily/data" ["data/"]))
    (is (excluded-dir? "daily/data/AB/some-uuid" ["data/"]))
    (is (excluded-dir? "gptel/data/ef" ["data/"])))
  (testing "non-match"
    (is (not (excluded-dir? "daily" ["data/"])))
    (is (not (excluded-dir? "database" ["data/"])))
    (is (not (excluded-dir? "" ["data/"]))))
  (testing "multiple excludes"
    (is (excluded-dir? "daily/data/x" ["data/" ".git/"]))
    (is (excluded-dir? ".git/objects" ["data/" ".git/"]))
    (is (not (excluded-dir? "notes" ["data/" ".git/"]))))
  (testing "empty excludes"
    (is (not (excluded-dir? "anything" [])))))
