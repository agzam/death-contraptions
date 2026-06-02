;; browser-repl: the nbb + Playwright stdlib loaded into a live nREPL session.
;;
;; Driven via the nrepl MCP (`nrepl-eval`), which auto-awaits promises on
;; nbb/cljs ports, so every async fn here returns a promesa promise and the
;; agent reads the resolved value directly. The launcher (launch.bb) loads this
;; file, applies a session mode, and writes .nrepl-port.
;;
;; Design goals (see plan.md):
;; - liveness: browser/context/page + capture atoms persist across evals
;; - token economy: targeted extractors + scoped `aria` eyes, not huge snapshots
;; - live correlation: network/console capture into atoms, polled between turns
;; - fire-and-poll: `run-job!` kicks long ops off async, stash in `jobs`, poll
;;
;; Gotcha baked in: `eval-js` takes a JS EXPRESSION string, not a bare arrow
;; (a "() => ..." string serializes to the function, i.e. nil). See its doc.

(ns browser-repl
  (:require ["playwright$default" :as pw]
            [promesa.core :as p]
            [clojure.string :as str]))

;; --------------------------------------------------------------------------
;; State (defonce so a re-load keeps the live browser + captures)
;; --------------------------------------------------------------------------

(def default-config
  {:mode :fresh                                   ; :fresh | :persistent | :attach
   :headless? false
   :user-data-dir "~/.cache/qlik-verify/browser-repl-profile"
   :cdp-endpoint nil                              ; required for :attach
   :viewport {:width 1440 :height 900}})

(defonce state (atom {:config default-config :browser nil :context nil :page nil}))
(defonce net (atom []))
(defonce console (atom []))
(defonce jobs (atom {}))
(defonce ^:private capturing (atom #{}))

(def correlation-header-keys
  "Request-side keys that backend watchers join on. vohi responses carry none
   of these, so we capture them from the REQUEST (see plan.md correlation note)."
  ["x-request-id" "traceparent" "x-correlation-id" "x-b3-traceid" "x-amzn-trace-id"])

;; --------------------------------------------------------------------------
;; Helpers
;; --------------------------------------------------------------------------

(defn- expand-home
  "node does not expand a leading ~; resolve it from $HOME."
  [path]
  (if (and path (str/starts-with? path "~"))
    (str (.-HOME (.-env js/process)) (subs path 1))
    path))

(defn page
  "The current Page, or nil before (start!)."
  []
  (:page @state))

(defn started? []
  (some? (page)))

(defn- pg!
  "The current Page or a clear error - used by synchronous locator builders."
  []
  (or (page)
      (throw (js/Error. "No page yet - call (start!) or (goto url) first."))))

;; --------------------------------------------------------------------------
;; Locators: target specs unify the locator helpers the agent needs.
;;   string          -> CSS selector
;;   Locator         -> passed through
;;   [:role r opts?] [:text s] [:label s] [:placeholder s] [:testid s] [:css s]
;; Specs resolve lazily at action time, so there is no "built before start" trap.
;; --------------------------------------------------------------------------

(defn resolve-target
  [spec]
  (let [p (pg!)]
    (cond
      (string? spec) (.locator p spec)
      (vector? spec)
      (let [[kind a b] spec]
        (case kind
          :role        (.getByRole p (name a) (clj->js (or b {})))
          :text        (.getByText p a)
          :label       (.getByLabel p a)
          :placeholder (.getByPlaceholder p a)
          :testid      (.getByTestId p a)
          :css         (.locator p a)
          (throw (js/Error. (str "Unknown target kind: " kind)))))
      :else spec)))

(defn loc "Resolve a target spec to a Playwright Locator." [spec] (resolve-target spec))
(defn css [selector] (.locator (pg!) selector))
(defn by-role
  ([role] (by-role role {}))
  ([role opts] (.getByRole (pg!) (name role) (clj->js opts))))
(defn by-text [t] (.getByText (pg!) t))
(defn by-label [t] (.getByLabel (pg!) t))
(defn by-placeholder [t] (.getByPlaceholder (pg!) t))
(defn by-testid [t] (.getByTestId (pg!) t))

;; --------------------------------------------------------------------------
;; Capture (opt-in; idempotent) - listeners feed the atoms
;; --------------------------------------------------------------------------

(declare start!)                                  ; defined below in Lifecycle

(defn- corr-headers [js-headers]
  (select-keys (js->clj js-headers) correlation-header-keys))

(defn- attach-net! [pg re]
  (let [match? (fn [u] (or (nil? re) (boolean (re-find re u))))]
    (.on pg "request"
         (fn [req]
           (let [u (.url req)]
             (when (match? u)
               (swap! net conj {:phase :req :method (.method req) :url u
                                :ts (js/Date.now) :headers (corr-headers (.headers req))})))))
    (.on pg "response"
         (fn [resp]
           (let [u (.url resp)]
             (when (match? u)
               (swap! net conj {:phase :resp :status (.status resp) :url u
                                :ts (js/Date.now)})))))))

(defn capture-net!
  "Start recording requests/responses into `net`. :url-filter is a regex string
   that limits what is recorded (recommended, e.g. \"/api/v1\"). Idempotent."
  ([] (capture-net! {}))
  ([{:keys [url-filter]}]
   (p/let [pg (start!)]
     (when-not (contains? @capturing :net)
       (swap! capturing conj :net)
       (attach-net! pg (when url-filter (re-pattern url-filter))))
     :capturing-net)))

(defn capture-console!
  "Start recording console messages into `console`. Idempotent."
  []
  (p/let [pg (start!)]
    (when-not (contains? @capturing :console)
      (swap! capturing conj :console)
      (.on pg "console"
           (fn [msg]
             (swap! console conj {:type (.type msg) :text (.text msg) :ts (js/Date.now)}))))
    :capturing-console))

(defn net-summary
  "Compact roll-up of captured network: request/response totals + counts by
   status code. Cheap to poll between turns."
  []
  (let [items @net
        resps (filter #(= :resp (:phase %)) items)]
    {:requests  (count (filter #(= :req (:phase %)) items))
     :responses (count resps)
     :by-status (frequencies (map :status resps))}))

(defn net-where
  "Captured entries whose url matches the regex string, projected to the compact
   fields (phase/method/status/url). For targeted inspection without the dump."
  [url-re]
  (let [re (re-pattern url-re)]
    (->> @net
         (filter #(re-find re (:url %)))
         (mapv #(select-keys % [:phase :method :status :url :headers])))))

(defn net-clear! [] (reset! net []) :cleared)

(defn console-tail
  "Last n captured console messages (default 20)."
  ([] (console-tail 20))
  ([n] (vec (take-last n @console))))

(defn console-clear! [] (reset! console []) :cleared)

;; --------------------------------------------------------------------------
;; Lifecycle: modes fresh / persistent / attach. start! is idempotent and is
;; the lazy ensure-page! used by every action below.
;; --------------------------------------------------------------------------

(defn configure!
  "Merge opts into the session config (mode/headless?/user-data-dir/cdp-endpoint
   /viewport). Takes effect on the next (start!)."
  [opts]
  (swap! state update :config merge opts)
  (:config @state))

(defn- first-or-new-page
  "Reuse an existing page from a context (persistent/attach reconnects to a live
   one), else open a fresh page."
  [context]
  (let [pages (.pages context)]
    (if (pos? (alength pages)) (aget pages 0) (.newPage context))))

(defn start!
  "Launch the browser for the configured mode and remember browser/context/page.
   Idempotent - returns the existing page if already started. Doubles as the
   ensure-page! every action calls, so a bare (goto url) auto-starts."
  []
  (if (started?)
    (p/resolved (page))
    (let [{:keys [mode headless? user-data-dir cdp-endpoint viewport]} (:config @state)
          remember (fn [browser context pg]
                     (swap! state assoc :browser browser :context context :page pg)
                     pg)]
      (case mode
        :fresh
        (p/let [browser (.launch pw/chromium (clj->js {:headless headless?}))
                context (.newContext browser (clj->js {:viewport viewport}))
                pg      (.newPage context)]
          (remember browser context pg))

        :persistent
        (p/let [context (.launchPersistentContext
                         pw/chromium (expand-home user-data-dir)
                         (clj->js {:headless headless? :viewport viewport}))
                pg      (first-or-new-page context)]
          (remember nil context pg))

        :attach
        (do
          (when-not cdp-endpoint
            (throw (js/Error. ":attach mode needs :cdp-endpoint (e.g. http://127.0.0.1:9222)")))
          (p/let [browser  (.connectOverCDP pw/chromium cdp-endpoint)
                  contexts (.contexts browser)
                  context  (if (pos? (alength contexts)) (aget contexts 0) (.newContext browser))
                  pg       (first-or-new-page context)]
            (remember browser context pg)))

        (throw (js/Error. (str "Unknown mode: " mode)))))))

(defn stop!
  "Close context + browser, clear capture flags, forget handles. Idempotent.
   Captured `net`/`console` data is kept so you can inspect after teardown;
   call net-clear!/console-clear! to drop it."
  []
  (let [{:keys [browser context]} @state]
    (p/let [_ (when context (p/catch (.close context) (fn [_] nil)))
            _ (when browser (p/catch (.close browser) (fn [_] nil)))]
      (reset! capturing #{})
      (swap! state assoc :browser nil :context nil :page nil)
      :stopped)))

;; --------------------------------------------------------------------------
;; Navigation + interaction (auto-start via start!)
;; --------------------------------------------------------------------------

(defn goto
  "Navigate to url; returns the resulting URL string."
  ([url] (goto url {}))
  ([url opts]
   (p/let [pg (start!)
           _  (.goto pg url (clj->js (merge {:waitUntil "domcontentloaded"} opts)))]
     (.url pg))))

(defn current-url [] (p/let [pg (start!)] (.url pg)))

(defn click
  ([target] (click target {}))
  ([target opts]
   (p/let [_ (start!)
           _ (.click (resolve-target target) (clj->js opts))]
     :clicked)))

(defn fill
  "Clear and set an input's value."
  [target value]
  (p/let [_ (start!)
          _ (.fill (resolve-target target) value)]
    :filled))

(defn type-text
  "Type into a field key-by-key (triggers key handlers)."
  [target text]
  (p/let [_ (start!)
          _ (.pressSequentially (resolve-target target) text)]
    :typed))

(defn press
  "Press a key on a target (e.g. \"Enter\", \"Control+A\")."
  [target key]
  (p/let [_ (start!)
          _ (.press (resolve-target target) key)]
    :pressed))

;; --------------------------------------------------------------------------
;; Targeted extractors (token economy: vectors/strings, never raw handles)
;; --------------------------------------------------------------------------

(defn texts
  "Trimmed text contents of all elements matching target, as a vector."
  [target]
  (p/let [_   (start!)
          arr (.allTextContents (resolve-target target))]
    (mapv str/trim (js->clj arr))))

(defn text
  "Trimmed text content of the first matching element."
  [target]
  (p/let [_ (start!)
          t (.textContent (.first (resolve-target target)))]
    (some-> t str/trim)))

(defn count-of
  "How many elements match target."
  [target]
  (p/let [_ (start!)] (.count (resolve-target target))))

(defn attrs
  "An attribute's value across all matching elements, as a vector."
  [target attr]
  (p/let [_   (start!)
          l   (resolve-target target)
          n   (.count l)
          out (p/all (for [i (range n)] (.getAttribute (.nth l i) attr)))]
    (vec out)))

(defn visible?
  "Is the first matching element visible?"
  [target]
  (p/let [_ (start!)] (.isVisible (.first (resolve-target target)))))

(defn aria
  "The agent's compact 'eyes': a scoped ARIA snapshot. Defaults to body for the
   whole accessible structure; pass a target spec to scope to a dialog/list/
   region and shrink the output further."
  ([] (aria "body"))
  ([target]
   (p/let [_ (start!)] (.ariaSnapshot (.first (resolve-target target))))))

(defn eval-js
  "Run JS in the page and return the (serializable) result.
   GOTCHA: a string is evaluated as an EXPRESSION, not a function. A bare
   \"() => document.title\" returns the function (serializes to nil). Use a
   plain expression \"document.title\" or an IIFE \"(() => {...})()\"."
  ([js] (eval-js js nil))
  ([js arg] (p/let [pg (start!)] (.evaluate pg js arg))))

;; --------------------------------------------------------------------------
;; Wait / assert / download
;; --------------------------------------------------------------------------

(defn wait-url
  "Wait until the URL matches pattern (glob string or a JS RegExp #\"...\").
   Returns the URL."
  ([pattern] (wait-url pattern {}))
  ([pattern opts]
   (p/let [pg (start!)
           _  (.waitForURL pg pattern (clj->js opts))]
     (.url pg))))

(defn wait-for
  "Wait for a target's state (\"visible\" default; also \"hidden\"/\"attached\"
   /\"detached\")."
  ([target] (wait-for target {}))
  ([target opts]
   (p/let [_ (start!)
           _ (.waitFor (.first (resolve-target target))
                       (clj->js (merge {:state "visible"} opts)))]
     :ready)))

(defn download!
  "Run trigger-fn (0-arg, initiates the download e.g. (fn [] (click ...))) and
   save the download under dir. Returns {:path :filename}."
  ([trigger-fn] (download! trigger-fn {}))
  ([trigger-fn {:keys [dir] :or {dir "~/.cache/qlik-verify/output"}}]
   (p/let [pg      (start!)
           ;; set up the waiter BEFORE triggering, await both (Promise.all)
           [dl _]  (p/all [(.waitForEvent pg "download") (trigger-fn)])
           fname   (.suggestedFilename dl)
           path    (str (expand-home dir) "/" fname)
           _       (.saveAs dl path)]
     {:path path :filename fname})))

(defn assert!
  "Throw with msg unless truthy?; returns :ok. Pair with an extractor, e.g.
   (p/let [t (text \"h1\")] (assert! (= t \"Home\") (str \"got \" t)))."
  [truthy? msg]
  (if truthy? :ok (throw (js/Error. (str "assert failed: " msg)))))

(defn assert-visible
  "Assert the target is visible; throws otherwise. Returns :ok."
  [target]
  (p/let [v (visible? target)]
    (assert! v (str "expected visible: " (pr-str target)))))

;; --------------------------------------------------------------------------
;; Fire-and-poll: the right primitive for long ops in a turn-based loop.
;; (run-job! :id #(...promise...)) returns :id immediately; poll (job :id).
;; --------------------------------------------------------------------------

(defn run-job!
  "Start (f) (a 0-arg fn returning a promise) in the background under id and
   stash its outcome in `jobs`. Returns id immediately - do NOT await it; poll
   (job id) on later turns. Sidesteps tool timeouts for slow flows."
  [id f]
  (swap! jobs assoc id {:status :running :started (js/Date.now)})
  (-> (p/let [v (f)] v)
      (p/then  (fn [v] (swap! jobs assoc id {:status :done  :value v})))
      (p/catch (fn [e] (swap! jobs assoc id {:status :error :error (or (.-message e) (str e))}))))
  id)

(defn job
  "Current state of a fire-and-poll job: {:status :running|:done|:error ...}."
  [id]
  (get @jobs id))

;; --------------------------------------------------------------------------
;; Introspection
;; --------------------------------------------------------------------------

(defn status
  "Compact session status for the agent."
  []
  (let [pg (page)]
    {:mode      (get-in @state [:config :mode])
     :started?  (started?)
     :url       (when pg (.url pg))
     :capturing @capturing
     :net       (count @net)
     :console   (count @console)
     :jobs      (into {} (map (fn [[k v]] [k (:status v)])) @jobs)}))

(def api
  "One-line index of the stdlib, for in-session discovery without the skill."
  ["(configure! {:mode :fresh|:persistent|:attach :headless? false ...})"
   "(start!) (stop!) (status) (page)"
   "(goto url) (current-url) (wait-url glob) (wait-for target)"
   "(click target) (fill target v) (type-text target s) (press target key)"
   "  target := \"css\" | [:role r {:name ..}] | [:text s] | [:label s] | [:placeholder s] | [:testid s] | Locator"
   "(aria) (aria target)  ; scoped ARIA 'eyes'"
   "(texts target) (text target) (count-of target) (attrs target attr) (visible? target)"
   "(eval-js \"document.title\")  ; EXPRESSION string, not a bare arrow"
   "(capture-net! {:url-filter \"/api/v1\"}) (net-summary) (net-where re) (net-clear!)"
   "(capture-console!) (console-tail n) (console-clear!)"
   "(download! trigger-fn {:dir ..})  (assert! x msg) (assert-visible target)"
   "(run-job! :id #(...promise...)) (job :id)  ; fire-and-poll long ops"])
