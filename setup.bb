#!/usr/bin/env bb

;; Setup script for death-contraptions MCP tools.
;; Generates config files for ECA/Claude Code CLI, Claude Desktop,
;; symlinks tools and skills into ~/.config/eca/.

(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.process :as proc])

(def repo-dir (-> *file* io/file .getParentFile .getCanonicalPath))
(def tools-dir (str repo-dir "/tools"))
(def skills-dir (str repo-dir "/skills"))
(def eca-dir (str (System/getenv "HOME") "/.config/eca"))
(def mac? (str/includes? (System/getProperty "os.name") "Mac"))

;; Server registry: name -> {:command, :platform}
(def servers
  {"elisp-eval" {:command "elisp-eval/server.bb"
                 :platform :all}
   "k8s"        {:command "k8s/server.bb"
                 :platform :all}
   "jxa-browser" {:command "jxa-browser/server.bb"
                  :platform :darwin}
   "slack"       {:command "slack/server.bb"
                  :platform :darwin}
   "splunk"      {:command "splunk/server.bb"
                  :platform :darwin}
   "org-roam-mcp" {:command "org-roam-mcp/start.sh"
                   :platform :all}
   "kitty"        {:command "kitty/server.bb"
                   :platform :darwin}})

(defn load-local-config
  "Read local-config.edn, trying .gpg first (via gpg --decrypt), then plain."
  []
  (let [gpg-file (str repo-dir "/local-config.edn.gpg")
        plain-file (str repo-dir "/local-config.edn")]
    (cond
      (.exists (io/file gpg-file))
      (let [result (proc/sh ["gpg" "--quiet" "--decrypt" gpg-file])]
        (if (zero? (:exit result))
          (edn/read-string (:out result))
          (do (println "Warning: gpg decrypt failed, using defaults")
              {})))

      (.exists (io/file plain-file))
      (edn/read-string (slurp plain-file))

      :else
      (do (println "No local-config.edn found, using defaults")
          {}))))

(defn platform-matches?
  "Check if a server's platform requirement matches the current OS."
  [platform]
  (or (= platform :all)
      (and mac? (= platform :darwin))))

(defn- server-config-path
  "Resolve a server's config.edn path from its command entry."
  [server-name]
  (let [cmd-path (get-in servers [server-name :command])
        server-dir (-> (io/file tools-dir cmd-path) .getParentFile .getCanonicalPath)]
    (str server-dir "/config.edn")))

(defn- write-server-config!
  "Merge :config from local-config into the server's config.edn.
   Preserves existing defaults, overrides with local values."
  [server-name config-map]
  (let [path (server-config-path server-name)
        existing (if (.exists (io/file path))
                   (edn/read-string (slurp path))
                   {})
        merged (merge existing config-map)]
    (spit path (pr-str merged))
    (println (str "  wrote: " path))))

(defn build-server-entries
  "Build the mcpServers map for config.json.
   Writes per-server config.edn files from :config in local-config."
  [local-config]
  (let [local-servers (:servers local-config {})]
    (reduce-kv
     (fn [acc name {:keys [command platform]}]
       (if-not (platform-matches? platform)
         acc
         (let [local (get local-servers (keyword name) {})
               enabled? (get local :enabled true)]
           (if-not enabled?
             acc
             (do
               (when-let [cfg (:config local)]
                 (write-server-config! name cfg))
               (assoc acc name {:command (str tools-dir "/" command)}))))))
     {}
     servers)))

(defn build-config
  "Build the full ECA config.json content.
  Merges ECA-specific settings from local-config :eca key."
  [server-entries local-config]
  (merge (:eca local-config {})
         {:mcpServers server-entries}))

(defn build-agents-md
  "Concatenate base AGENTS.md with local extra content."
  [local-config]
      (let [base (slurp (str repo-dir "/agents-base.md"))
        extra (:agents-extra local-config)]
    (if extra
      (str base "\n" extra)
      base)))

(defn ensure-dir
  "Wrapper for mkdirs - needed because setup must create nested config dirs
  that may not exist yet (mkdir -p semantics)."
  [path]
  (.mkdirs (io/file path)))

(defn create-symlink
  "Create a symlink, removing existing one if present."
  [link target]
  (let [link-path (java.nio.file.Paths/get link (into-array String []))
        target-path (java.nio.file.Paths/get target (into-array String []))]
    (when (java.nio.file.Files/exists link-path (into-array java.nio.file.LinkOption []))
      (java.nio.file.Files/delete link-path))
    (java.nio.file.Files/createSymbolicLink link-path target-path (into-array java.nio.file.attribute.FileAttribute []))))

(defn -main
  "Run the full setup: load local config, build and write ECA config.json and
  AGENTS.md, optionally write Claude Desktop config, and create symlinks for
  tools/skills into ~/.config/eca/."
  []
  (println "Setting up death-contraptions...")
  (println (str "  repo: " repo-dir))
  (println (str "  platform: " (if mac? "macOS" "Linux")))

  (let [local-config (load-local-config)
        server-entries (build-server-entries local-config)
        config (build-config server-entries local-config)
        agents-content (build-agents-md local-config)]

    ;; Ensure ~/.config/eca/ exists
    (ensure-dir eca-dir)

    ;; Write config.json (remove old read-only file if present)
    (let [config-path (str eca-dir "/config.json")]
      (when (.exists (io/file config-path))
        (.setWritable (io/file config-path) true))
      (spit config-path (json/generate-string config {:pretty true}))
      (println (str "  wrote: " config-path))
      (println (str "  servers: " (str/join ", " (keys server-entries)))))

    ;; Write AGENTS.md (remove old read-only file if present)
    (let [agents-path (str eca-dir "/AGENTS.md")]
      (when (.exists (io/file agents-path))
        (.setWritable (io/file agents-path) true))
      (spit agents-path agents-content)
      (println (str "  wrote: " agents-path)))

    ;; Claude Desktop config (macOS only)
    (when mac?
      (let [claude-dir (str (System/getenv "HOME") "/Library/Application Support/Claude/")]
        (ensure-dir claude-dir)
        (spit (str claude-dir "claude_desktop_config.json")
              (json/generate-string {:mcpServers server-entries} {:pretty true}))
        (println (str "  wrote: " claude-dir "claude_desktop_config.json"))))

    ;; CLAUDE.md symlink for Claude Code CLI
    (let [claude-dir (str (System/getenv "HOME") "/.claude")]
      (ensure-dir claude-dir)
      (create-symlink (str claude-dir "/CLAUDE.md") (str eca-dir "/AGENTS.md"))
      (println (str "  symlink: ~/.claude/CLAUDE.md -> ~/.config/eca/AGENTS.md")))

    ;; Symlinks
    (create-symlink (str eca-dir "/tools") tools-dir)
    (println (str "  symlink: ~/.config/eca/tools -> " tools-dir))

    (create-symlink (str eca-dir "/skills") skills-dir)
    (println (str "  symlink: ~/.config/eca/skills -> " skills-dir))

    (println "Done.")))

(-main)
