#!/usr/bin/env bb
;; MCP server for browser interaction via JXA (JavaScript for Automation).
;; Full E2E browser automation: navigation, clicking, typing, DOM queries,
;; network monitoring, console capture, screenshots, and more.
;; macOS only - requires osascript, plutil, jq, screencapture.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(def server-info
  {:name "jxa-browser" :version "2.0.0"})

;;; --- Helpers ---

(def browser-name (atom nil))

(defn default-browser
  "Resolves the user's default browser from LaunchServices prefs.
  Cached in atom since the default won't change mid-session."
  []
  (or @browser-name
      (let [{:keys [exit out]} (shell/sh
                                "sh" "-c"
                                (str "osascript -e \"tell application \\\"Finder\\\" to get the name of application file id"
                                     "\\\"$(plutil -convert json -o - ~/Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist"
                                     "| jq -r '.LSHandlers[] | select(.LSHandlerURLScheme==\"https\") | .LSHandlerRoleAll')\\\"\""))]
        (when (zero? exit)
          (let [name (str/trim out)]
            (reset! browser-name name)
            name)))))

(defn run-jxa
  "Executes a JXA (JavaScript for Automation) script via osascript.
  JXA is macOS's JS-based alternative to AppleScript for app control."
  [script]
  (shell/sh "osascript" "-l" "JavaScript" "-e" script))

(defn html->text
  "Converts HTML to plain text via macOS textutil.
  Needed because raw DOM HTML is unusable for text search and display."
  [html]
  (let [{:keys [exit out err]} (shell/sh "textutil" "-stdin" "-format" "html" "-convert" "txt" "-stdout"
                                         :in html)]
    (if (zero? exit)
      (str/trim out)
      (str "textutil error: " (str/trim err)))))

(defn wrap-result
  "Reshapes {:ok ...}/{:error ...} into MCP's required content envelope.
  MCP protocol mandates a {:content [{:type :text}]} response shape."
  [{:keys [ok error]}]
  (if error
    {:content [{:type "text" :text (str "Error: " error)}] :isError true}
    {:content [{:type "text" :text ok}]}))

(defn exec-js-in-tab
  "Execute JavaScript in the active tab via JXA. Returns {:ok string} or {:error string}.
   Writes JS to a temp file to avoid all encoding/escaping issues across the JXA bridge."
  [js-code]
  (let [browser (default-browser)
        tmp (java.io.File/createTempFile "mcp-js-" ".js")
        tmp-path (.getAbsolutePath tmp)]
    (try
      (spit tmp js-code)
      (let [script (str "var app = Application.currentApplication();\n"
                        "app.includeStandardAdditions = true;\n"
                        "var browser = Application('" browser "');\n"
                        "var wins = browser.windows();\n"
                        "if (wins.length === 0) throw new Error('No browser windows open');\n"
                        "var activeTab = wins[0].activeTab;\n"
                        "var code = app.read(Path('" tmp-path "'));\n"
                        "activeTab.execute({javascript: code});")
            {:keys [exit out err]} (run-jxa script)]
        (if (zero? exit)
          {:ok (str/trim out)}
          {:error (str/trim (str out err))}))
      (finally
        (.delete tmp)))))

;;; --- Tool Definitions ---

(def tools
  [{:name "browser-list-tabs"
    :description "List all open browser tabs. Returns windowIndex, tabIndex, url, title, active flag per tab."
    :inputSchema {:type "object" :properties {}}}

   {:name "browser-read-active-tab"
    :description "Read the active browser tab's content. Without query: returns url, title, preview. With query: returns matching lines with context."
    :inputSchema
    {:type "object"
     :properties {:query {:type "string"
                          :description "Optional text/regex to search for in page content. Returns matching lines with surrounding context."}
                  :format {:type "string"
                           :description "Output format: 'text' (default) for plain text, 'html' for raw HTML."
                           :enum ["text" "html"]}}}}

   {:name "browser-get-selection"
    :description "Get selected text in the active browser tab, or empty string if none."
    :inputSchema {:type "object" :properties {}}}

   {:name "browser-navigate"
    :description "Navigate the active browser tab to a URL or perform back/forward/reload."
    :inputSchema
    {:type "object"
     :properties {:url {:type "string"
                        :description "URL to navigate to. Required for 'goto' action."}
                  :action {:type "string"
                           :description "Navigation action: 'goto' (default when url given), 'back', 'forward', 'reload'."
                           :enum ["goto" "back" "forward" "reload"]}}}}

   {:name "browser-click"
    :description "Click an element by CSS selector, visible text, or viewport coordinates (x, y for OS-level click)."
    :inputSchema
    {:type "object"
     :properties {:selector {:type "string"
                             :description "CSS selector to find the element."}
                  :text {:type "string"
                         :description "Visible text content to match. Used when selector is not provided."}
                  :index {:type "integer"
                          :description "Zero-based index when multiple elements match (default: 0)."}
                  :x {:type "number"
                      :description "Viewport-relative X coordinate for OS-level click. Use with y. Coordinates from browser-query bounding rects work directly."}
                  :y {:type "number"
                      :description "Viewport-relative Y coordinate for OS-level click. Use with x. Coordinates from browser-query bounding rects work directly."}}}}

   {:name "browser-type"
    :description "Type text into a focused or selected input/textarea/contenteditable element."
    :inputSchema
    {:type "object"
     :properties {:text {:type "string"
                         :description "Text to type into the element."}
                  :selector {:type "string"
                             :description "CSS selector for target element. If omitted, types into the currently focused element."}
                  :clear {:type "boolean"
                          :description "Clear existing content before typing (default: false)."}
                  :submit {:type "boolean"
                           :description "Submit the closest form after typing (default: false)."}}
     :required ["text"]}}

   {:name "browser-query"
    :description "Query DOM elements by CSS selector. Returns tag, text, attributes, bounding rect, visibility."
    :inputSchema
    {:type "object"
     :properties {:selector {:type "string"
                             :description "CSS selector to query elements."}
                  :limit {:type "integer"
                          :description "Max number of elements to return (default: 20)."}}
     :required ["selector"]}}

   {:name "browser-execute-js"
    :description "Execute JavaScript in the active tab. Last expression value is returned. Supports same-origin iframes."
    :inputSchema
    {:type "object"
     :properties {:code {:type "string"
                         :description "JavaScript code to execute. The return value of the last expression is captured via eval()."}
                  :iframe_selector {:type "string"
                                    :description "CSS selector for an iframe to execute inside (same-origin only)."}}
     :required ["code"]}}

   {:name "browser-tab"
    :description "Switch, open, or close browser tabs."
    :inputSchema
    {:type "object"
     :properties {:action {:type "string"
                           :description "Tab action: 'switch', 'open', 'close'."
                           :enum ["switch" "open" "close"]}
                  :tabIndex {:type "integer"
                             :description "1-based tab index (for switch/close)."}
                  :windowIndex {:type "integer"
                                :description "1-based window index (default: 1)."}
                  :url {:type "string"
                        :description "URL to open (for 'open' action)."}
                  :url_pattern {:type "string"
                                :description "Regex to match tab URL (for 'switch', alternative to tabIndex)."}}
     :required ["action"]}}

   {:name "browser-screenshot"
    :description "Screenshot the browser window as base64 PNG."
    :inputSchema {:type "object" :properties {}}}

   {:name "browser-network"
    :description "Monitor network requests. start: begin capture, get: retrieve logs, stop: end capture."
    :inputSchema
    {:type "object"
     :properties {:action {:type "string"
                           :description "Action: 'start' to begin capture, 'get' to retrieve logs, 'stop' to end capture."
                           :enum ["start" "get" "stop"]}
                  :filter {:type "string"
                           :description "Regex pattern to filter request URLs (for 'get' action)."}}
     :required ["action"]}}

   {:name "browser-console-logs"
    :description "Capture browser console output. start/get/stop lifecycle."
    :inputSchema
    {:type "object"
     :properties {:action {:type "string"
                           :description "Action: 'start' to begin capture, 'get' to retrieve logs, 'stop' to end."
                           :enum ["start" "get" "stop"]}
                  :level {:type "string"
                          :description "Filter by log level (for 'get')."
                          :enum ["log" "warn" "error" "info" "debug"]}}
     :required ["action"]}}

   {:name "browser-wait"
    :description "Wait for an element, text, or URL pattern. Polls until condition met or timeout."
    :inputSchema
    {:type "object"
     :properties {:selector {:type "string"
                             :description "CSS selector to wait for."}
                  :text {:type "string"
                         :description "Text to wait for on the page."}
                  :url_pattern {:type "string"
                                :description "Regex for URL to match."}
                  :state {:type "string"
                          :description "Element state: 'visible' (default), 'hidden', 'present' (in DOM regardless of visibility)."
                          :enum ["visible" "hidden" "present"]}
                  :timeout {:type "integer"
                            :description "Timeout in milliseconds (default: 10000)."}}}}

   {:name "browser-scroll"
    :description "Scroll the page or a container to an element, top/bottom, or by pixel amount."
    :inputSchema
    {:type "object"
     :properties {:selector {:type "string"
                             :description "CSS selector - scroll this element into view."}
                  :direction {:type "string"
                              :description "Scroll direction: 'up' or 'down'."
                              :enum ["up" "down"]}
                  :pixels {:type "integer"
                           :description "Pixels to scroll (default: 500). Used with direction."}
                  :to {:type "string"
                       :description "Scroll to absolute position: 'top', 'bottom', or 'element' (with selector)."
                       :enum ["top" "bottom" "element"]}
                  :container {:type "string"
                              :description "CSS selector for a scrollable container (e.g. '[role=\"dialog\"]'). Scrolls within this element instead of the page."}}}}])

;;; --- Existing Tool Implementations ---

(defn list-tabs
  "Enumerates all tabs across all browser windows via JXA."
  []
  (let [browser (default-browser)
        script (format "
var browser = Application('%s');
var tabsInfo = [];
var wins = browser.windows();
if (wins.length === 0) { JSON.stringify([]); } else {
  var activeTabId = wins[0].activeTab.id();
  wins.forEach(function(win, wi) {
    win.tabs().forEach(function(tab, ti) {
      tabsInfo.push({
        windowIndex: wi + 1,
        tabIndex: ti + 1,
        url: tab.url(),
        title: tab.name(),
        active: activeTabId === tab.id()
      });
    });
  });
  JSON.stringify(tabsInfo);
}
" browser)
        {:keys [exit out err]} (run-jxa script)]
    (if (zero? exit)
      {:content [{:type "text" :text (str/trim out)}]}
      {:content [{:type "text" :text (str "JXA error: " (str/trim (str out err)))}]
       :isError true})))

(defn grep-lines
  "Returns lines matching `query` (case-insensitive regex) with `ctx`
  surrounding context lines."
  [text query & {:keys [ctx] :or {ctx 3}}]
  (let [lines   (str/split-lines text)
        total   (count lines)
        pattern (re-pattern (str "(?i)" query))
        hits    (keep-indexed
                 (fn [i line] (when (re-find pattern line) i))
                 lines)
        ranges  (reduce
                 (fn [acc i]
                   (let [lo (max 0 (- i ctx))
                         hi (min (dec total) (+ i ctx))]
                     (if-let [[plo phi] (peek acc)]
                       (if (<= lo (inc phi))
                         (conj (pop acc) [plo (max phi hi)])
                         (conj acc [lo hi]))
                       (conj acc [lo hi]))))
                 [] hits)]
    (->> ranges
         (map (fn [[lo hi]]
                (->> (range lo (inc hi))
                     (map (fn [i]
                            (let [prefix (if (re-find pattern (nth lines i)) "» " "  ")]
                              (format "%s%4d: %s" prefix (inc i) (nth lines i)))))
                     (str/join "\n"))))
         (str/join "\n  ---\n"))))

(defn fetch-active-tab-content
  "Fetches active tab's content via JXA. Returns {:url :title :html} or {:error ...}"
  []
  (let [browser (default-browser)
        script  (format "
var browser = Application('%s');
var wins = browser.windows();
if (wins.length === 0) throw new Error('No browser windows open');
var activeTab = wins[0].activeTab;
var html = activeTab.execute({javascript: 'document.documentElement.outerHTML'});
JSON.stringify({url: activeTab.url(), title: activeTab.name(), html: html});
" browser)
        {:keys [exit out err]} (run-jxa script)]
    (if (zero? exit)
      (let [parsed (json/parse-string (str/trim out) true)]
        {:url   (:url parsed)
         :title (:title parsed)
         :html  (:html parsed)})
      {:error (str "JXA error: " (str/trim (str out err)))})))

(defn get-selection
  "Returns the current text selection in the active browser tab."
  []
  (let [browser (default-browser)
        script  (format "
var browser = Application('%s');
var wins = browser.windows();
if (wins.length === 0) throw new Error('No browser windows open');
var activeTab = wins[0].activeTab;
activeTab.execute({javascript: 'window.getSelection().toString()'});
" browser)
        {:keys [exit out err]} (run-jxa script)]
    (if (zero? exit)
      {:content [{:type "text" :text (str/trim out)}]}
      {:content [{:type "text" :text (str "JXA error: " (str/trim (str out err)))}]
       :isError true})))

(defn read-active-tab
  "Reads active tab content. Dispatches on format (html vs text) and query presence -
  returning either raw HTML, a preview with metadata, or grep-matched lines."
  [query fmt]
  (let [{:keys [url title html error]} (fetch-active-tab-content)]
    (if error
      {:content [{:type "text" :text error}] :isError true}
      (if (= fmt "html")
        {:content [{:type "text"
                    :text (json/generate-string
                           {:url url :title title
                            :html (subs html 0 (min 50000 (count html)))})}]}
        (let [text (html->text html)]
          (if (str/blank? query)
            (let [lines    (str/split-lines text)
                  line-cnt (count lines)
                  preview  (subs text 0 (min 500 (count text)))]
              {:content [{:type "text"
                          :text (json/generate-string
                                 {:url url :title title
                                  :lineCount line-cnt
                                  :preview (str preview "...")})}]})
            (let [matches (grep-lines text query)]
              {:content [{:type "text"
                          :text (json/generate-string
                                 {:url url :title title
                                  :matches (if (str/blank? matches)
                                             "No matches found."
                                             matches)})}]})))))))

;;; --- New Tool Implementations ---

(defn wait-for-load
  "Polls document.readyState until 'complete' or timeout. Returns page info."
  [max-attempts]
  (loop [attempt 0]
    (if (< max-attempts attempt)
      (let [info (exec-js-in-tab "JSON.stringify({url: location.href, title: document.title, readyState: document.readyState, timeout: true})")]
        (wrap-result (if (:error info)
                       {:ok (json/generate-string {:timeout true :readyState "unknown"})}
                       info)))
      (let [result (exec-js-in-tab "document.readyState")]
        (if (= (:ok result) "complete")
          (wrap-result (exec-js-in-tab "JSON.stringify({url: location.href, title: document.title, readyState: 'complete'})"))
          (do (Thread/sleep 500)
              (recur (inc attempt))))))))

(defn browser-navigate
  "Navigates the active tab via goto/back/forward/reload, then waits for page load."
  [{:strs [url action]}]
  (let [action (or action (when url "goto") "reload")]
    (case action
      "goto"
      (if (str/blank? url)
        {:content [{:type "text" :text "Error: url is required for goto action."}] :isError true}
        (let [browser (default-browser)
              script (format "
var browser = Application('%s');
var wins = browser.windows();
if (wins.length === 0) throw new Error('No browser windows open');
wins[0].activeTab.url = %s;
'ok';
" browser (json/generate-string url))
              {:keys [exit out err]} (run-jxa script)]
          (if (zero? exit)
            (do (Thread/sleep 1000) (wait-for-load 18))
            {:content [{:type "text" :text (str "JXA error: " (str/trim (str out err)))}]
             :isError true})))

      "back"
      (do (exec-js-in-tab "history.back()")
          (Thread/sleep 1000)
          (wait-for-load 18))

      "forward"
      (do (exec-js-in-tab "history.forward()")
          (Thread/sleep 1000)
          (wait-for-load 18))

      "reload"
      (do (exec-js-in-tab "location.reload()")
          (Thread/sleep 1000)
          (wait-for-load 18)))))

(defn click-at-coordinates
  "Performs an OS-level click at screen coordinates via macOS CGEvent through JXA's ObjC bridge.
  Uses osascript (not swift) for faster execution and consistency with the rest of the server."
  [screen-x screen-y]
  (let [script (str "ObjC.import('CoreGraphics');\n"
                    "ObjC.import('Cocoa');\n"
                    "if (!$.AXIsProcessTrusted()) {\n"
                    "  var opts = $.NSDictionary.dictionaryWithObject_forKey_(true, $.kAXTrustedCheckOptionPrompt);\n"
                    "  $.AXIsProcessTrustedWithOptions(opts);\n"
                    "  throw new Error('Accessibility permission required. Grant access in System Settings > Privacy & Security > Accessibility for the host app, then retry.');\n"
                    "}\n"
                    "var p = $.CGPointMake(" (double screen-x) ", " (double screen-y) ");\n"
                    "var down = $.CGEventCreateMouseEvent(null, $.kCGEventLeftMouseDown, p, $.kCGMouseButtonLeft);\n"
                    "var up = $.CGEventCreateMouseEvent(null, $.kCGEventLeftMouseUp, p, $.kCGMouseButtonLeft);\n"
                    "$.CGEventPost($.kCGHIDEventTap, down);\n"
                    "delay(0.05);\n"
                    "$.CGEventPost($.kCGHIDEventTap, up);\n"
                    "'ok';")
        {:keys [exit out err]} (run-jxa script)]
    (if (zero? exit)
      {:ok (json/generate-string {:success true :clickedAt {:screenX (double screen-x) :screenY (double screen-y)}})}
      {:error (str/trim (str out err))})))

(defn browser-click
  "Clicks an element by CSS selector, visible text, or viewport coordinates.
  Coordinate mode performs an OS-level CGEvent click, bypassing framework event delegation."
  [{:strs [selector text index x y]}]
  (if (and x y)
    ;; OS-level click at viewport coordinates via CGEvent
    (let [info-result (exec-js-in-tab
                       "JSON.stringify({screenX: window.screenX, screenY: window.screenY, outerHeight: window.outerHeight, innerHeight: window.innerHeight})")]
      (if (:error info-result)
        (wrap-result info-result)
        (let [info (json/parse-string (:ok info-result) true)
              toolbar-h (- (:outerHeight info) (:innerHeight info))
              screen-x (+ (:screenX info) x)
              screen-y (+ (:screenY info) toolbar-h y)]
          (run-jxa (format "Application('%s').activate()" (default-browser)))
          (Thread/sleep 200)
          (wrap-result (click-at-coordinates screen-x screen-y)))))
    ;; DOM-level click by selector or text
    (let [idx (or index 0)
          js-code (format "
(function() {
  var matches = [];
  if (%s) {
    matches = Array.from(document.querySelectorAll(%s));
  } else if (%s) {
    var query = %s.toLowerCase();
    var clickables = document.querySelectorAll('a, button, input[type=\"submit\"], input[type=\"button\"], [role=\"button\"], summary, label, [onclick]');
    for (var i = 0; i < clickables.length; i++) {
      var ct = (clickables[i].textContent || '').trim().toLowerCase();
      var al = (clickables[i].getAttribute('aria-label') || '').toLowerCase();
      if (ct.indexOf(query) !== -1 || al.indexOf(query) !== -1) {
        matches.push(clickables[i]);
      }
    }
    if (matches.length === 0) {
      var all = document.querySelectorAll('*');
      for (var j = 0; j < all.length; j++) {
        var el = all[j];
        var ownText = Array.from(el.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('').trim().toLowerCase();
        if (ownText.indexOf(query) !== -1) matches.push(el);
        else if (el.children.length === 0 && (el.textContent || '').trim().toLowerCase().indexOf(query) !== -1) matches.push(el);
      }
    }
  } else {
    return JSON.stringify({success: false, error: 'Provide selector or text parameter'});
  }
  if (matches.length === 0) return JSON.stringify({success: false, error: 'No matching elements found'});
  var idx = %d;
  if (idx >= matches.length) return JSON.stringify({success: false, error: 'Index ' + idx + ' out of range, found ' + matches.length + ' matches'});
  var el = matches[idx];
  el.scrollIntoView({block: 'center', behavior: 'instant'});
  var rect = el.getBoundingClientRect();
  var cx = rect.left + rect.width / 2;
  var cy = rect.top + rect.height / 2;
  var pOpts = {bubbles: true, cancelable: true, clientX: cx, clientY: cy, pointerId: 1, pointerType: 'mouse', isPrimary: true};
  var mOpts = {bubbles: true, cancelable: true, clientX: cx, clientY: cy};
  el.dispatchEvent(new PointerEvent('pointerover', pOpts));
  el.dispatchEvent(new PointerEvent('pointerenter', Object.assign({}, pOpts, {bubbles: false})));
  el.dispatchEvent(new MouseEvent('mouseover', mOpts));
  el.dispatchEvent(new PointerEvent('pointerdown', pOpts));
  el.dispatchEvent(new MouseEvent('mousedown', mOpts));
  el.dispatchEvent(new PointerEvent('pointerup', pOpts));
  el.dispatchEvent(new MouseEvent('mouseup', mOpts));
  el.click();
  return JSON.stringify({
    success: true,
    tag: el.tagName.toLowerCase(),
    text: (el.textContent || '').substring(0, 100).trim(),
    id: el.id || undefined,
    href: el.href || undefined,
    matchCount: matches.length
  });
})()
"
                        (boolean selector)
                        (json/generate-string (or selector ""))
                        (boolean text)
                        (json/generate-string (or text ""))
                          idx)]
      (wrap-result (exec-js-in-tab js-code)))))

(defn browser-type
  "Types text into an element, dispatching input/change events explicitly.
  Synthetic value assignment alone won't trigger React/Vue/etc. state updates."
  [{:strs [text selector clear submit]}]
  (let [js-code (format "
(function() {
  var el = %s ? document.querySelector(%s) : document.activeElement;
  if (!el) return JSON.stringify({success: false, error: 'No element found'});
  if (!('value' in el) && !el.isContentEditable) {
    return JSON.stringify({success: false, error: 'Element is not editable: ' + el.tagName});
  }
  el.focus();
  if (%s) {
    if (el.isContentEditable) el.innerHTML = '';
    else el.value = '';
    el.dispatchEvent(new Event('input', {bubbles: true}));
  }
  var txt = %s;
  if (el.isContentEditable) {
    el.innerHTML += txt;
  } else {
    el.value += txt;
  }
  el.dispatchEvent(new Event('input', {bubbles: true}));
  el.dispatchEvent(new Event('change', {bubbles: true}));
  var submitted = false;
  if (%s) {
    var form = el.closest('form');
    if (form) {
      form.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
      form.submit();
      submitted = true;
    }
  }
  return JSON.stringify({success: true, tag: el.tagName.toLowerCase(), submitted: submitted});
})()
"
                        (boolean selector)
                        (json/generate-string (or selector ""))
                        (boolean clear)
                        (json/generate-string (or text ""))
                        (boolean submit))]
    (wrap-result (exec-js-in-tab js-code))))

(defn browser-query
  "Returns structured DOM info (tag, attrs, rect, visibility) for matched elements.
  Richer than raw HTML - callers need actionable data to decide what to interact with."
  [{:strs [selector limit]}]
  (let [lim (or limit 20)
        js-code (format "
(function() {
  var els = document.querySelectorAll(%s);
  var results = Array.from(els).slice(0, %d).map(function(el, i) {
    var rect = el.getBoundingClientRect();
    var style = window.getComputedStyle(el);
    var attrs = {};
    for (var j = 0; j < el.attributes.length; j++) {
      var a = el.attributes[j];
      attrs[a.name] = a.value;
    }
    return {
      index: i,
      tag: el.tagName.toLowerCase(),
      text: (el.textContent || '').substring(0, 200).trim(),
      id: el.id || undefined,
      className: el.className && typeof el.className === 'string' ? el.className : undefined,
      href: el.href || undefined,
      src: el.src || undefined,
      value: el.value !== undefined && el.value !== '' ? el.value : undefined,
      type: el.type || undefined,
      name: el.name || undefined,
      placeholder: el.placeholder || undefined,
      ariaLabel: el.getAttribute('aria-label') || undefined,
      role: el.getAttribute('role') || undefined,
      visible: rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none',
      inViewport: rect.top < window.innerHeight && rect.bottom > 0 && rect.left < window.innerWidth && rect.right > 0,
      rect: {x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height)}
    };
  });
  return JSON.stringify({total: els.length, returned: results.length, elements: results});
})()
" (json/generate-string selector) lim)]
    (wrap-result (exec-js-in-tab js-code))))

(defn browser-execute-js
  "Evaluates arbitrary JS in the active tab. Supports targeting same-origin iframes
  since many apps embed content in frames that the top-level context can't reach."
  [{:strs [code iframe_selector]}]
  (let [code-b64 (.encodeToString (java.util.Base64/getEncoder) (.getBytes code "UTF-8"))
        js-code (if iframe_selector
                  (str "(function() { try {"
                       " var iframe = document.querySelector(" (json/generate-string iframe_selector) ");"
                       " if (!iframe) return JSON.stringify({error: 'iframe not found'});"
                       " if (!iframe.contentDocument) return JSON.stringify({error: 'Cannot access iframe (cross-origin?)'});"
                       " var __r = iframe.contentWindow.eval(atob('" code-b64 "'));"
                       " if (__r === undefined) return JSON.stringify({result: null, type: 'undefined'});"
                       " if (typeof __r === 'object') return JSON.stringify({result: __r, type: 'object'});"
                       " return JSON.stringify({result: __r, type: typeof __r});"
                       " } catch(e) { return JSON.stringify({error: e.message, stack: e.stack}); }})()")
                  (str "(function() { try {"
                       " var __r = eval(atob('" code-b64 "'));"
                       " if (__r === undefined) return JSON.stringify({result: null, type: 'undefined'});"
                       " if (typeof __r === 'object') return JSON.stringify({result: __r, type: 'object'});"
                       " return JSON.stringify({result: __r, type: typeof __r});"
                       " } catch(e) { return JSON.stringify({error: e.message, stack: e.stack}); }})()"))]
    (wrap-result (exec-js-in-tab js-code))))

(defn browser-tab
  "Manages browser tabs - switch (by index or URL regex), open, or close."
  [{:strs [action tabIndex windowIndex url url_pattern]}]
  (let [browser (default-browser)
        win-idx (or windowIndex 1)]
    (case action
      "switch"
      (let [script (if url_pattern
                     (format "
var browser = Application('%s');
var pattern = new RegExp(%s);
var found = null;
browser.windows().forEach(function(win, wi) {
  if (found) return;
  win.tabs().forEach(function(tab, ti) {
    if (found) return;
    if (pattern.test(tab.url())) {
      win.activeTabIndex = ti + 1;
      win.index = 1;
      found = {windowIndex: wi + 1, tabIndex: ti + 1, url: tab.url(), title: tab.name()};
    }
  });
});
JSON.stringify(found || {error: 'No tab matching pattern found'});
" browser (json/generate-string url_pattern))
                     (format "
var browser = Application('%s');
var win = browser.windows()[%d];
win.activeTabIndex = %d;
var tab = win.activeTab;
JSON.stringify({windowIndex: %d, tabIndex: %d, url: tab.url(), title: tab.name()});
" browser (dec win-idx) (or tabIndex 1) win-idx (or tabIndex 1)))
            {:keys [exit out err]} (run-jxa script)]
        (if (zero? exit)
          {:content [{:type "text" :text (str/trim out)}]}
          {:content [{:type "text" :text (str "JXA error: " (str/trim (str out err)))}] :isError true}))

      "open"
      (let [target-url (or url "about:blank")
            script (format "
var browser = Application('%s');
var win = browser.windows()[%d];
var newTab = browser.Tab({url: %s});
win.tabs.push(newTab);
win.activeTabIndex = win.tabs.length;
delay(1);
var tab = win.activeTab;
JSON.stringify({tabIndex: win.tabs.length, url: tab.url(), title: tab.name()});
" browser (dec win-idx) (json/generate-string target-url))
            {:keys [exit out err]} (run-jxa script)]
        (if (zero? exit)
          {:content [{:type "text" :text (str/trim out)}]}
          {:content [{:type "text" :text (str "JXA error: " (str/trim (str out err)))}] :isError true}))

      "close"
      (let [tab-idx (or tabIndex 1)
            script (format "
var browser = Application('%s');
var win = browser.windows()[%d];
var tab = win.tabs()[%d];
var info = {url: tab.url(), title: tab.name()};
tab.close();
JSON.stringify({closed: info, remainingTabs: win.tabs.length});
" browser (dec win-idx) (dec tab-idx))
            {:keys [exit out err]} (run-jxa script)]
        (if (zero? exit)
          {:content [{:type "text" :text (str/trim out)}]}
          {:content [{:type "text" :text (str "JXA error: " (str/trim (str out err)))}] :isError true})))))

(defn browser-screenshot
  "Captures browser window screenshot using macOS screencapture.
  JXA has no screenshot API, so we find the CGWindowID via Swift and shell out."
  []
  (let [browser (default-browser)
        tmp-file "/tmp/mcp_browser_screenshot.png"
        ;; Get window bounds from JXA, then find CGWindowID via Swift
        bounds-script (format "
var browser = Application('%s');
var wins = browser.windows();
if (wins.length === 0) throw new Error('No browser windows open');
var b = wins[0].bounds();
JSON.stringify(b);
" browser)
        {:keys [exit out]} (run-jxa bounds-script)
        bounds (when (zero? exit) (json/parse-string (str/trim out) true))
        ;; Use Swift to find CGWindowID matching owner + bounds
        swift-code (when bounds
                     (format "import Cocoa; guard let wl = CGWindowListCopyWindowInfo(.optionAll, kCGNullWindowID) as? [[String: Any]] else { exit(1) }; for w in wl { guard let o = w[\"kCGWindowOwnerName\"] as? String, o == \"%s\", let l = w[\"kCGWindowLayer\"] as? Int, l == 0, let b = w[\"kCGWindowBounds\"] as? [String: Any], let bw = b[\"Width\"] as? Int, let bh = b[\"Height\"] as? Int, bw == %d, bh == %d else { continue }; if let wid = w[\"kCGWindowNumber\"] as? Int { print(wid); exit(0) } }; exit(1)"
                             browser (:width bounds) (:height bounds)))
        window-id (when swift-code
                    (let [{:keys [exit out]} (shell/sh "swift" "-e" swift-code)]
                      (when (zero? exit) (str/trim out))))
        capture-result (cond
                         ;; Try window-specific capture
                         (and window-id (not= window-id ""))
                         (shell/sh "screencapture" (str "-l" window-id) "-o" "-x" tmp-file)

                         ;; Fallback: full screen capture
                         :else
                         (shell/sh "screencapture" "-x" "-o" tmp-file))
        {:keys [exit]} capture-result]
    (if (zero? exit)
      (let [{:keys [exit out]} (shell/sh "base64" "-i" tmp-file)]
        (if (zero? exit)
          {:content [{:type "image" :data (str/trim out) :mimeType "image/png"}]}
          {:content [{:type "text" :text "Failed to encode screenshot"}] :isError true}))
      {:content [{:type "text"
                  :text "screencapture failed. Grant Screen Recording permission to Emacs (or terminal) in System Settings > Privacy & Security > Screen Recording."}]
       :isError true})))

(defn browser-network
  "Monitors network requests by monkey-patching fetch/XHR in the page context.
  No browser devtools API is available from JXA, so interception is the only option."
  [{:strs [action filter]}]
  (case action
    "start"
    (wrap-result
     (exec-js-in-tab
      "(function() {
  window.__mcp_net_log = [];
  if (!window.__mcp_orig_fetch) {
    window.__mcp_orig_fetch = window.fetch;
    window.fetch = function() {
      var args = arguments;
      var url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : String(args[0]));
      var method = 'GET';
      if (args[1] && args[1].method) method = args[1].method;
      else if (typeof args[0] === 'object' && args[0] && args[0].method) method = args[0].method;
      var entry = {type: 'fetch', method: method, url: url, startTime: Date.now(), status: null, duration: null};
      window.__mcp_net_log.push(entry);
      return window.__mcp_orig_fetch.apply(this, args).then(function(resp) {
        entry.status = resp.status;
        entry.statusText = resp.statusText;
        entry.duration = Date.now() - entry.startTime;
        entry.contentType = resp.headers.get('content-type');
        return resp;
      }).catch(function(err) {
        entry.error = err.message;
        entry.duration = Date.now() - entry.startTime;
        throw err;
      });
    };
  }
  if (!window.__mcp_orig_xhr_open) {
    window.__mcp_orig_xhr_open = XMLHttpRequest.prototype.open;
    window.__mcp_orig_xhr_send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
      this.__mcp = {type: 'xhr', method: method, url: url};
      return window.__mcp_orig_xhr_open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
      var xhr = this;
      if (xhr.__mcp) {
        xhr.__mcp.startTime = Date.now();
        xhr.addEventListener('loadend', function() {
          xhr.__mcp.status = xhr.status;
          xhr.__mcp.statusText = xhr.statusText;
          xhr.__mcp.duration = Date.now() - xhr.__mcp.startTime;
          xhr.__mcp.contentType = xhr.getResponseHeader('content-type');
          window.__mcp_net_log.push(xhr.__mcp);
        });
      }
      return window.__mcp_orig_xhr_send.apply(this, arguments);
    };
  }
  return JSON.stringify({started: true, message: 'Network capture active'});
})()"))

    "get"
    (let [js-code (format "
(function() {
  var captured = window.__mcp_net_log || [];
  var resources = performance.getEntriesByType('resource').map(function(e) {
    return {
      type: 'resource-timing',
      url: e.name,
      duration: Math.round(e.duration),
      transferSize: e.transferSize || 0,
      initiatorType: e.initiatorType,
      startTime: Math.round(e.startTime)
    };
  });
  var all = captured.concat(resources);
  var filterPattern = %s;
  if (filterPattern) {
    var re = new RegExp(filterPattern, 'i');
    all = all.filter(function(e) { return re.test(e.url); });
  }
  return JSON.stringify({capturedCount: captured.length, resourceTimingCount: resources.length, total: all.length, requests: all});
})()
" (if filter (json/generate-string filter) "null"))]
      (wrap-result (exec-js-in-tab js-code)))

    "stop"
    (wrap-result
     (exec-js-in-tab
      "(function() {
  if (window.__mcp_orig_fetch) {
    window.fetch = window.__mcp_orig_fetch;
    delete window.__mcp_orig_fetch;
  }
  if (window.__mcp_orig_xhr_open) {
    XMLHttpRequest.prototype.open = window.__mcp_orig_xhr_open;
    XMLHttpRequest.prototype.send = window.__mcp_orig_xhr_send;
    delete window.__mcp_orig_xhr_open;
    delete window.__mcp_orig_xhr_send;
  }
  var count = (window.__mcp_net_log || []).length;
  delete window.__mcp_net_log;
  return JSON.stringify({stopped: true, entriesCaptured: count});
})()"))))

(defn browser-console-logs
  "Captures console output by monkey-patching console.log/warn/error/etc.
  JXA can't access browser devtools, so we intercept at the JS API level."
  [{:strs [action level]}]
  (case action
    "start"
    (wrap-result
     (exec-js-in-tab
      "(function() {
  window.__mcp_console_log = [];
  if (!window.__mcp_orig_console) {
    window.__mcp_orig_console = {};
    ['log','warn','error','info','debug'].forEach(function(lvl) {
      window.__mcp_orig_console[lvl] = console[lvl].bind(console);
      console[lvl] = function() {
        var args = Array.from(arguments).map(function(a) {
          try {
            if (a === null) return 'null';
            if (a === undefined) return 'undefined';
            if (a instanceof Error) return a.stack || a.message;
            if (typeof a === 'object') return JSON.stringify(a);
            return String(a);
          } catch(e) { return '[unserializable]'; }
        });
        window.__mcp_console_log.push({level: lvl, timestamp: Date.now(), message: args.join(' ')});
        window.__mcp_orig_console[lvl].apply(console, arguments);
      };
    });
  }
  return JSON.stringify({started: true, message: 'Console capture active'});
})()"))

    "get"
    (let [js-code (format "
(function() {
  var log = window.__mcp_console_log || [];
  var filterLevel = %s;
  if (filterLevel) log = log.filter(function(e) { return e.level === filterLevel; });
  return JSON.stringify({count: log.length, entries: log});
})()
" (if level (json/generate-string level) "null"))]
      (wrap-result (exec-js-in-tab js-code)))

    "stop"
    (wrap-result
     (exec-js-in-tab
      "(function() {
  if (window.__mcp_orig_console) {
    ['log','warn','error','info','debug'].forEach(function(lvl) {
      if (window.__mcp_orig_console[lvl]) console[lvl] = window.__mcp_orig_console[lvl];
    });
    delete window.__mcp_orig_console;
  }
  var count = (window.__mcp_console_log || []).length;
  delete window.__mcp_console_log;
  return JSON.stringify({stopped: true, entriesCaptured: count});
})()"))))

(defn browser-wait
  "Polls the page every 500ms for a condition (element, text, or URL match).
  Polling is necessary because JXA has no event subscription mechanism."
  [{:strs [selector text url_pattern state timeout]}]
  (let [wait-state (or state "visible")
        timeout-ms (or timeout 10000)
        max-attempts (quot timeout-ms 500)]
    (loop [attempt 0]
      (if (< max-attempts attempt)
        {:content [{:type "text"
                    :text (json/generate-string
                           {:timeout true :elapsed timeout-ms
                            :condition {:selector selector :text text
                                        :url_pattern url_pattern :state wait-state}})}]}
        (let [js-code (cond
                        selector
                        (format "
(function() {
  var st = %s;
  var el = document.querySelector(%s);
  if (!el) return JSON.stringify({met: st === 'hidden', found: false});
  var rect = el.getBoundingClientRect();
  var style = window.getComputedStyle(el);
  var vis = rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  var met = st === 'present' ? true : (st === 'visible' ? vis : !vis);
  return JSON.stringify({met: met, found: true, visible: vis});
})()
" (json/generate-string wait-state) (json/generate-string selector))

                        text
                        (format "
(function() {
  var found = (document.body.innerText || '').indexOf(%s) !== -1;
  return JSON.stringify({met: found});
})()
" (json/generate-string text))

                        url_pattern
                        (format "
(function() {
  var met = new RegExp(%s).test(location.href);
  return JSON.stringify({met: met, currentUrl: location.href});
})()
" (json/generate-string url_pattern))

                        :else
                        "JSON.stringify({met: true, error: 'No condition specified'})")
              result (exec-js-in-tab js-code)]
          (if (:error result)
            ;; Page might be in transition, keep waiting
            (if (< max-attempts (inc attempt))
              {:content [{:type "text" :text (str "Error: " (:error result))}] :isError true}
              (do (Thread/sleep 500) (recur (inc attempt))))
            (let [parsed (json/parse-string (:ok result) true)]
              (if (:met parsed)
                {:content [{:type "text"
                            :text (json/generate-string
                                   (assoc parsed :elapsed (* attempt 500)))}]}
                (do (Thread/sleep 500)
                    (recur (inc attempt)))))))))))

(defn browser-scroll
  "Scrolls the page or a specific container to an element, to top/bottom, or by pixel offset.
  When container selector is provided, scrolls within that element instead of the window."
  [{:strs [selector direction pixels to container]}]
  (let [px (or pixels 500)
        js-code (cond
                  ;; scrollIntoView already handles nested scroll containers
                  (and selector (or (= to "element") (nil? to)))
                  (format "
(function() {
  var el = document.querySelector(%s);
  if (!el) return JSON.stringify({success: false, error: 'Element not found'});
  el.scrollIntoView({behavior: 'instant', block: 'center'});
  return JSON.stringify({success: true, scrollY: Math.round(window.scrollY), pageHeight: document.body.scrollHeight, viewportHeight: window.innerHeight});
})()
" (json/generate-string selector))

                  (= to "top")
                  (format "
(function() {
  var cSel = %s;
  var c = cSel ? document.querySelector(cSel) : null;
  if (cSel && !c) return JSON.stringify({success: false, error: 'Container not found'});
  if (c) {
    c.scrollTo(0, 0);
    return JSON.stringify({success: true, scrollTop: 0, scrollHeight: c.scrollHeight, clientHeight: c.clientHeight});
  }
  window.scrollTo(0, 0);
  return JSON.stringify({success: true, scrollY: 0, pageHeight: document.body.scrollHeight, viewportHeight: window.innerHeight});
})()
" (json/generate-string container))

                  (= to "bottom")
                  (format "
(function() {
  var cSel = %s;
  var c = cSel ? document.querySelector(cSel) : null;
  if (cSel && !c) return JSON.stringify({success: false, error: 'Container not found'});
  if (c) {
    c.scrollTo(0, c.scrollHeight);
    return JSON.stringify({success: true, scrollTop: Math.round(c.scrollTop), scrollHeight: c.scrollHeight, clientHeight: c.clientHeight});
  }
  window.scrollTo(0, document.body.scrollHeight);
  return JSON.stringify({success: true, scrollY: Math.round(window.scrollY), pageHeight: document.body.scrollHeight, viewportHeight: window.innerHeight});
})()
" (json/generate-string container))

                  direction
                  (format "
(function() {
  var cSel = %s;
  var c = cSel ? document.querySelector(cSel) : null;
  if (cSel && !c) return JSON.stringify({success: false, error: 'Container not found'});
  var amount = %s === 'up' ? -%d : %d;
  if (c) {
    c.scrollBy(0, amount);
    return JSON.stringify({success: true, scrollTop: Math.round(c.scrollTop), scrollHeight: c.scrollHeight, clientHeight: c.clientHeight});
  }
  window.scrollBy(0, amount);
  return JSON.stringify({success: true, scrollY: Math.round(window.scrollY), pageHeight: document.body.scrollHeight, viewportHeight: window.innerHeight});
})()
" (json/generate-string container) (json/generate-string direction) px px)

                  :else
                  "JSON.stringify({success: false, error: 'Provide selector, direction, or to parameter'})")]
    (wrap-result (exec-js-in-tab js-code))))

;;; --- MCP Dispatch ---

(defn handle-request
  "Dispatches MCP JSON-RPC requests to the appropriate handler by method name.
  Returns the JSON-RPC response envelope or nil for notifications."
  [{:strs [id method params]}]
  (case method
    "initialize"
    {:jsonrpc "2.0" :id id
     :result {:protocolVersion "2024-11-05"
              :capabilities {:tools {}}
              :serverInfo server-info}}

    "notifications/initialized" nil

    "tools/list"
    {:jsonrpc "2.0" :id id
     :result {:tools tools}}

    "tools/call"
    (let [{tool "name" args "arguments"} params]
      {:jsonrpc "2.0" :id id
       :result (case tool
                 "browser-list-tabs"       (list-tabs)
                 "browser-read-active-tab" (read-active-tab (get args "query") (get args "format"))
                 "browser-get-selection"   (get-selection)
                 "browser-navigate"        (browser-navigate args)
                 "browser-click"           (browser-click args)
                 "browser-type"            (browser-type args)
                 "browser-query"           (browser-query args)
                 "browser-execute-js"      (browser-execute-js args)
                 "browser-tab"             (browser-tab args)
                 "browser-screenshot"      (browser-screenshot)
                 "browser-network"         (browser-network args)
                 "browser-console-logs"    (browser-console-logs args)
                 "browser-wait"            (browser-wait args)
                 "browser-scroll"          (browser-scroll args)
                 {:content [{:type "text" :text (str "Unknown tool: " tool)}]
                  :isError true})})

    nil))

;;; --- Main Loop ---

(when (= *file* (System/getProperty "babashka.file"))
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (when-not (str/blank? line)
      (when-let [res (handle-request (json/parse-string line))]
        (println (json/generate-string res))
        (flush)))))
