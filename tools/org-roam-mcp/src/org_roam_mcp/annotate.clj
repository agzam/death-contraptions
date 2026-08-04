(ns org-roam-mcp.annotate
  "Entity annotation: find spans in free text matching known note
   titles/aliases, so plain prose can be turned into linked org-mode.
   Pure functions over a title-index map - no global state."
  (:import [java.util.regex Pattern]))

(def ^:private min-match-len
  "Ignore 1-char titles/aliases; they match everywhere and mean nothing."
  2)

(defn- occurrences
  "All [start end] of `needle` in `text`, case-insensitive.
   Letter/digit lookarounds instead of \\b so titles that start or end in
   non-word chars (C++, .NET) still get sane boundaries."
  [text needle]
  (let [p (Pattern/compile (str "(?<![\\p{L}\\p{N}])"
                                (Pattern/quote needle)
                                "(?![\\p{L}\\p{N}])")
                           (bit-or Pattern/CASE_INSENSITIVE Pattern/UNICODE_CASE))
        m (.matcher p text)]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc [(.start m) (.end m)]))
        acc))))

(defn- rank-candidates
  "Deterministic candidate order: title matches before alias matches, then
   alphabetical; one entry per node."
  [entries]
  (reduce (fn [acc e]
            (if (some #(= (:node-id %) (:node-id e)) acc)
              acc
              (conj acc e)))
          []
          (sort-by (juxt #(if (= :title (:type %)) 0 1) :title) entries)))

(defn find-spans
  "Scan `text` against `title-idx` {lowercase-name -> [entry ...]}.
   Longest-match-first at each position, non-overlapping, left to right.
   Returns ordered annotation maps."
  [title-idx text]
  (let [matches  (for [[k entries] title-idx
                       :when (<= min-match-len (count k))
                       [s e] (occurrences text k)]
                   {:start s :end e :entries entries})
        ordered  (sort-by (juxt :start (comp - :end)) matches)
        selected (reduce (fn [acc {:keys [start] :as m}]
                           (if (or (empty? acc) (<= (:end (peek acc)) start))
                             (conj acc m)
                             acc))
                         [] ordered)]
    (mapv (fn [{:keys [start end entries]}]
            (let [cands (rank-candidates entries)]
              {:span [start end]
               :matched-text (subs text start end)
               :ambiguous (< 1 (count cands))
               :candidates (mapv (fn [{:keys [node-id title]}]
                                   {:node-id node-id
                                    :title title
                                    :link (format "[[id:%s][%s]]" node-id title)})
                                 cands)}))
          selected)))

(defn- render
  "Rebuild `text` substituting org links for spans where `pick` returns a
   candidate. Matched surface text stays as the link description so the
   prose reads unchanged."
  [text annotations pick]
  (let [sb (StringBuilder.)
        tail (reduce (fn [pos {:keys [span] :as ann}]
                       (let [[s e] span]
                         (.append sb (subs text pos s))
                         (if-let [c (pick ann)]
                           (.append sb (str "[[id:" (:node-id c) "]["
                                            (subs text s e) "]]"))
                           (.append sb (subs text s e)))
                         e))
                     0 annotations)]
    (.append sb (subs text tail))
    (str sb)))

(defn annotate
  "Full annotation payload for `text`: structured spans plus two rendered
   variants - best-guess (top candidate everywhere) and safe (unambiguous
   matches only)."
  [title-idx text]
  (let [anns (find-spans title-idx text)]
    {:original-text text
     :annotations anns
     :annotated-text (render text anns (comp first :candidates))
     :safe-annotated-text (render text anns
                                  #(when-not (:ambiguous %)
                                     (first (:candidates %))))}))
