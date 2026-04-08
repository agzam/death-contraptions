(ns org-roam-mcp.util-test
  (:require [clojure.test :refer [deftest is testing]]
            [org-roam-mcp.util :as util]))

(deftest expand-home-test
  (let [home (System/getProperty "user.home")]
    (testing "expands ~/ prefix"
      (is (= (str home "/foo/bar") (util/expand-home "~/foo/bar"))))
    (testing "leaves absolute paths alone"
      (is (= "/tmp/foo" (util/expand-home "/tmp/foo"))))
    (testing "leaves relative paths alone"
      (is (= "foo/bar" (util/expand-home "foo/bar"))))))

(deftest contract-home-test
  (let [home (System/getProperty "user.home")]
    (testing "replaces home prefix with ~"
      (is (= "~/foo" (util/contract-home (str home "/foo")))))
    (testing "leaves non-home paths alone"
      (is (= "/tmp/foo" (util/contract-home "/tmp/foo"))))))

(deftest sha256-test
  (testing "produces consistent 64-char hex digest"
    (let [h (util/sha256 "hello")]
      (is (= 64 (count h)))
      (is (= h (util/sha256 "hello")))))
  (testing "different inputs produce different digests"
    (is (not= (util/sha256 "a") (util/sha256 "b")))))
