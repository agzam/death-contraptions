#!/usr/bin/env bb
;; Playwright MCP launcher. Reads config.edn (populated by setup.bb from
;; local-config.edn :servers {:playwright {...}}) and execs
;; `npx @playwright/mcp@<version>` with the matching flags. proc/exec replaces
;; this process, so the MCP inherits the parent's stdio directly - no pumping.
;;
;; This drives a real browser against logged-in Qlik tenants, so keep it scoped
;; to a dedicated profile and the Qlik domains via :allowed-origins. See
;; skills/qlik-verify/SKILL.md and the awesome-qlik-ai Playwright MCP assessment.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.process :as proc])

(def ^:private script-dir
  ;; canonicalize first so a relative *file* (e.g. load-file from a test)
  ;; still resolves a non-nil parent dir.
  (-> *file* io/file .getCanonicalFile .getParentFile .getCanonicalPath))

(def ^:private config
  (let [f (io/file script-dir "config.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      {})))

(defn- die [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

(defn expand-home
  "npx/Playwright do not expand ~, so resolve a leading ~ from $HOME here."
  [path]
  (when path
    (if (str/starts-with? path "~")
      (str (System/getenv "HOME") (subs path 1))
      path)))

(defn build-args
  "Pure: turn a playwright config map into the npx argv. Side-effect free so
  server_test.bb can assert the exact vector. :mode :attach connects to an
  existing browser via --cdp-endpoint; :own-profile uses a persistent
  --user-data-dir. :extra-args is appended verbatim for version-specific flags."
  [{:keys [version mode user-data-dir cdp-endpoint browser executable-path
           headless? isolated? viewport allowed-origins blocked-origins
           output-dir caps save-trace? extra-args]
    :or {version "latest"
         mode :own-profile
         user-data-dir "~/.cache/qlik-verify/chrome-profile"
         output-dir "~/.cache/qlik-verify/output"
         viewport {:w 1440 :h 900}
         allowed-origins ["https://*.pte.qlikdev.com"
                          "https://*.qlik-stage.com"]}}]
  (let [pkg (str "@playwright/mcp@" version)
        vp (when viewport
             (if (map? viewport)
               (str (:w viewport) "," (:h viewport))
               (str viewport)))]
    (cond-> ["npx" "-y" pkg]
      (= mode :attach)      (into ["--cdp-endpoint" cdp-endpoint])
      (and (not= mode :attach)
           user-data-dir)   (into ["--user-data-dir" (expand-home user-data-dir)])
      isolated?             (conj "--isolated")
      browser               (into ["--browser" browser])
      executable-path       (into ["--executable-path" (expand-home executable-path)])
      headless?             (conj "--headless")
      vp                    (into ["--viewport-size" vp])
      (seq allowed-origins) (into ["--allowed-origins" (str/join ";" allowed-origins)])
      (seq blocked-origins) (into ["--blocked-origins" (str/join ";" blocked-origins)])
      output-dir            (into ["--output-dir" (expand-home output-dir)])
      (seq caps)            (into ["--caps" (str/join "," caps)])
      save-trace?           (conj "--save-trace")
      (seq extra-args)      (into (mapv str extra-args)))))

(defn -main [& _]
  (when-not (zero? (:exit (proc/sh ["which" "npx"])))
    (die "playwright: npx not found on PATH. Install Node.js so `npx @playwright/mcp` can run."))
  (proc/exec (build-args config)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
