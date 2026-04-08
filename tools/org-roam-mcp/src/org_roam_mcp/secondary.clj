(ns org-roam-mcp.secondary
  (:require [clojure.string :as str]
            [org-roam-mcp.index :as idx]
            [org-roam-mcp.util :as util]))

;; Three secondary indices derived from HNSW items.
;; Rebuilt from scratch on index load - cheap since it's just iterating metadata.

(defonce backlinks (atom {}))   ;; {target-node-id -> #{source-node-id}}
(defonce title-idx (atom {}))   ;; {lowercase-string -> [{:node-id :title :type}]}
(defonce tag-idx   (atom {}))   ;; {tag -> #{node-id}}

(defn- index-chunk!
  "Populate backlink, title, and tag accumulators from one chunk's metadata."
  [bl ti tg {:keys [node-id title aliases outgoing-links tags]}]
  (doseq [target-id outgoing-links]
    (swap! bl update target-id (fnil conj #{}) node-id))
  (swap! ti update (str/lower-case title)
         (fnil conj []) {:node-id node-id :title title :type :title})
  (doseq [alias aliases]
    (swap! ti update (str/lower-case alias)
           (fnil conj []) {:node-id node-id :title alias :type :alias}))
  (doseq [tag tags]
    (swap! tg update tag (fnil conj #{}) node-id)))

(defn build!
  "Rebuild all secondary indices. Takes HNSW index for embedded items,
   plus an optional seq of extra chunk maps (non-embedded but structurally
   relevant). Secondary indices cover ALL nodes, not just embedded ones."
  ([hnsw-index] (build! hnsw-index nil))
  ([hnsw-index extra-chunks]
   (util/log "Building secondary indices")
   (let [bl (atom {})
         ti (atom {})
         tg (atom {})]
     ;; Index HNSW items
     (doseq [item (idx/all-items hnsw-index)]
       (index-chunk! bl ti tg (idx/item->map item)))
     ;; Index non-embedded chunks (structural data only)
     (doseq [chunk extra-chunks]
       (index-chunk! bl ti tg chunk))
     (reset! backlinks @bl)
     (reset! title-idx @ti)
     (reset! tag-idx @tg)
     (util/log "Secondary indices built:"
               (count @backlinks) "backlink targets,"
               (count @title-idx) "title entries,"
               (count @tag-idx) "tags"))))

(defn find-backlinks
  "Find all node IDs that link to the given target-id."
  [target-id]
  (get @backlinks target-id #{}))

(defn resolve-node
  "Resolve a string (title, alias, or ID) to candidate node-id maps."
  [s]
  (or (get @title-idx (str/lower-case s))
      ;; Maybe it's a direct node ID
      []))

(defn find-by-tag
  "Find all node IDs with the given tag."
  [tag]
  (get @tag-idx tag #{}))

(defn update-for-item!
  "Incrementally add a single item's metadata to all secondary indices."
  [item]
  (let [m (idx/item->map item)
        src-id (:node-id m)]
    ;; Backlinks
    (doseq [target-id (:outgoing-links m)]
      (swap! backlinks update target-id (fnil conj #{}) src-id))
    ;; Title index
    (swap! title-idx update (str/lower-case (:title m))
           (fnil conj []) {:node-id src-id :title (:title m) :type :title})
    (doseq [alias (:aliases m)]
      (swap! title-idx update (str/lower-case alias)
             (fnil conj []) {:node-id src-id :title alias :type :alias}))
    ;; Tag index
    (doseq [tag (:tags m)]
      (swap! tag-idx update tag (fnil conj #{}) src-id))))

(defn remove-for-id!
  "Purge a node ID from all secondary indices (backlinks, titles, tags)."
  [node-id]
  ;; Backlinks: remove as source from all targets
  (swap! backlinks
         (fn [bl]
           (reduce-kv (fn [m k v]
                        (let [v' (disj v node-id)]
                          (if (empty? v') m (assoc m k v'))))
                      {} bl)))
  ;; Title index: remove entries with this node-id
  (swap! title-idx
         (fn [ti]
           (reduce-kv (fn [m k v]
                        (let [v' (filterv #(not= (:node-id %) node-id) v)]
                          (if (empty? v') m (assoc m k v'))))
                      {} ti)))
  ;; Tag index: remove from all tags
  (swap! tag-idx
         (fn [tg]
           (reduce-kv (fn [m k v]
                        (let [v' (disj v node-id)]
                          (if (empty? v') m (assoc m k v'))))
                      {} tg))))
