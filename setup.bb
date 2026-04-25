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
(def os-kind
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond (str/includes? os "mac") :macos
          (str/includes? os "linux") :linux
          :else :other)))

(def mac? (= os-kind :macos))

(defn- claude-desktop-config-dir
  "Where Claude Desktop reads its config JSON on this platform."
  []
  (case os-kind
    :macos (str (System/getenv "HOME") "/Library/Application Support/Claude/")
    :linux (str (or (System/getenv "XDG_CONFIG_HOME")
                    (str (System/getenv "HOME") "/.config"))
                "/Claude/")
    nil))

(defn- copilot-config-dir
  "Where GitHub Copilot CLI reads its user config.
  $COPILOT_HOME wins if set; otherwise $HOME/.copilot. Returns nil if
  $HOME is also missing so the Copilot block is skipped entirely."
  []
  (let [override (System/getenv "COPILOT_HOME")
        home (System/getenv "HOME")]
    (cond
      (and override (not (str/blank? override))) override
      home (str home "/.copilot")
      :else nil)))

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
                   :platform :darwin}
   "qlik-kb"      {:command "qlik-kb/server.bb"
                   :platform :all}})

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
   Each :servers entry in local-config is flat key/value config written
   verbatim to the tool's config.edn, minus the reserved :disabled? flag
   which setup.bb strips. Default is enabled; :disabled? true skips."
  [local-config]
  (let [local-servers (:servers local-config {})]
    (reduce-kv
     (fn [acc name {:keys [command platform]}]
       (if-not (platform-matches? platform)
         acc
         (let [local (get local-servers (keyword name) {})
               disabled? (:disabled? local false)
               cfg (dissoc local :disabled?)]
           (if disabled?
             acc
             (do
               (when (seq cfg)
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

(defn- b64-encode
  "UTF-8 base64 encode for safely smuggling large strings through osascript."
  [^String s]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes s "UTF-8")))

(defn- find-claude-tab-osa
  "Return AppleScript that finds first claude.ai tab in BROWSER app.
  Result is 'windowIndex,tabIndex' or empty string."
  [browser]
  (format "tell application \"%s\"
  if (count of windows) = 0 then return \"\"
  set w to 0
  repeat with win in windows
    set w to w + 1
    set t to 0
    repeat with tb in tabs of win
      set t to t + 1
      if URL of tb starts with \"https://claude.ai\" then
        return (w as string) & \",\" & (t as string)
      end if
    end repeat
  end repeat
  return \"\"
end tell" browser))

(defmulti push-claude-web-preferences!
  "Sync AGENTS.md into claude.ai's server-side Personal preferences
  (conversation_preferences on /api/account_profile). Implementation
  dispatches by OS: macOS drives the logged-in browser via osascript,
  Linux copies to clipboard so the user can paste, unknown platforms
  print a manual instruction."
  (fn [_content] os-kind))

(defmethod push-claude-web-preferences! :macos [content]
  (try
    (let [payload (b64-encode (json/generate-string {:conversation_preferences content}))
          js (str "(function(){"
                  "var body=atob('" payload "');"
                  "var x=new XMLHttpRequest();"
                  "x.open('PUT','/api/account_profile',false);"
                  "x.setRequestHeader('Content-Type','application/json');"
                  "x.withCredentials=true;"
                  "x.send(body);"
                  "return x.status+'';"
                  "})()")
          browsers ["Brave Browser" "Google Chrome" "Safari"]
          found (some (fn [b]
                        (let [r (proc/sh ["osascript" "-e" (find-claude-tab-osa b)])
                              loc (str/trim (:out r))]
                          (when (and (zero? (:exit r)) (not (str/blank? loc)))
                            [b loc])))
                      browsers)]
      (if-not found
        (println "  skipped: claude.ai Personal preferences (no claude.ai tab open in Brave/Chrome/Safari)")
        (let [[browser loc] found
              [win tab] (str/split loc #",")
              tmp (java.io.File/createTempFile "dc-sync-" ".scpt")
              osa (format "tell application \"%s\"
  tell tab %s of window %s
    execute javascript %s
  end tell
end tell"
                          browser tab win
                          (pr-str js))]
          (spit tmp osa)
          (let [r (proc/sh ["osascript" (.getAbsolutePath tmp)])
                out (str/trim (:out r))]
            (.delete tmp)
            (cond
              (not (zero? (:exit r)))
              (println (str "  warn: claude.ai sync via " browser " failed: "
                            (str/trim (:err r))))
              (str/starts-with? out "200")
              (println (str "  wrote: claude.ai Personal preferences via " browser))
              :else
              (println (str "  warn: claude.ai sync unexpected response: " out)))))))
    (catch Exception e
      (println (str "  skipped: claude.ai sync errored: " (.getMessage e))))))

(defmethod push-claude-web-preferences! :linux [content]
  (try
    (let [which (fn [cmd] (zero? (:exit (proc/sh ["which" cmd]))))
          clip-cmd (cond
                     (which "wl-copy")  ["wl-copy"]
                     (which "xclip")    ["xclip" "-selection" "clipboard"]
                     (which "xsel")     ["xsel" "--clipboard" "--input"])]
      (if clip-cmd
        (do
          @(proc/process clip-cmd {:in content})
          (println (str "  copied AGENTS.md to clipboard via " (first clip-cmd)
                        "; paste into claude.ai Settings -> Personal preferences")))
        (println "  skipped: claude.ai Personal preferences (install wl-copy / xclip / xsel, or paste ~/.config/eca/AGENTS.md by hand)")))
    (catch Exception e
      (println (str "  skipped: claude.ai push errored: " (.getMessage e))))))

(defmethod push-claude-web-preferences! :default [_content]
  (println "  skipped: claude.ai Personal preferences auto-push not implemented here; paste ~/.config/eca/AGENTS.md into Settings -> Personal preferences manually"))

(defn -main
  "Propagate agents-base.md + :agents-extra to every consumer as copied
  files so a single re-run keeps all in sync. Writes ECA config.json and
  AGENTS.md, Claude Code CLI CLAUDE.md, Copilot copilot-instructions.md,
  Claude Desktop config, and pushes into claude.ai Personal preferences.
  Cleans stale AGENTS.md/CLAUDE.md from the repo dir so only
  agents-base.md lives here."
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

    ;; Remove stale AGENTS.md / CLAUDE.md from repo dir if left over from
    ;; a previous setup run.  The generated content now lives only in the
    ;; consumer directories; the repo should only contain agents-base.md.
    (doseq [f ["AGENTS.md" "CLAUDE.md"]]
      (let [p (java.nio.file.Paths/get (str repo-dir "/" f) (into-array String []))]
        (when (java.nio.file.Files/deleteIfExists p)
          (println (str "  removed stale: " repo-dir "/" f)))))

    ;; Claude Desktop local MCP config (platform-specific path).
    (when-let [dir (claude-desktop-config-dir)]
      (ensure-dir dir)
      (spit (str dir "claude_desktop_config.json")
            (json/generate-string {:mcpServers server-entries} {:pretty true}))
      (println (str "  wrote: " dir "claude_desktop_config.json")))

    ;; Push AGENTS.md into claude.ai's server-side Personal preferences.
    ;; This is the only channel through which rules reach Desktop chat,
    ;; so it runs on every platform (implementation dispatches by OS).
    (push-claude-web-preferences! agents-content)

    ;; Claude Code CLI: user-scope CLAUDE.md, skills symlink, and mcpServers
    ;; merged into ~/.claude.json (preserving other keys like oauth tokens
    ;; and preferences). Claude Desktop's agent/cowork mode runs Claude Code
    ;; under the hood, so fixing Claude Code CLI also fixes that surface.
    (let [claude-dir (str (System/getenv "HOME") "/.claude")
          claude-json (str (System/getenv "HOME") "/.claude.json")]
      (ensure-dir claude-dir)
      (let [dst (str claude-dir "/CLAUDE.md")
            p (java.nio.file.Paths/get dst (into-array String []))]
        (java.nio.file.Files/deleteIfExists p)
        (spit dst agents-content)
        (println (str "  wrote: " dst)))
      (create-symlink (str claude-dir "/skills") skills-dir)
      (println (str "  symlink: ~/.claude/skills -> " skills-dir))
      (when (.exists (io/file claude-json))
        (let [existing (json/parse-string (slurp claude-json) true)
              updated (assoc existing :mcpServers server-entries)]
          (spit claude-json (json/generate-string updated {:pretty true}))
          (println (str "  wrote: " claude-json " (mcpServers)")))))

    ;; GitHub Copilot CLI: user-scope personal instructions, skills symlink,
    ;; and mcpServers merged into ~/.copilot/mcp-config.json (preserving
    ;; servers a user added via /mcp add). Leaves ~/.copilot/config.json
    ;; alone so auth state and trusted_folders stay intact.
    (when-let [copilot-dir (copilot-config-dir)]
      (ensure-dir copilot-dir)
      (let [dst (str copilot-dir "/copilot-instructions.md")
            p (java.nio.file.Paths/get dst (into-array String []))]
        (java.nio.file.Files/deleteIfExists p)
        (spit dst agents-content)
        (println (str "  wrote: " dst)))
      (create-symlink (str copilot-dir "/skills") skills-dir)
      (println (str "  symlink: " copilot-dir "/skills -> " skills-dir))
      (let [mcp-path (str copilot-dir "/mcp-config.json")
            ;; Copilot CLI's zod schema makes args required (must be an
            ;; array) and rejects type "local"; the stdio branch just
            ;; needs {command, args}. String keys throughout so merge
            ;; does not see "k8s" and :k8s as distinct and duplicate.
            copilot-entries (update-vals
                             server-entries
                             (fn [v] {"command" (:command v) "args" []}))
            existing (if (.exists (io/file mcp-path))
                       (json/parse-string (slurp mcp-path))
                       {})
            existing-servers (get existing "mcpServers" {})
            merged-servers (merge existing-servers copilot-entries)
            updated (assoc existing "mcpServers" merged-servers)]
        (spit mcp-path (json/generate-string updated {:pretty true}))
        (println (str "  wrote: " mcp-path " (mcpServers)"))))

    ;; Write generated AGENTS.md into ~/.config/eca/ as a real file.
    ;; The canonical source is agents-base.md + :agents-extra; every
    ;; consumer gets a copy so a single setup.bb run keeps them in sync.
    (let [dst (str eca-dir "/AGENTS.md")
          p (java.nio.file.Paths/get dst (into-array String []))]
      (java.nio.file.Files/deleteIfExists p)
      (spit dst agents-content)
      (println (str "  wrote: " dst)))

    ;; Symlinks
    (create-symlink (str eca-dir "/tools") tools-dir)
    (println (str "  symlink: ~/.config/eca/tools -> " tools-dir))

    (create-symlink (str eca-dir "/skills") skills-dir)
    (println (str "  symlink: ~/.config/eca/skills -> " skills-dir))

    ;; qlik-kb runs an out-of-repo prebuilt binary cached under tools/qlik-kb/bin.
    ;; Warn (non-fatal) when it's missing so first-run or post-pull users know
    ;; to fetch it explicitly rather than discovering the failure inside a chat.
    (when (contains? server-entries "qlik-kb")
      (let [bin (str tools-dir "/qlik-kb/bin/mcp-qlik-kb-server")]
        (when-not (.canExecute (io/file bin))
          (println (str "  warn: qlik-kb binary missing at " bin))
          (println (str "        run: bb " tools-dir "/qlik-kb/update.bb")))))

    (println "Done.")))

(-main)
