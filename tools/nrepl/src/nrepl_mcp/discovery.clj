(ns nrepl-mcp.discovery
  "Discover nREPL servers by walking .nrepl-port files up the directory
   tree and probing ports via the nREPL describe op."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl-mcp.client :as client])
  (:import [java.io File]))

(def ^:private port-filenames
  "Files written by nREPL servers to advertise their port."
  [".nrepl-port"
   ".shadow-cljs/nrepl.port"])

(defn- read-port-file
  "Read a port number from a file, returns nil on failure."
  [^File f]
  (when (.isFile f)
    (try
      (let [s (str/trim (slurp f))]
        (when-not (str/blank? s)
          (Integer/parseInt s)))
      (catch Exception _ nil))))

(defn- walk-ancestors
  "Walk from dir up to filesystem root, collecting directories."
  [^File dir]
  (take-while some? (iterate #(.getParentFile ^File %) dir)))

(defn find-port-files
  "Discover .nrepl-port files starting from start-dir and walking up.
   Returns [{:port :file :project-root}]."
  [start-dir]
  (let [start (io/file (or start-dir (System/getProperty "user.dir")))]
    (->> (walk-ancestors start)
         (mapcat (fn [^File dir]
                   (for [filename port-filenames
                         :let [f (io/file dir filename)
                               port (read-port-file f)]
                         :when port]
                     {:port port
                      :file (.getAbsolutePath f)
                      :project-root (.getAbsolutePath dir)})))
         (distinct)
         (vec))))

(defn- probe-port
  "Connect to an nREPL port and send describe op to determine type.
   Returns {:port :type :project-root :status} or nil on failure.
   Type is one of: :clj :bb :nbb :shadow-cljs :unknown."
  [{:keys [port project-root file]} timeout-ms]
  (try
    (let [conn (client/connect! "localhost" port)
          responses (client/nrepl-op! conn {"op" "describe"} :timeout-ms timeout-ms)
          versions (some #(get % "versions") responses)]
      (client/disconnect! conn)
      (let [repl-type (cond
                        ;; shadow-cljs nREPL advertises shadow-cljs in versions
                        (get versions "shadow-cljs") :shadow-cljs
                        ;; babashka has "babashka" in versions
                        (get versions "babashka") :bb
                        ;; nbb has "nbb" in versions
                        (get versions "nbb") :nbb
                        ;; JVM Clojure has "clojure" and "java" in versions
                        (get versions "clojure") :clj
                        :else :unknown)]
        {:port port
         :type repl-type
         :project-root project-root
         :file file
         :status :connected
         :versions versions}))
    (catch Exception _
      {:port port
       :type :unknown
       :project-root project-root
       :file file
       :status :unreachable})))

(defn discover-ports
  "Find and probe all nREPL ports reachable from start-dir.
   Probes run in parallel with a short timeout.
   Returns [{:port :type :project-root :status}]."
  [& {:keys [start-dir probe-timeout-ms]
      :or {probe-timeout-ms 2000}}]
  (let [port-entries (find-port-files start-dir)]
    (if (empty? port-entries)
      []
      (->> port-entries
           (pmap #(probe-port % probe-timeout-ms))
           (vec)))))
