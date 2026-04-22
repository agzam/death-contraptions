#!/usr/bin/env bb
;; Fetch a pinned release of qlik-trial/mcp-server-knowledge-base into
;; tools/qlik-kb/bin/. Run with no args to track latest, or pass a tag
;; (e.g. v0.3.0) to pin. Skips the download when the current .version
;; already matches the requested tag and the binary is present.
;; Author: Ag Ibragimov - github.com/agzam

(require '[babashka.process :as proc]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def ^:private repo "qlik-trial/mcp-server-knowledge-base")

(def ^:private script-dir
  (-> *file* io/file .getParentFile .getCanonicalPath))

(def ^:private bin-dir (str script-dir "/bin"))
(def ^:private bin-path (str bin-dir "/mcp-qlik-kb-server"))
(def ^:private version-path (str bin-dir "/.version"))

(def ^:private os-name
  (str/lower-case (System/getProperty "os.name")))

(def ^:private os-arch
  (str/lower-case (System/getProperty "os.arch")))

(defn- platform-asset
  "Map Java's os.name/os.arch to the asset names published by the upstream
  release workflow. Only Apple Silicon and x64 Linux are wired up; Intel
  Macs and Windows are left unreachable by design - ask upstream if needed."
  []
  (cond
    (and (str/includes? os-name "mac")
         (or (str/includes? os-arch "aarch64")
             (str/includes? os-arch "arm64")))
    "mcp-qlik-kb-server-darwin-arm64"

    (and (str/includes? os-name "linux")
         (or (str/includes? os-arch "amd64")
             (str/includes? os-arch "x86_64")))
    "mcp-qlik-kb-server-linux-x64"

    :else
    (throw (ex-info (str "qlik-kb: unsupported platform " os-name "/" os-arch) {}))))

(defn- sh!
  "Run a command; throw on non-zero exit so failures surface immediately
  instead of silently corrupting the binary cache."
  [& args]
  (let [r (proc/sh (vec args))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "command failed: " (str/join " " args))
                      {:exit (:exit r)
                       :err (str/trim (str (:err r)))
                       :out (str/trim (str (:out r)))})))
    (str/trim (str (:out r)))))

(defn- current-version []
  (when (.exists (io/file version-path))
    (str/trim (slurp version-path))))

(defn- resolve-tag
  "Use the explicit tag when provided; otherwise ask gh for the latest
  release. Done via gh (not the raw API) so auth and redirects are handled."
  [requested]
  (or requested
      (sh! "gh" "release" "view"
           "--repo" repo
           "--json" "tagName"
           "--jq" ".tagName")))

(defn -main [& args]
  (let [requested (first args)
        tag (resolve-tag requested)
        asset (platform-asset)]
    (if (and (= tag (current-version))
             (.exists (io/file bin-path)))
      (println (str "qlik-kb already on " tag))
      (do
        (.mkdirs (io/file bin-dir))
        (println (str "qlik-kb: downloading " asset " @ " tag))
        (sh! "gh" "release" "download" tag
             "--repo" repo
             "--pattern" asset
             "--output" bin-path
             "--clobber")
        (sh! "chmod" "+x" bin-path)
        (when (str/includes? os-name "mac")
          ;; Gatekeeper quarantine on downloaded unsigned binaries; ignore
          ;; failures because a fresh binary sometimes has no xattr to remove.
          (proc/sh ["xattr" "-d" "com.apple.quarantine" bin-path]))
        (spit version-path tag)
        (println (str "qlik-kb: installed " tag " at " bin-path))))))

(apply -main *command-line-args*)
