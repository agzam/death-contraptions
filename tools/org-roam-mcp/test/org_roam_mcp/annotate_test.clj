(ns org-roam-mcp.annotate-test
  (:require [clojure.test :refer [deftest is testing]]
            [org-roam-mcp.annotate :as ann]))

(def ^:private title-idx
  {"alpha"       [{:node-id "A" :title "Alpha" :type :title}]
   "zach harris" [{:node-id "Z" :title "Zach Harris" :type :title}]
   "zach"        [{:node-id "Z" :title "Zach Harris" :type :alias}]
   "dan"         [{:node-id "D1" :title "Dan Smith" :type :alias}
                  {:node-id "D2" :title "Dan Jones" :type :alias}]
   "db"          [{:node-id "DB" :title "Database" :type :alias}]
   "x"           [{:node-id "X" :title "X" :type :title}]
   "zeta"        [{:node-id "Z2" :title "Zeta" :type :title}
                  {:node-id "Z2" :title "Zeta" :type :alias}]})

(deftest single-unambiguous-match-test
  (let [{:keys [annotations annotated-text safe-annotated-text]}
        (ann/annotate title-idx "met Alpha today")]
    (is (= 1 (count annotations)))
    (let [{:keys [span matched-text ambiguous candidates]} (first annotations)]
      (is (= [4 9] span))
      (is (= "Alpha" matched-text))
      (is (false? ambiguous))
      (is (= [{:node-id "A" :title "Alpha" :link "[[id:A][Alpha]]"}] candidates)))
    (is (= "met [[id:A][Alpha]] today" annotated-text))
    (is (= annotated-text safe-annotated-text))))

(deftest surface-text-preserved-test
  (testing "case-insensitive match keeps the original surface as description"
    (let [{:keys [annotated-text]} (ann/annotate title-idx "met alpha today")]
      (is (= "met [[id:A][alpha]] today" annotated-text)))))

(deftest longest-match-first-test
  (testing "full name wins over the alias embedded in it"
    (let [{:keys [annotations]} (ann/annotate title-idx "talked to Zach Harris")]
      (is (= ["Zach Harris"] (map :matched-text annotations)))
      (is (= "Z" (-> annotations first :candidates first :node-id)))))
  (testing "alias alone still matches"
    (let [{:keys [annotations]} (ann/annotate title-idx "ping Zach now")]
      (is (= ["Zach"] (map :matched-text annotations)))
      (is (= "Z" (-> annotations first :candidates first :node-id))))))

(deftest ambiguous-match-test
  (let [{:keys [annotations annotated-text safe-annotated-text]}
        (ann/annotate title-idx "Dan proposed it")]
    (is (= 1 (count annotations)))
    (let [{:keys [ambiguous candidates]} (first annotations)]
      (is (true? ambiguous))
      (is (= #{"D1" "D2"} (set (map :node-id candidates)))))
    (testing "safe render leaves ambiguous spans as plain text"
      (is (= "Dan proposed it" safe-annotated-text)))
    (testing "best-guess render applies the deterministic top candidate"
      (is (= "[[id:D2][Dan]] proposed it" annotated-text)))))

(deftest word-boundary-test
  (testing "no match inside a longer word"
    (is (empty? (:annotations (ann/annotate title-idx "Alphabet soup"))))
    (is (empty? (:annotations (ann/annotate title-idx "MongoDB rocks")))))
  (testing "punctuation and string edges bound fine"
    (is (= ["Alpha"] (map :matched-text
                          (:annotations (ann/annotate title-idx "Alpha, hi")))))
    (is (= ["DB"] (map :matched-text
                       (:annotations (ann/annotate title-idx "the DB")))))))

(deftest min-length-test
  (testing "1-char titles are ignored"
    (is (empty? (:annotations (ann/annotate title-idx "x marks the spot"))))))

(deftest same-node-title-and-alias-dedupe-test
  (let [{:keys [annotations]} (ann/annotate title-idx "Zeta rules")]
    (is (= 1 (count annotations)))
    (is (false? (:ambiguous (first annotations))))
    (is (= 1 (count (:candidates (first annotations)))))))

(deftest multiple-spans-render-test
  (let [{:keys [annotations annotated-text safe-annotated-text]}
        (ann/annotate title-idx "Dan met Alpha near the DB")]
    (is (= ["Dan" "Alpha" "DB"] (mapv :matched-text annotations)))
    (testing "span indices slice back to the matched text"
      (doseq [{:keys [span matched-text]} annotations]
        (is (= matched-text (subs "Dan met Alpha near the DB"
                                  (first span) (second span))))))
    (is (= "[[id:D2][Dan]] met [[id:A][Alpha]] near the [[id:DB][DB]]"
           annotated-text))
    (is (= "Dan met [[id:A][Alpha]] near the [[id:DB][DB]]"
           safe-annotated-text))))

(deftest no-match-test
  (let [text "nothing to see here"
        {:keys [annotations annotated-text safe-annotated-text original-text]}
        (ann/annotate title-idx text)]
    (is (empty? annotations))
    (is (= text original-text annotated-text safe-annotated-text))))
