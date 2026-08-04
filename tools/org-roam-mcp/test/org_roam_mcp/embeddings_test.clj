(ns org-roam-mcp.embeddings-test
  (:require [clojure.test :refer [deftest is testing]]
            [org-roam-mcp.embeddings :as emb]))

(def gemma-config
  {:doc-prefix "title: none | text: "
   :query-prefix "task: search result | query: "})

(deftest doc-text-test
  (testing "applies the configured document prefix"
    (is (= "title: none | text: hello"
           (emb/doc-text gemma-config "hello"))))
  (testing "falls back to nomic prefix when unconfigured"
    (is (= "search_document: hello" (emb/doc-text {} "hello"))))
  (testing "substitutes a placeholder for blank input"
    (is (= "title: none | text: [empty]" (emb/doc-text gemma-config "")))
    (is (= "title: none | text: [empty]" (emb/doc-text gemma-config "   ")))
    (is (= "title: none | text: [empty]" (emb/doc-text gemma-config nil))))
  (testing "an empty prefix is honored rather than treated as missing"
    (is (= "hello" (emb/doc-text {:doc-prefix ""} "hello")))))

(deftest query-text-test
  (testing "applies the configured query prefix"
    (is (= "task: search result | query: clojure macros"
           (emb/query-text gemma-config "clojure macros"))))
  (testing "falls back to nomic prefix when unconfigured"
    (is (= "search_query: clojure macros"
           (emb/query-text {} "clojure macros"))))
  (testing "an empty prefix is honored"
    (is (= "clojure macros" (emb/query-text {:query-prefix ""} "clojure macros")))))

(deftest asymmetric-prefixes-test
  (testing "document and query prefixes stay distinct, as retrieval models expect"
    (is (not= (emb/doc-text gemma-config "x")
              (emb/query-text gemma-config "x")))))
