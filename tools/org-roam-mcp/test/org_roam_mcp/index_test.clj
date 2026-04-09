(ns org-roam-mcp.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [org-roam-mcp.index :as idx]))

(def ^:private test-hnsw-config
  {:dimensions 4 :max-items 100 :m 8 :ef 50 :ef-construction 50})

(defn- fake-chunk [id title & {:keys [tags links] :or {tags [] links #{}}}]
  {:node-id id :title title :file-path "/tmp/test.org" :level 0
   :tags tags :aliases [] :outgoing-links links
   :content (str "Content for " title)
   :mtime 1000 :checksum "abc123"})

(defn- random-vec [dims]
  (let [arr (float-array dims)]
    (dotimes [i dims] (aset arr i (float (- (rand 2) 1))))
    arr))

(deftest create-and-add-test
  (testing "create index and add items"
    (let [index (idx/create-index test-hnsw-config)
          item (idx/make-item (fake-chunk "A" "Alpha") (random-vec 4))]
      (idx/add-item! index item)
      (is (= 1 (.size index)))
      (is (some? (idx/get-item index "A"))))))

(deftest search-test
  (testing "search returns nearest neighbors"
    (let [index (idx/create-index test-hnsw-config)
          ;; Two items with known vectors
          v1 (float-array [1.0 0.0 0.0 0.0])
          v2 (float-array [0.0 1.0 0.0 0.0])
          _ (idx/add-item! index (idx/make-item (fake-chunk "A" "Alpha") v1))
          _ (idx/add-item! index (idx/make-item (fake-chunk "B" "Beta") v2))
          ;; Query close to v1
          results (idx/search index (float-array [0.9 0.1 0.0 0.0]) 2)]
      (is (= 2 (count results)))
      (is (= "A" (:node-id (first results)))))))

(deftest item-roundtrip-test
  (testing "item->map recovers metadata"
    (let [chunk (fake-chunk "X" "Xray" :tags ["a" "b"] :links #{"L1" "L2"})
          item (idx/make-item chunk (random-vec 4))
          m (idx/item->map item)]
      (is (= "X" (:node-id m)))
      (is (= "Xray" (:title m)))
      (is (= ["a" "b"] (:tags m)))
      (is (= #{"L1" "L2"} (:outgoing-links m))))))

(deftest remove-test
  (testing "remove item from index"
    (let [index (idx/create-index test-hnsw-config)
          _ (idx/add-item! index (idx/make-item (fake-chunk "A" "Alpha") (random-vec 4)))
          _ (idx/add-item! index (idx/make-item (fake-chunk "B" "Beta") (random-vec 4)))]
      (is (= 2 (.size index)))
      (idx/remove-item! index "A")
      (is (= 1 (.size index)))
      (is (nil? (idx/get-item index "A")))
      (is (some? (idx/get-item index "B"))))))

(deftest save-load-roundtrip-test
  (testing "save and load index preserves items"
    (let [dir (str (System/getProperty "java.io.tmpdir") "/hnsw-test-" (System/nanoTime))
          _ (.mkdirs (io/file dir))
          index (idx/create-index test-hnsw-config)
          v1 (float-array [1.0 0.0 0.0 0.0])
          _ (idx/add-item! index (idx/make-item (fake-chunk "A" "Alpha" :tags ["t1"]) v1))
          _ (idx/save-index! index dir {"test.org" 1000} 1 "test-model" 4)
          [loaded meta] (idx/load-index dir)]
      (is (= 1 (.size loaded)))
      (let [item (idx/get-item loaded "A")
            m (idx/item->map item)]
        (is (= "Alpha" (:title m)))
        (is (= ["t1"] (:tags m))))
      (is (= "test-model" (:model meta)))
      (is (= 4 (:dimensions meta)))
      ;; cleanup
      (doseq [f (reverse (file-seq (io/file dir)))]
        (.delete f)))))

(deftest load-index-missing-test
  (testing "returns nil when index directory has no index file"
    (let [dir (str (System/getProperty "java.io.tmpdir") "/hnsw-empty-" (System/nanoTime))]
      (.mkdirs (io/file dir))
      (is (nil? (idx/load-index dir)))
      ;; cleanup
      (.delete (io/file dir)))))

(deftest load-index-corrupt-test
  (testing "returns nil and deletes corrupt index file"
    (let [dir (str (System/getProperty "java.io.tmpdir") "/hnsw-corrupt-" (System/nanoTime))
          idx-file (io/file dir "index.hnsw")]
      (.mkdirs (io/file dir))
      ;; write garbage bytes to simulate corruption
      (with-open [os (java.io.FileOutputStream. idx-file)]
        (.write os (byte-array (map byte [0 0 0 0 1 2 3]))))
      (is (.exists idx-file))
      (is (nil? (idx/load-index dir)))
      (is (not (.exists idx-file)) "corrupt file should be deleted")
      ;; cleanup
      (doseq [f (reverse (file-seq (io/file dir)))]
        (.delete f)))))
