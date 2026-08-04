(ns org-roam-mcp.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [org-roam-mcp.core :as core]
            [org-roam-mcp.index :as idx]
            [org-roam-mcp.secondary :as sec]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private tools @#'core/tools)

(def ^:private handle-request #'core/handle-request)

(defn- tool-def [tool-name]
  (first (filter #(= (:name %) tool-name) tools)))

(defn- error-text
  "Extract the error text from a validation result."
  [result]
  (-> result :content first :text))

(defn- call
  "Drive a tools/call through the real JSON-RPC dispatch."
  [tool-name args]
  (:result (handle-request {"id" 1
                            "method" "tools/call"
                            "params" {"name" tool-name "arguments" args}})))

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

(defn- install-test-index!
  "Point core's private index atom at a small in-memory index."
  []
  (let [index (idx/create-index test-hnsw-config)]
    (idx/add-item! index (idx/make-item
                          (fake-chunk "A" "Alpha" :tags ["t1"] :links #{"B"})
                          (random-vec)))
    (idx/add-item! index (idx/make-item
                          (fake-chunk "B" "Beta" :tags ["t1" "t2"])
                          (random-vec)))
    (idx/add-item! index (idx/make-item
                          (fake-chunk "C" "Charlie" :tags ["t2"])
                          (random-vec)))
    (sec/build! index)
    (reset! @#'core/hnsw-index index)
    index))

;; ---------------------------------------------------------------------------
;; Schema invariants
;; ---------------------------------------------------------------------------

(deftest schemas-declare-strictness-test
  (testing "every tool schema rejects undeclared properties"
    (doseq [{:keys [name inputSchema]} tools]
      (is (false? (:additionalProperties inputSchema))
          (str name " must declare :additionalProperties false")))))

;; ---------------------------------------------------------------------------
;; validate-args
;; ---------------------------------------------------------------------------

(deftest unknown-args-rejected-test
  (testing "unknown args error instead of being silently ignored"
    (let [err (core/validate-args (tool-def "notes-search") {"foo" 5 "query" "x"})]
      (is (:isError err))
      (is (str/includes? (error-text err) "unknown: foo"))
      (is (str/includes? (error-text err) "Accepted arguments:"))))
  (testing "the original LLM mistake: id instead of node"
    (let [err (core/validate-args (tool-def "notes-backlinks") {"id" "some-uuid"})]
      (is (:isError err))
      (is (str/includes? (error-text err) "unknown: id"))
      (is (str/includes? (error-text err) "missing required: node"))
      (is (str/includes? (error-text err) "required: node")))))

(deftest missing-required-args-test
  (testing "absent required arg names the arg, no NPE"
    (let [err (core/validate-args (tool-def "notes-backlinks") {})]
      (is (:isError err))
      (is (str/includes? (error-text err) "missing required: node"))))
  (testing "JSON null counts as missing"
    (let [err (core/validate-args (tool-def "notes-backlinks") {"node" nil})]
      (is (:isError err))
      (is (str/includes? (error-text err) "missing required: node"))))
  (testing "nil arguments map handled"
    (let [err (core/validate-args (tool-def "notes-search-related") nil)]
      (is (:isError err))
      (is (str/includes? (error-text err) "missing required: node")))))

(deftest type-errors-test
  (testing "wrong scalar type"
    (let [err (core/validate-args (tool-def "notes-search") {"query" 42})]
      (is (:isError err))
      (is (str/includes? (error-text err) "query must be string"))))
  (testing "string where integer expected"
    (let [err (core/validate-args (tool-def "notes-search") {"query" "x" "k" "5"})]
      (is (:isError err))
      (is (str/includes? (error-text err) "k must be integer"))))
  (testing "array item type checked"
    (let [err (core/validate-args (tool-def "notes-search") {"query" "x" "tags" ["a" 5]})]
      (is (:isError err))
      (is (str/includes? (error-text err) "tags must be array of string")))))

(deftest enum-errors-test
  (testing "enum violation names allowed values"
    (let [err (core/validate-args (tool-def "notes-edit") {"content" "x" "mode" "wrong"})]
      (is (:isError err))
      (is (str/includes? (error-text err) "mode must be one of append/replace")))))

(deftest valid-args-pass-test
  (is (nil? (core/validate-args (tool-def "notes-search") {"query" "x" "k" 5})))
  (is (nil? (core/validate-args (tool-def "notes-search") {"query" "x" "limit" 5})))
  (is (nil? (core/validate-args (tool-def "notes-backlinks") {"node" "Alpha"})))
  (is (nil? (core/validate-args (tool-def "notes-read") {})))
  (is (nil? (core/validate-args (tool-def "notes-create")
                                {"title" "t" "content" "c" "mode" "journal"}))))

;; ---------------------------------------------------------------------------
;; Alias normalization (k -> limit)
;; ---------------------------------------------------------------------------

(deftest normalize-args-test
  (testing "k rewrites to limit"
    (is (= {:args {"query" "x" "limit" 5}}
           (core/normalize-args "notes-search" {"query" "x" "k" 5}))))
  (testing "canonical limit passes through"
    (is (= {:args {"limit" 5}}
           (core/normalize-args "notes-search-related" {"limit" 5}))))
  (testing "both spellings is an error"
    (let [{:keys [error]} (core/normalize-args "notes-search" {"k" 5 "limit" 3})]
      (is (:isError error))
      (is (str/includes? (error-text error) "not both"))))
  (testing "tools without aliases pass args untouched"
    (is (= {:args {"id" "abc"}}
           (core/normalize-args "notes-read" {"id" "abc"})))))

(deftest limit-and-alias-behavior-test
  (install-test-index!)
  (testing "limit caps structural search results"
    (let [result (call "notes-search" {"tags" ["t1"] "limit" 1})
          parsed (json/parse-string (error-text result) true)]
      (is (not (:isError result)))
      (is (= 1 (count (:results parsed))))))
  (testing "k behaves identically via aliasing (previously silently defaulted)"
    (let [result (call "notes-search" {"tags" ["t1"] "k" 1})
          parsed (json/parse-string (error-text result) true)]
      (is (not (:isError result)))
      (is (= 1 (count (:results parsed))))))
  (testing "both k and limit rejected at dispatch"
    (let [result (call "notes-search" {"tags" ["t1"] "k" 1 "limit" 2})]
      (is (:isError result))
      (is (str/includes? (error-text result) "not both")))))

;; ---------------------------------------------------------------------------
;; Full dispatch integration
;; ---------------------------------------------------------------------------

(deftest dispatch-validation-integration-test
  (testing "notes-backlinks without node fails legibly through dispatch (was NPE)"
    (let [result (call "notes-backlinks" {})]
      (is (:isError result))
      (is (str/includes? (error-text result) "missing required: node"))))
  (testing "notes-search-related without node fails legibly through dispatch (was NPE)"
    (let [result (call "notes-search-related" {})]
      (is (:isError result))
      (is (str/includes? (error-text result) "missing required: node"))))
  (testing "unknown tool lists available tools"
    (let [result (call "notes-frobnicate" {})]
      (is (:isError result))
      (is (str/includes? (error-text result) "Unknown tool: notes-frobnicate"))
      (is (str/includes? (error-text result) "notes-search")))))

(deftest dispatch-valid-call-passes-through-test
  (install-test-index!)
  (testing "structural tag search reaches the handler and returns results"
    (let [result (call "notes-search" {"tags" ["t1"]})
          parsed (json/parse-string (error-text result) true)]
      (is (not (:isError result)))
      (is (= #{"A" "B"} (set (map :node-id (:results parsed))))))))

(deftest notes-annotate-dispatch-test
  (install-test-index!)
  (testing "annotates text against the live title index"
    (let [result (call "notes-annotate" {"text" "Alpha talked to Beta"})
          parsed (json/parse-string (error-text result) true)]
      (is (not (:isError result)))
      (is (= ["Alpha" "Beta"] (mapv :matched-text (:annotations parsed))))
      (is (= "[[id:A][Alpha]] talked to [[id:B][Beta]]"
             (:annotated-text parsed)))))
  (testing "missing text fails legibly"
    (let [result (call "notes-annotate" {})]
      (is (:isError result))
      (is (str/includes? (error-text result) "missing required: text"))))
  (testing "extra args rejected"
    (let [result (call "notes-annotate" {"text" "hi" "context" "nope"})]
      (is (:isError result))
      (is (str/includes? (error-text result) "unknown: context")))))

(deftest notes-read-selector-guidance-test
  (testing "no selector at all yields guidance, not 'not found'"
    (let [result (call "notes-read" {})]
      (is (:isError result))
      (is (= "Provide one of: id, title, path" (error-text result)))))
  (testing "wrong selector still reports which value failed"
    (install-test-index!)
    (let [result (call "notes-read" {"title" "Nonexistent"})]
      (is (:isError result))
      (is (str/includes? (error-text result) "Note not found: Nonexistent")))))
