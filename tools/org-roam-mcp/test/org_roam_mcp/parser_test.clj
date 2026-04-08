(ns org-roam-mcp.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org-roam-mcp.parser :as parser]))

;; ---------------------------------------------------------------------------
;; Helpers - write temp .org files
;; ---------------------------------------------------------------------------

(defn- temp-org-file
  "Write content to a temp .org file, return the File."
  [content]
  (let [f (java.io.File/createTempFile "test-" ".org")]
    (.deleteOnExit f)
    (spit f content)
    f))

;; ---------------------------------------------------------------------------
;; parse-file tests
;; ---------------------------------------------------------------------------

(deftest parse-file-level-node
  (testing "file with ID, title, filetags"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:"
                        ":ID:       AAA-BBB-CCC"
                        ":END:"
                        "#+title: My Note"
                        "#+filetags: :work:programming:"
                        ""
                        "Some body text here."]))
          chunks (parser/parse-file f)]
      (is (= 1 (count chunks)))
      (let [c (first chunks)]
        (is (= "AAA-BBB-CCC" (:node-id c)))
        (is (= "My Note" (:title c)))
        (is (= 0 (:level c)))
        (is (= ["work" "programming"] (:tags c)))
        (is (str/includes? (:content c) "Some body text"))))))

(deftest parse-file-with-aliases
  (testing "ROAM_ALIASES with quoted multi-word and bare single-word"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:"
                        ":ROAM_ALIASES: elastic \"Elastic Search\""
                        ":ID: 111-222"
                        ":END:"
                        "#+title: elasticsearch"]))
          chunks (parser/parse-file f)]
      (is (= ["elastic" "Elastic Search"] (:aliases (first chunks)))))))

(deftest parse-vulpea-tag-links
  (testing "tag-links after title extracted as tags"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:"
                        ":ID: TAG-TEST-1"
                        ":END:"
                        "#+title: Android"
                        ""
                        "[[id:D9F71D4B][programming]]"
                        "[[id:SOME-UUID][mobile]]"
                        ""
                        "Real body content here."]))
          chunks (parser/parse-file f)
          c (first chunks)]
      (is (= ["programming" "mobile"] (:tags c)))
      ;; Body should NOT include the tag-link lines
      (is (str/includes? (:content c) "Real body"))
      (is (not (str/includes? (:content c) "programming"))))))

(deftest parse-heading-level-chunks
  (testing "heading with :ID: produces a chunk"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:"
                        ":ID: FILE-1"
                        ":END:"
                        "#+title: Top"
                        "* Sub heading"
                        ":PROPERTIES:"
                        ":ID: HEAD-1"
                        ":END:"
                        "Heading body text."
                        "* No ID heading"
                        "This has no ID and should be skipped."
                        "** Deep heading"
                        ":PROPERTIES:"
                        ":ID: HEAD-2"
                        ":ROAM_ALIASES: \"Deep Alias\""
                        ":END:"
                        "Deep body."]))
          chunks (parser/parse-file f)]
      (is (= 3 (count chunks)))
      (let [ids (set (map :node-id chunks))]
        (is (contains? ids "FILE-1"))
        (is (contains? ids "HEAD-1"))
        (is (contains? ids "HEAD-2")))
      (let [h2 (first (filter #(= "HEAD-2" (:node-id %)) chunks))]
        (is (= 2 (:level h2)))
        (is (= ["Deep Alias"] (:aliases h2)))
        (is (str/includes? (:content h2) "Deep body"))))))

(deftest parse-heading-inline-tags
  (testing "heading with inline org tags"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:"
                        ":ID: FILE-1"
                        ":END:"
                        "#+title: Top"
                        "#+filetags: :base:"
                        "* Tagged heading :foo:bar:"
                        ":PROPERTIES:"
                        ":ID: H-TAG"
                        ":END:"
                        "Body."]))
          chunks (parser/parse-file f)
          h (first (filter #(= "H-TAG" (:node-id %)) chunks))]
      (is (= "Tagged heading" (:title h)))
      ;; Should inherit file tag + have heading tags
      (is (= ["base" "foo" "bar"] (:tags h))))))

(deftest parse-outgoing-links
  (testing "id links extracted from content"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:"
                        ":ID: LINK-TEST"
                        ":END:"
                        "#+title: Links"
                        "See [[id:TARGET-1][some note]] and [[id:TARGET-2][another]]."
                        "Also [[https://example.com][a url]]."]))
          c (first (parser/parse-file f))]
      (is (= #{"TARGET-1" "TARGET-2"} (:outgoing-links c)))
      ;; Link descriptions should be in content, not the [[...]] syntax
      (is (str/includes? (:content c) "some note"))
      (is (not (str/includes? (:content c) "[["))))))

(deftest parse-strips-property-drawers
  (testing "property drawer lines not in content"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:"
                        ":ID: STRIP-TEST"
                        ":END:"
                        "#+title: Strip"
                        "Body here."]))
          c (first (parser/parse-file f))]
      (is (not (str/includes? (:content c) "PROPERTIES")))
      (is (not (str/includes? (:content c) ":END:"))))))

(deftest parse-case-insensitive-properties
  (testing "property keys are case-insensitive"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:"
                        ":id:       case-test-1"
                        ":roam_aliases: slug"
                        ":END:"
                        "#+TITLE: Case Test"]))
          c (first (parser/parse-file f))]
      (is (= "case-test-1" (:node-id c)))
      (is (= ["slug"] (:aliases c))))))

(deftest parse-file-without-id
  (testing "file with no :ID: produces no file-level chunk"
    (let [f (temp-org-file
             (str/join "\n"
                       ["#+title: No ID"
                        "Just text."
                        "* Heading with ID"
                        ":PROPERTIES:"
                        ":ID: ONLY-HEAD"
                        ":END:"
                        "Heading body."]))
          chunks (parser/parse-file f)]
      (is (= 1 (count chunks)))
      (is (= "ONLY-HEAD" (:node-id (first chunks)))))))

(deftest parse-filetags-formats
  (testing "colon-delimited filetags"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:" ":ID: FT-1" ":END:"
                        "#+title: T"
                        "#+filetags: :a:b:c:"]))
          c (first (parser/parse-file f))]
      (is (= ["a" "b" "c"] (:tags c)))))
  (testing "bare filetag"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:" ":ID: FT-2" ":END:"
                        "#+title: T"
                        "#+filetags: cryptography"]))
          c (first (parser/parse-file f))]
      (is (= ["cryptography"] (:tags c))))))

;; ---------------------------------------------------------------------------
;; embedding-text tests
;; ---------------------------------------------------------------------------

(deftest embedding-text-structure
  (testing "title + tags + content joined"
    (let [text (parser/embedding-text {:title "My Note"
                                       :tags ["work" "dev"]
                                       :content "Body here"})]
      (is (str/starts-with? text "My Note"))
      (is (str/includes? text "work, dev"))
      (is (str/includes? text "Body here"))))
  (testing "no tags omits tag line"
    (let [text (parser/embedding-text {:title "X" :tags [] :content "Y"})]
      (is (= "X\nY" text)))))

(deftest embedding-text-truncation
  (testing "long content is truncated"
    (let [long-content (apply str (repeat 30000 "x"))
          text (parser/embedding-text {:title "T" :tags [] :content long-content})]
      (is (= 24000 (count text))))))

;; ---------------------------------------------------------------------------
;; scan-directory / excludes
;; ---------------------------------------------------------------------------

(deftest scan-directory-excludes
  (testing "excluded paths are skipped"
    (let [dir (java.io.File/createTempFile "orgdir" "")
          _ (.delete dir)
          _ (.mkdirs dir)
          sub (io/file dir "data")
          _ (.mkdirs sub)
          f1 (io/file dir "note.org")
          f2 (io/file sub "excluded.org")]
      (spit f1 (str/join "\n" [":PROPERTIES:" ":ID: INC" ":END:" "#+title: Included"]))
      (spit f2 (str/join "\n" [":PROPERTIES:" ":ID: EXC" ":END:" "#+title: Excluded"]))
      (let [chunks (parser/scan-directory (.getAbsolutePath dir) ["data/"])]
        (is (= 1 (count chunks)))
        (is (= "INC" (:node-id (first chunks)))))
      ;; cleanup
      (.delete f2)
      (.delete f1)
      (.delete sub)
      (.delete dir))))

(deftest parse-tag-links-as-outgoing
  (testing "vulpea tag-links appear in both tags and outgoing-links"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:" ":ID: TLO-1" ":END:"
                        "#+title: Test"
                        "" "[[id:TARGET-1][programming]]" "[[id:TARGET-2][work]]"
                        "" "Body content."]))
          c (first (parser/parse-file f))]
      (is (= ["programming" "work"] (:tags c)))
      (is (contains? (:outgoing-links c) "TARGET-1"))
      (is (contains? (:outgoing-links c) "TARGET-2")))))

(deftest parse-full-file-links
  (testing "file-level chunk captures links from under non-ID headings"
    (let [f (temp-org-file
             (str/join "\n"
                       [":PROPERTIES:" ":ID: FFL-1" ":END:"
                        "#+title: Hub"
                        "* Sub heading without ID"
                        "See [[id:DEEP-TARGET][deep note]]."]))
          chunks (parser/parse-file f)
          fc (first (filter #(= "FFL-1" (:node-id %)) chunks))]
      (is (contains? (:outgoing-links fc) "DEEP-TARGET")))))
