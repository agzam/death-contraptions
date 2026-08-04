(ns org-roam-mcp.embeddings
  (:require [clojure.string :as str]
            [hato.client :as hc]
            [cheshire.core :as json]
            [org-roam-mcp.util :as util]))

(defonce ^:private http-client
  (delay (hc/build-http-client {:connect-timeout 10000})))

;; Retrieval models are trained with their own asymmetric document/query
;; prefixes; using the wrong pair measurably degrades ranking, so they travel
;; with :model in config. Defaults keep nomic-embed-text configs working.
(def default-doc-prefix "search_document: ")
(def default-query-prefix "search_query: ")

(defn doc-text
  "Prefix a document for embedding, substituting a placeholder for blank input
   so the API never receives an empty string."
  [config text]
  (str (get config :doc-prefix default-doc-prefix)
       (if (str/blank? text) "[empty]" text)))

(defn query-text
  "Prefix a search query for embedding."
  [config text]
  (str (get config :query-prefix default-query-prefix) text))

(defn embed-batch
  "Embed a batch of texts via Ollama /api/embed.
   Returns a vector of float arrays, one per input text."
  [{:keys [ollama-url model] :as config} texts]
  (util/ensure-ssh-tunnel! config)
  (let [prefixed (mapv #(doc-text config %) texts)
        resp (hc/request
              {:url                (str ollama-url "/api/embed")
               :method             :post
               :content-type       :json
               :body               (json/generate-string {:model model :input prefixed :keep_alive "30m"})
               :http-client        @http-client
               :as                 :string
               :throw-exceptions?  false
               :socket-timeout     60000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "Ollama embed failed: " (subs (str (:body resp)) 0 (min 200 (count (str (:body resp))))))
                      {:status (:status resp)})))
    (let [body (json/parse-string (:body resp) true)]
      (mapv float-array (:embeddings body)))))

(defn embed-query
  "Embed a single query string using the model's query prefix for retrieval."
  [{:keys [ollama-url model] :as config} text]
  (util/ensure-ssh-tunnel! config)
  (let [resp (hc/request
              {:url                (str ollama-url "/api/embed")
               :method             :post
               :content-type       :json
               :body               (json/generate-string
                                    {:model model
                                     :input [(query-text config text)]
                                     :keep_alive "30m"})
               :http-client        @http-client
               :as                 :string
               :throw-exceptions?  false
               :socket-timeout     30000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "Ollama query embed failed: " (:body resp))
                      {:status (:status resp)})))
    (let [body (json/parse-string (:body resp) true)]
      (float-array (first (:embeddings body))))))

(defn- embed-single-with-truncation
  "Embed a single text, truncating progressively on context-length errors."
  [config text]
  (util/ensure-ssh-tunnel! config)
  (loop [t (doc-text config text)
         attempt 0]
    (if (< 3 attempt)
      (do (util/log "WARN: giving up on text after 4 truncation attempts, len=" (count t))
          nil)
      (let [resp (hc/request
                  {:url          (str (:ollama-url config) "/api/embed")
                   :method       :post
                   :content-type :json
                   :body         (json/generate-string {:model (:model config) :input [t] :keep_alive "30m"})
                   :http-client  @http-client
                   :as           :string
                   :throw-exceptions? false
                   :socket-timeout 60000})]
        (if (= 200 (:status resp))
          (let [body (json/parse-string (:body resp) true)]
            (float-array (first (:embeddings body))))
          ;; Truncate to 60% and retry
          (let [new-len (int (* 0.6 (count t)))]
            (util/log "WARN: embed 400 at len=" (count t) "- truncating to" new-len)
            (recur (subs t 0 new-len) (inc attempt))))))))

(defn embed-all
  "Embed a sequence of texts in batches. Returns a vector of float arrays
   in the same order as the input texts. Logs progress.
   On batch failure, falls back to embedding items individually with truncation."
  [config texts batch-size]
  (let [batches (partition-all batch-size texts)
        total (count texts)]
    (util/log "Embedding" total "texts in" (count batches) "batches")
    (loop [bs batches
           idx 0
           result []]
      (if (empty? bs)
        result
        (let [batch (vec (first bs))
              n (count batch)]
          (util/log "  batch" (inc idx) "-" n "texts")
          (let [vecs (try
                       (embed-batch config batch)
                       (catch Exception _e
                         ;; Batch failed - embed individually with truncation
                         (util/log "  batch failed, falling back to individual embedding")
                         (mapv (fn [t] (embed-single-with-truncation config t)) batch)))]
            (recur (rest bs) (inc idx) (into result vecs))))))))
