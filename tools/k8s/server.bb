#!/usr/bin/env bb
;; MCP server for Kubernetes: manage clusters via kubectl, stern, helm.
;; Delegates to CLI tools for all operations; adds output cleaning,
;; secrets masking, and stateful watch/diff via cached snapshots.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[clojure.set :as set])

;;; ---------- State ----------

(def watch-cache (atom {})) ;; {cache-key -> {:timestamp <epoch-ms> :items [...]}}

;;; ---------- Shell helpers ----------

(defn sh-ok
  "Run command, return trimmed stdout on success, nil on failure."
  [& args]
  (let [{:keys [exit out]} (apply shell/sh args)]
    (when (zero? exit)
      (let [s (str/trim out)]
        (when-not (str/blank? s) s)))))

(defn which [cmd] (sh-ok "which" cmd))

(def kubectl-bin (or (which "kubectl") "kubectl"))
(def stern-bin (which "stern"))
(def helm-bin (which "helm"))
(def neat-bin (which "kubectl-neat"))

;;; ---------- kubectl plumbing ----------

(defn truthy? [v] (or (true? v) (= "true" v)))

(defn kubectl-args
  "Build common kubectl flags from tool params."
  [{:strs [kubeconfig context namespace all_namespaces]}]
  (cond-> []
    kubeconfig               (into ["--kubeconfig" kubeconfig])
    context                  (into ["--context" context])
    namespace                (into ["-n" namespace])
    (truthy? all_namespaces) (conj "-A")))

(defn run-kubectl
  "Run kubectl, return {:ok bool :out/:err string}.
  Flags go AFTER subcommand args - kubectl 1.34+ is strict about ordering."
  [args & {:keys [opts stdin] :or {opts {}}}]
  (let [cmd  (into [kubectl-bin] (concat args (kubectl-args opts)))
        argv (if stdin (concat cmd [:in stdin]) cmd)
        {:keys [exit out err]} (apply shell/sh argv)]
    (if (zero? exit)
      {:ok true :out (str/trim out)}
      {:ok false :err (str/trim (str err "\n" out))})))

(defn run-kubectl-json
  "Run kubectl -o json, optionally pipe through kubectl-neat.
  neat? defaults to false - enable for spec-focused output (strips status)."
  [args & {:keys [opts neat?] :or {opts {} neat? false}}]
  (let [cmd  (into [kubectl-bin] (concat args ["-o" "json"] (kubectl-args opts)))
        {:keys [exit out err]} (apply shell/sh cmd)]
    (if-not (zero? exit)
      {:ok false :err (str/trim (str err "\n" out))}
      (let [cleaned (if (and neat? neat-bin)
                      (let [r (shell/sh neat-bin "-f" "-" "-o" "json" :in out)]
                        (if (zero? (:exit r)) (str/trim (:out r)) out))
                      out)]
        {:ok true :data (json/parse-string cleaned)}))))

;;; ---------- Summary extraction ----------

(defn pod-summary [pod]
  (let [meta   (get pod "metadata")
        status (get pod "status")
        cstatuses (get status "containerStatuses" [])
        restarts (reduce + 0 (map #(get % "restartCount" 0) cstatuses))
        ready    (count (filter #(get % "ready") cstatuses))
        total    (count cstatuses)
        phase    (get status "phase" "Unknown")
        ;; detect CrashLoop, ImagePull, etc. from container state
        state-reason (some (fn [cs]
                             (or (get-in cs ["state" "waiting" "reason"])
                                 (get-in cs ["state" "terminated" "reason"])))
                          cstatuses)]
    {:name      (get meta "name")
     :namespace (get meta "namespace")
     :status    (or state-reason phase)
     :ready     (str ready "/" total)
     :restarts  restarts
     :node      (get-in pod ["spec" "nodeName"])
     :age       (get-in meta ["creationTimestamp"])}))

(defn deployment-summary [dep]
  (let [meta   (get dep "metadata")
        status (get dep "status")
        spec   (get dep "spec")]
    {:name       (get meta "name")
     :namespace  (get meta "namespace")
     :ready      (str (get status "readyReplicas" 0) "/" (get spec "replicas" 0))
     :up-to-date (get status "updatedReplicas" 0)
     :available  (get status "availableReplicas" 0)
     :age        (get-in meta ["creationTimestamp"])}))

(defn service-summary [svc]
  (let [meta (get svc "metadata")
        spec (get svc "spec")]
    {:name       (get meta "name")
     :namespace  (get meta "namespace")
     :type       (get spec "type")
     :cluster-ip (get spec "clusterIP")
     :ports      (mapv (fn [p] (str (get p "port") "/" (get p "protocol")))
                       (get spec "ports" []))}))

(defn generic-summary [resource]
  (let [meta (get resource "metadata")]
    {:name      (get meta "name")
     :namespace (get meta "namespace")
     :kind      (get resource "kind")
     :age       (get-in meta ["creationTimestamp"])}))

(defn summarize-resource [resource]
  (case (get resource "kind")
    "Pod"        (pod-summary resource)
    "Deployment" (deployment-summary resource)
    "Service"    (service-summary resource)
    (generic-summary resource)))

(defn summarize-list [data]
  (let [items (get data "items" [])]
    (mapv summarize-resource items)))

;;; ---------- Secrets masking ----------

(defn mask-secret-data [resource]
  (if (= "Secret" (get resource "kind"))
    (-> resource
        (update "data"       #(when % (zipmap (keys %) (repeat "***REDACTED***"))))
        (update "stringData" #(when % (zipmap (keys %) (repeat "***REDACTED***")))))
    resource))

(defn mask-secrets-in-list [data]
  (if (= "List" (get data "kind"))
    (update data "items" #(mapv mask-secret-data %))
    (mask-secret-data data)))

;;; ---------- Output helpers ----------

(def max-output-chars 50000)

(defn truncate [text]
  (if (< (count text) max-output-chars) text
      (str (subs text 0 max-output-chars) "\n\n... (truncated at " max-output-chars " chars)")))

(defn text-result [text]
  {:content [{:type "text" :text (truncate text)}]})

(defn error-result [text]
  {:content [{:type "text" :text text}] :isError true})

(defn json-result [data]
  (text-result (json/generate-string data {:pretty true})))

;;; ---------- Tool handlers ----------

(defn do-contexts [{:strs [kubeconfig]}]
  (let [args (cond-> ["config" "get-contexts"]
               kubeconfig (into ["--kubeconfig" kubeconfig]))
        {:keys [exit out err]} (apply shell/sh kubectl-bin args)]
    (if (zero? exit)
      (text-result (str/trim out))
      (error-result (str "Error: " (str/trim err))))))

(defn do-resources-list [{:strs [kind label_selector field_selector output] :as opts}]
  (let [full?  (= "full" output)
        args   (cond-> ["get" kind]
                 label_selector (into ["-l" label_selector])
                 field_selector (into ["--field-selector" field_selector]))
        {:keys [ok data err]} (run-kubectl-json args :opts opts :neat? full?)]
    (if-not ok
      (error-result (str "kubectl error: " err))
      (let [masked (mask-secrets-in-list data)]
        (if full?
          (json-result masked)
          (json-result (summarize-list masked)))))))

(defn do-resources-get [{:strs [kind name] :as opts}]
  (let [{:keys [ok data err]} (run-kubectl-json ["get" kind name] :opts opts :neat? true)]
    (if ok
      (json-result (mask-secret-data data))
      (error-result (str "kubectl error: " err)))))

(defn do-resources-apply [{:strs [resource] :as opts}]
  (let [{:keys [ok out err]} (run-kubectl ["apply" "-f" "-"] :opts opts :stdin resource)]
    (if ok (text-result out) (error-result (str "kubectl apply error: " err)))))

(defn do-resources-delete [{:strs [kind name] :as opts}]
  (let [{:keys [ok out err]} (run-kubectl ["delete" kind name] :opts opts)]
    (if ok (text-result out) (error-result (str "kubectl delete error: " err)))))

(defn do-pod-logs [{:strs [query tail since container include exclude] :as opts}]
  (let [{:strs [kubeconfig context namespace]} opts]
    (if stern-bin
      ;; stern: multi-pod regex matching, structured output
      (let [args (cond-> [stern-bin query "--no-follow" "--color" "never"
                          "--tail" (str (or tail 100))]
                   since      (into ["--since" since])
                   container  (into ["--container" container])
                   include    (into ["--include" include])
                   exclude    (into ["--exclude" exclude])
                   namespace  (into ["--namespace" namespace])
                   kubeconfig (into ["--kubeconfig" kubeconfig])
                   context    (into ["--context" context]))
            {:keys [exit out err]} (apply shell/sh args)]
        (cond
          (zero? exit)
          (text-result (if (str/blank? out) "No logs found." out))

          (re-find #"(?i)no pod|not found" (str err out))
          (text-result "No pods found matching the query.")

          :else
          (error-result (str "stern error: " (str/trim (str err "\n" out))))))
      ;; fallback: kubectl logs (single pod only)
      (let [args (cond-> ["logs" query]
                   tail      (into ["--tail" (str tail)])
                   since     (into ["--since" since])
                   container (into ["-c" container]))
            {:keys [ok out err]} (run-kubectl args :opts opts)]
        (if ok
          (text-result (if (str/blank? out) "No logs found." out))
          (error-result (str "kubectl logs error: " err)))))))

(defn do-pod-exec [{:strs [name command container] :as opts}]
  (let [args (cond-> ["exec" name]
               container (into ["-c" container])
               true      (into ["--" "sh" "-c" command]))
        {:keys [ok out err]} (run-kubectl args :opts opts)]
    (if ok (text-result out) (error-result (str "kubectl exec error: " err)))))

(defn do-events [{:strs [kind name] :as opts}]
  (let [args (cond-> ["get" "events" "--sort-by=.lastTimestamp"]
               (and kind name)
               (into ["--field-selector"
                      (str "involvedObject.kind=" kind
                           ",involvedObject.name=" name)]))
        {:keys [ok out err]} (run-kubectl args :opts opts)]
    (if ok
      (text-result (if (str/blank? out) "No events found." out))
      (error-result (str "kubectl error: " err)))))

(defn do-describe [{:strs [kind name] :as opts}]
  (let [{:keys [ok out err]} (run-kubectl ["describe" kind name] :opts opts)]
    (if ok (text-result out) (error-result (str "kubectl describe error: " err)))))

(defn do-api-resources [opts]
  (let [{:keys [ok out err]} (run-kubectl ["api-resources" "--sort-by=kind"] :opts opts)]
    (if ok (text-result out) (error-result (str "kubectl error: " err)))))

(defn do-helm-list [{:strs [namespace] :as opts}]
  (if-not helm-bin
    (error-result "helm is not installed")
    (let [{:strs [kubeconfig context all_namespaces]} opts
          args (cond-> [helm-bin "list" "-o" "json"]
                 kubeconfig                      (into ["--kubeconfig" kubeconfig])
                 context                         (into ["--kube-context" context])
                 namespace                       (into ["-n" namespace])
                 (or (truthy? all_namespaces)
                     (not namespace))             (conj "-A"))
          {:keys [exit out err]} (apply shell/sh args)]
      (if (zero? exit)
        (text-result out)
        (error-result (str "helm error: " (str/trim err)))))))

(defn do-helm-status [{:strs [name] :as opts}]
  (if-not helm-bin
    (error-result "helm is not installed")
    (let [{:strs [kubeconfig context namespace]} opts
          args (cond-> [helm-bin "status" name "-o" "json"]
                 kubeconfig (into ["--kubeconfig" kubeconfig])
                 context    (into ["--kube-context" context])
                 namespace  (into ["-n" namespace]))
          {:keys [exit out err]} (apply shell/sh args)]
      (if (zero? exit)
        (text-result out)
        (error-result (str "helm error: " (str/trim err)))))))

;; --- Watch / diff ---

(defn item-key [item]
  (str (get-in item ["metadata" "namespace"]) "/" (get-in item ["metadata" "name"])))

(defn diff-resources [prev-items curr-items]
  (let [prev-map (into {} (map (fn [i] [(item-key i) i]) prev-items))
        curr-map (into {} (map (fn [i] [(item-key i) i]) curr-items))
        pks (set (keys prev-map))
        cks (set (keys curr-map))
        added   (set/difference cks pks)
        removed (set/difference pks cks)
        changed (filter #(not= (get prev-map %) (get curr-map %))
                        (set/intersection pks cks))]
    {:added   (mapv curr-map added)
     :removed (mapv prev-map removed)
     :changed (mapv (fn [k] {:key k :current (get curr-map k)}) changed)
     :total   (count curr-items)}))

(defn watch-key [{:strs [kind namespace label_selector kubeconfig context]}]
  (str kind "|" namespace "|" label_selector "|" kubeconfig "|" context))

(defn do-resources-watch [{:strs [kind label_selector field_selector] :as opts}]
  (let [args (cond-> ["get" kind]
               label_selector (into ["-l" label_selector])
               field_selector (into ["--field-selector" field_selector]))
        {:keys [ok data err]} (run-kubectl-json args :opts opts)]
    (if-not ok
      (error-result (str "kubectl error: " err))
      (let [items (get (mask-secrets-in-list data) "items" [])
            k     (watch-key opts)
            prev  (get @watch-cache k)
            _     (swap! watch-cache assoc k {:timestamp (System/currentTimeMillis) :items items})]
        (if-not prev
          (text-result (str "Snapshot captured (" (count items) " items). Next call returns changes only.\n\n"
                            (json/generate-string (summarize-list (mask-secrets-in-list data)) {:pretty true})))
          (let [diff    (diff-resources (:items prev) items)
                elapsed (/ (- (System/currentTimeMillis) (:timestamp prev)) 1000.0)]
            (if (and (empty? (:added diff)) (empty? (:removed diff)) (empty? (:changed diff)))
              (text-result (str "No changes (" (format "%.1f" elapsed) "s ago). Total: " (:total diff)))
              (json-result {:since_seconds_ago elapsed
                            :added_count   (count (:added diff))
                            :removed_count (count (:removed diff))
                            :changed_count (count (:changed diff))
                            :total         (:total diff)
                            :added         (mapv summarize-resource (:added diff))
                            :removed       (mapv summarize-resource (:removed diff))
                            :changed       (mapv (fn [c] (update c :current summarize-resource))
                                                 (:changed diff))}))))))))

;;; ---------- Tool definitions ----------

(def tools
  [{:name "k8s-contexts"
    :description "List available Kubernetes contexts from kubeconfig. Use to discover clusters."
    :inputSchema
    {:type "object"
     :properties {:kubeconfig {:type "string" :description "Path to kubeconfig file (default: ~/.kube/config)"}}}}

   {:name "k8s-resources-list"
    :description "List Kubernetes resources of a given kind. Returns summary by default (name, status, ready, restarts, age). Use output=full for complete JSON. Secrets are redacted. Works with any resource kind including CRDs."
    :inputSchema
    {:type "object"
     :properties {:kind           {:type "string" :description "Resource kind: pods, deployments, services, configmaps, or any CRD"}
                  :namespace      {:type "string" :description "Namespace (omit for default)"}
                  :all_namespaces {:type "boolean" :description "List across all namespaces"}
                  :label_selector {:type "string" :description "Label selector (e.g. app=nginx)"}
                  :field_selector {:type "string" :description "Field selector (e.g. status.phase=Running)"}
                  :output         {:type "string" :description "Output format: 'summary' (default) or 'full' for complete JSON"}
                  :kubeconfig     {:type "string" :description "Path to kubeconfig file"}
                  :context        {:type "string" :description "Kubeconfig context to use"}}
     :required ["kind"]}}

   {:name "k8s-resources-get"
    :description "Get a specific Kubernetes resource by name. Returns cleaned JSON. Secrets are redacted."
    :inputSchema
    {:type "object"
     :properties {:kind       {:type "string" :description "Resource kind"}
                  :name       {:type "string" :description "Resource name"}
                  :namespace  {:type "string" :description "Namespace"}
                  :kubeconfig {:type "string" :description "Path to kubeconfig file"}
                  :context    {:type "string" :description "Kubeconfig context to use"}}
     :required ["kind" "name"]}}

   {:name "k8s-resources-apply"
    :description "Create or update a Kubernetes resource from YAML/JSON manifest (kubectl apply)."
    :inputSchema
    {:type "object"
     :properties {:resource   {:type "string" :description "YAML or JSON manifest to apply"}
                  :namespace  {:type "string" :description "Namespace override"}
                  :kubeconfig {:type "string" :description "Path to kubeconfig file"}
                  :context    {:type "string" :description "Kubeconfig context to use"}}
     :required ["resource"]}}

   {:name "k8s-resources-delete"
    :description "Delete a Kubernetes resource. Use with caution."
    :inputSchema
    {:type "object"
     :properties {:kind       {:type "string" :description "Resource kind"}
                  :name       {:type "string" :description "Resource name"}
                  :namespace  {:type "string" :description "Namespace"}
                  :kubeconfig {:type "string" :description "Path to kubeconfig file"}
                  :context    {:type "string" :description "Kubeconfig context to use"}}
     :required ["kind" "name"]}}

   {:name "k8s-pod-logs"
    :description "Get logs from pods matching a query. Uses stern for multi-pod regex matching when available, falls back to kubectl logs. Returns last N lines."
    :inputSchema
    {:type "object"
     :properties {:query      {:type "string" :description "Pod name regex or resource/name (e.g. 'nginx', 'deploy/myapp')"}
                  :namespace  {:type "string" :description "Namespace"}
                  :tail       {:type "integer" :description "Lines from end (default: 100)"}
                  :since      {:type "string" :description "Look back duration (e.g. 5m, 1h, 30s)"}
                  :container  {:type "string" :description "Container name filter"}
                  :include    {:type "string" :description "Regex: only lines matching this"}
                  :exclude    {:type "string" :description "Regex: exclude lines matching this"}
                  :kubeconfig {:type "string" :description "Path to kubeconfig file"}
                  :context    {:type "string" :description "Kubeconfig context to use"}}
     :required ["query"]}}

   {:name "k8s-pod-exec"
    :description "Execute a command in a running pod container."
    :inputSchema
    {:type "object"
     :properties {:name       {:type "string" :description "Pod name"}
                  :command    {:type "string" :description "Command string (passed to sh -c)"}
                  :container  {:type "string" :description "Container name (if pod has multiple)"}
                  :namespace  {:type "string" :description "Namespace"}
                  :kubeconfig {:type "string" :description "Path to kubeconfig file"}
                  :context    {:type "string" :description "Kubeconfig context to use"}}
     :required ["name" "command"]}}

   {:name "k8s-events"
    :description "List Kubernetes events. Shows warnings, errors, recent activity. Can filter by resource."
    :inputSchema
    {:type "object"
     :properties {:namespace      {:type "string" :description "Namespace"}
                  :all_namespaces {:type "boolean" :description "All namespaces"}
                  :kind           {:type "string" :description "Filter by involved object kind (e.g. Pod, Deployment)"}
                  :name           {:type "string" :description "Filter by involved object name"}
                  :kubeconfig     {:type "string" :description "Path to kubeconfig file"}
                  :context        {:type "string" :description "Kubeconfig context to use"}}}}

   {:name "k8s-describe"
    :description "Describe a Kubernetes resource in human-readable format with events (kubectl describe)."
    :inputSchema
    {:type "object"
     :properties {:kind       {:type "string" :description "Resource kind"}
                  :name       {:type "string" :description "Resource name"}
                  :namespace  {:type "string" :description "Namespace"}
                  :kubeconfig {:type "string" :description "Path to kubeconfig file"}
                  :context    {:type "string" :description "Kubeconfig context to use"}}
     :required ["kind" "name"]}}

   {:name "k8s-api-resources"
    :description "List available API resource types in the cluster, including CRDs. For discovering what resources exist."
    :inputSchema
    {:type "object"
     :properties {:kubeconfig {:type "string" :description "Path to kubeconfig file"}
                  :context    {:type "string" :description "Kubeconfig context to use"}}}}

   {:name "k8s-helm-list"
    :description "List Helm releases. Shows deployed charts, versions, status."
    :inputSchema
    {:type "object"
     :properties {:namespace      {:type "string" :description "Namespace (default: all)"}
                  :all_namespaces {:type "boolean" :description "All namespaces (default: true)"}
                  :kubeconfig     {:type "string" :description "Path to kubeconfig file"}
                  :context        {:type "string" :description "Kubeconfig context to use"}}}}

   {:name "k8s-helm-status"
    :description "Get Helm release status including chart version, app version, deployment status."
    :inputSchema
    {:type "object"
     :properties {:name       {:type "string" :description "Release name"}
                  :namespace  {:type "string" :description "Release namespace"}
                  :kubeconfig {:type "string" :description "Path to kubeconfig file"}
                  :context    {:type "string" :description "Kubeconfig context to use"}}
     :required ["name"]}}

   {:name "k8s-resources-watch"
    :description "Watch resources for changes. First call captures a snapshot; subsequent calls return only additions, removals, and modifications since last check. For monitoring pod restarts, rollouts, etc."
    :inputSchema
    {:type "object"
     :properties {:kind           {:type "string" :description "Resource kind (e.g. pods, deployments)"}
                  :namespace      {:type "string" :description "Namespace"}
                  :all_namespaces {:type "boolean" :description "All namespaces"}
                  :label_selector {:type "string" :description "Label selector"}
                  :field_selector {:type "string" :description "Field selector"}
                  :kubeconfig     {:type "string" :description "Path to kubeconfig file"}
                  :context        {:type "string" :description "Kubeconfig context to use"}}
     :required ["kind"]}}])

;;; ---------- MCP Server ----------

(def server-info {:name "k8s" :version "0.1.0"})

(defn handle-tool-call [tool-name args]
  (try
    (case tool-name
      "k8s-contexts"         (do-contexts args)
      "k8s-resources-list"   (do-resources-list args)
      "k8s-resources-get"    (do-resources-get args)
      "k8s-resources-apply"  (do-resources-apply args)
      "k8s-resources-delete" (do-resources-delete args)
      "k8s-pod-logs"         (do-pod-logs args)
      "k8s-pod-exec"         (do-pod-exec args)
      "k8s-events"           (do-events args)
      "k8s-describe"         (do-describe args)
      "k8s-api-resources"    (do-api-resources args)
      "k8s-helm-list"        (do-helm-list args)
      "k8s-helm-status"      (do-helm-status args)
      "k8s-resources-watch"  (do-resources-watch args)
      (error-result (str "Unknown tool: " tool-name)))
    (catch Exception e
      (error-result (str "Error: " (.getMessage e))))))

(defn handle-request [{:strs [id method params]}]
  (case method
    "initialize"
    {:jsonrpc "2.0" :id id
     :result {:protocolVersion "2024-11-05"
              :capabilities    {:tools {}}
              :serverInfo      server-info}}

    "notifications/initialized" nil

    "tools/list"
    {:jsonrpc "2.0" :id id
     :result {:tools tools}}

    "tools/call"
    (let [{tool "name" args "arguments"} params]
      {:jsonrpc "2.0" :id id
       :result (handle-tool-call tool (or args {}))})

    nil))

;;; ---------- Main loop ----------

(doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (when-not (str/blank? line)
    (when-let [res (handle-request (json/parse-string line))]
      (println (json/generate-string res))
      (flush))))
