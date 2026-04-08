(ns org-roam-mcp.secondary-test
  (:require [clojure.test :refer [deftest is testing]]
            [org-roam-mcp.index :as idx]
            [org-roam-mcp.secondary :as sec]))

(def ^:private test-hnsw-config
  {:dimensions 4 :max-items 100 :m 8 :ef 50 :ef-construction 50})

(defn- fake-chunk [id title & {:keys [tags aliases links]
                                :or {tags [] aliases [] links #{}}}]
  {:node-id id :title title :file-path "/tmp/test.org" :level 0
   :tags tags :aliases aliases :outgoing-links links
   :content "content" :mtime 1000 :checksum "abc"})

(defn- random-vec []
  (let [a (float-array 4)]
    (dotimes [i 4] (aset a i (float (rand))))
    a))

(defn- build-test-index
  "Create an index with test items and build secondary indices."
  []
  (let [index (idx/create-index test-hnsw-config)]
    (idx/add-item! index (idx/make-item
                          (fake-chunk "A" "Alpha" :tags ["t1" "t2"]
                                      :aliases ["Al"] :links #{"B" "C"})
                          (random-vec)))
    (idx/add-item! index (idx/make-item
                          (fake-chunk "B" "Beta" :tags ["t1"]
                                      :aliases ["Be"] :links #{"A"})
                          (random-vec)))
    (idx/add-item! index (idx/make-item
                          (fake-chunk "C" "Charlie" :tags ["t2" "t3"]
                                      :links #{})
                          (random-vec)))
    (sec/build! index)
    index))

(deftest backlinks-test
  (build-test-index)
  (testing "A links to B and C"
    (is (contains? (sec/find-backlinks "B") "A"))
    (is (contains? (sec/find-backlinks "C") "A")))
  (testing "B links to A"
    (is (contains? (sec/find-backlinks "A") "B")))
  (testing "nobody links to nonexistent"
    (is (empty? (sec/find-backlinks "Z")))))

(deftest title-index-test
  (build-test-index)
  (testing "resolve by title"
    (let [candidates (sec/resolve-node "Alpha")]
      (is (= 1 (count candidates)))
      (is (= "A" (:node-id (first candidates))))))
  (testing "resolve by alias"
    (let [candidates (sec/resolve-node "Al")]
      (is (= 1 (count candidates)))
      (is (= "A" (:node-id (first candidates))))))
  (testing "case-insensitive"
    (is (seq (sec/resolve-node "alpha")))
    (is (seq (sec/resolve-node "BETA")))))

(deftest tag-index-test
  (build-test-index)
  (testing "find by tag"
    (is (= #{"A" "B"} (sec/find-by-tag "t1")))
    (is (= #{"A" "C"} (sec/find-by-tag "t2")))
    (is (= #{"C"} (sec/find-by-tag "t3")))))

(deftest incremental-update-test
  (let [index (build-test-index)]
    (testing "add new item incrementally"
      (let [item (idx/make-item
                  (fake-chunk "D" "Delta" :tags ["t1"] :aliases ["De"] :links #{"A"})
                  (random-vec))]
        (idx/add-item! index item)
        (sec/update-for-item! item)
        (is (contains? (sec/find-backlinks "A") "D"))
        (is (seq (sec/resolve-node "Delta")))
        (is (seq (sec/resolve-node "De")))
        (is (contains? (sec/find-by-tag "t1") "D"))))))

(deftest remove-from-secondary-test
  (build-test-index)
  (testing "remove node from all indices"
    (sec/remove-for-id! "A")
    (is (not (contains? (sec/find-backlinks "B") "A")))
    (is (empty? (sec/resolve-node "Alpha")))
    (is (empty? (sec/resolve-node "Al")))
    (is (not (contains? (sec/find-by-tag "t1") "A")))))

(deftest build-with-extra-chunks-test
  (let [index (build-test-index)]
    (testing "extra non-embedded chunks contribute to secondary indices"
      (sec/build! index [{:node-id "X" :title "Extra" :aliases [] :tags ["t1"]
                          :outgoing-links #{"A"} :content ""}])
      (is (contains? (sec/find-backlinks "A") "X"))
      (is (seq (sec/resolve-node "Extra")))
      (is (contains? (sec/find-by-tag "t1") "X")))))
