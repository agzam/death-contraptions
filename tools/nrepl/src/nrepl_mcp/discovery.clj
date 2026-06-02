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

(defn default-registry-dir
  "Flat dir where out-of-tree launchers (e.g. browser-repl in a sibling repo)
   advertise their nREPL port so the MCP can find sessions it would never reach
   by walking up from its own CWD. Override with $NREPL_MCP_PORT_DIR."
  []
  (or (System/getenv "NREPL_MCP_PORT_DIR")
      (str (System/getProperty "user.home") "/.cache/nrepl-ports")))

(defn registry-port-files
  "Port entries advertised in a flat registry dir: every *.port file's content
   is a bare port number. Returns [{:port :file :project-root}]."
  ([] (registry-port-files (default-registry-dir)))
  ([dir]
   (let [d (io/file dir)]
     (if-not (.isDirectory d)
       []
       (vec (for [^File f (.listFiles d)
                  :let [port (when (str/ends-with? (.getName f) ".port")
                               (read-port-file f))]
                  :when port]
              {:port port
               :file (.getAbsolutePath f)
               :project-root (.getAbsolutePath d)}))))))

(defn find-port-files
  "Discover nREPL ports: .nrepl-port files from start-dir walking up, PLUS any
   advertised in the registry dir (for sessions outside the CWD subtree).
   Deduped by port (walked entries win). Returns [{:port :file :project-root}]."
  [start-dir]
  (let [start  (io/file (or start-dir (System/getProperty "user.dir")))
        walked (mapcat (fn [^File dir]
                         (for [filename port-filenames
                               :let [f (io/file dir filename)
                                     port (read-port-file f)]
                               :when port]
                           {:port port
                            :file (.getAbsolutePath f)
                            :project-root (.getAbsolutePath dir)}))
                       (walk-ancestors start))]
    (->> (concat walked (registry-port-files))
         (group-by :port)
         vals
         (mapv first))))

(defn classify-repl-type
  "Map an nREPL describe :versions map to a repl type keyword. Pure.
   nbb's nREPL reports {\"nbb-nrepl\" .. \"node\" ..} and NO \"clojure\" key
   (this is why the old (get versions \"nbb\") check left it :unknown, killing
   auto-await). shadow-cljs and bb/clj advertise their own keys.
   One of :clj :bb :nbb :shadow-cljs :unknown."
  [versions]
  (cond
    (get versions "shadow-cljs")                         :shadow-cljs
    (get versions "babashka")                            :bb
    (or (get versions "nbb") (get versions "nbb-nrepl")) :nbb
    (get versions "clojure")                             :clj
    (get versions "node")                                :nbb ; nbb: node, no clojure
    :else                                                :unknown))

(defn- probe-port
  "Connect to an nREPL port and send describe op to determine type.
   Caches the connection for reuse by subsequent eval calls.
   Returns {:port :type :project-root :status} or nil on failure.
   Type is one of: :clj :bb :nbb :shadow-cljs :unknown."
  [{:keys [port project-root file]} timeout-ms]
  (try
    (let [conn (client/get-connection! "localhost" port)
          responses (client/nrepl-op! conn {"op" "describe"} :timeout-ms timeout-ms)
          versions (some #(get % "versions") responses)
          repl-type (classify-repl-type versions)]
      {:port port
       :type repl-type
       :project-root project-root
       :file file
       :status :connected
       :versions versions})
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
