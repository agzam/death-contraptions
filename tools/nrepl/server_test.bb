#!/usr/bin/env bb
(ns nrepl-server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [nrepl-mcp.client :as client]
            [nrepl-mcp.discovery :as discovery]
            [nrepl-mcp.sessions :as sessions]
            [nrepl-mcp.delimiters :as delimiters]
            [babashka.process :as process]))

(load-file "server.bb")

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn- find-free-port []
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)]
    (.close ss)
    port))

(def ^:dynamic *nrepl-port* nil)
(def ^:dynamic *nrepl-dir* nil)

(defn- wait-for-port [port max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (if (< deadline (System/currentTimeMillis))
        false
        (if (try
              (let [s (java.net.Socket.)]
                (.connect s (java.net.InetSocketAddress. "localhost" (int port)) 200)
                (.close s)
                true)
              (catch Exception _ false))
          true
          (do (Thread/sleep 100) (recur)))))))

(def ^:private nrepl-proc (atom nil))

(defn nrepl-fixture [f]
  (let [port (find-free-port)
        tmpdir (io/file (System/getProperty "java.io.tmpdir")
                        (str "nrepl-test-" (System/currentTimeMillis)))
        _ (.mkdirs tmpdir)
        port-file (io/file tmpdir ".nrepl-port")
        proc (process/process
              ["bb" "nrepl-server" (str "localhost:" port)]
              {:err :inherit :dir (.getAbsolutePath tmpdir)})]
    (reset! nrepl-proc proc)
    (spit port-file (str port))
    (if (wait-for-port port 5000)
      (binding [*nrepl-port* port
                *nrepl-dir* (.getAbsolutePath tmpdir)]
        (try (f)
             (finally
               (.destroyForcibly (:proc proc))
               (client/close-connection! "localhost" port)
               (.delete port-file)
               (.delete tmpdir))))
      (do (.destroyForcibly (:proc proc))
          (.delete port-file)
          (.delete tmpdir)
          (throw (ex-info "bb nrepl-server failed to start" {:port port}))))))

(use-fixtures :once nrepl-fixture)

;; ---------------------------------------------------------------------------
;; MCP protocol
;; ---------------------------------------------------------------------------

(deftest handle-request-initialize-test
  (let [resp (handle-request {"id" 1 "method" "initialize" "params" {}})]
    (is (= "2.0" (:jsonrpc resp)))
    (is (= "2024-11-05" (get-in resp [:result :protocolVersion])))
    (is (= "nrepl" (get-in resp [:result :serverInfo :name])))))

(deftest handle-request-tools-list-test
  (let [resp (handle-request {"id" 2 "method" "tools/list" "params" {}})
        tools (get-in resp [:result :tools])]
    (is (= 2 (count tools)))
    (is (= #{"nrepl-eval" "nrepl-list-ports"} (set (map :name tools))))))

(deftest handle-request-notification-test
  (is (nil? (handle-request {"method" "notifications/initialized"}))))

(deftest handle-request-unknown-tool-test
  (let [resp (handle-request
              {"id" 3 "method" "tools/call"
               "params" {"name" "bogus" "arguments" {}}})]
    (is (true? (get-in resp [:result :isError])))))

(deftest truncate-test
  (is (= "hello" (truncate "hello")))
  (let [long-str (apply str (repeat 10000 "x"))
        result (truncate long-str)]
    (is (< (count result) (count long-str)))
    (is (re-find #"truncated 8500/10000 chars" result))))

;; ---------------------------------------------------------------------------
;; Port discovery
;; ---------------------------------------------------------------------------

(deftest discover-port-file-test
  (let [entries (discovery/find-port-files *nrepl-dir*)]
    (is (= 1 (count entries)))
    (is (= *nrepl-port* (:port (first entries))))))

(deftest discover-probe-test
  (let [ports (discovery/discover-ports :start-dir *nrepl-dir*)]
    (is (= 1 (count ports)))
    (is (= :bb (:type (first ports))))
    (is (= :connected (:status (first ports))))))

(deftest list-ports-mcp-test
  (let [resp (handle-request
              {"id" 20 "method" "tools/call"
               "params" {"name" "nrepl-list-ports"
                         "arguments" {"start_dir" *nrepl-dir*}}})]
    (is (str/includes? (get-in resp [:result :content 0 :text])
                       (str *nrepl-port*)))))

;; ---------------------------------------------------------------------------
;; nREPL eval
;; ---------------------------------------------------------------------------

(deftest eval-simple-test
  (let [result (client/eval-code {:port *nrepl-port* :code "(+ 1 2 3)"})]
    (is (= "6" (:value result)))
    (is (not (:timed-out? result)))))

(deftest eval-last-value-only-test
  (testing "multi-expression returns only the last value"
    (let [result (client/eval-code {:port *nrepl-port* :code "(def y 42) y"})]
      (is (= "42" (:value result))))))

(deftest eval-stdout-test
  (let [result (client/eval-code {:port *nrepl-port*
                                  :code "(println \"hello\") :ok"})]
    (is (= ":ok" (:value result)))
    (is (str/includes? (:out result) "hello"))))

(deftest eval-error-test
  (let [result (client/eval-code {:port *nrepl-port*
                                  :code "(throw (ex-info \"boom\" {}))"})]
    (is (some? (:ex result)))))

(deftest eval-mcp-roundtrip-test
  (let [resp (handle-request
              {"id" 10 "method" "tools/call"
               "params" {"name" "nrepl-eval"
                         "arguments" {"code" "(* 7 6)"
                                      "port" *nrepl-port*}}})]
    (is (= "42" (get-in resp [:result :content 0 :text])))))

(deftest eval-no-ns-block-when-unchanged-test
  (testing "response omits [ns] when namespace didn't change"
    (let [resp (handle-request
                {"id" 12 "method" "tools/call"
                 "params" {"name" "nrepl-eval"
                           "arguments" {"code" "1" "port" *nrepl-port*}}})]
      (is (not (some #(str/starts-with? (:text %) "[ns]")
                     (get-in resp [:result :content])))))))

(deftest eval-connection-refused-test
  (let [resp (handle-request
              {"id" 11 "method" "tools/call"
               "params" {"name" "nrepl-eval"
                         "arguments" {"code" "1" "port" 19999}}})]
    (is (true? (get-in resp [:result :isError])))))

;; ---------------------------------------------------------------------------
;; Sessions
;; ---------------------------------------------------------------------------

(deftest session-clone-test
  (let [sid (sessions/get-session! "localhost" *nrepl-port*)]
    (is (string? sid))
    (is (not (str/blank? sid)))))

(deftest session-persists-state-test
  (let [sid (sessions/get-session! "localhost" *nrepl-port*)]
    (client/eval-code {:port *nrepl-port* :code "(def test-sv 999)" :session sid})
    (let [result (client/eval-code {:port *nrepl-port* :code "test-sv" :session sid})]
      (is (= "999" (:value result))))))

(deftest session-reset-test
  (let [sid1 (sessions/get-session! "localhost" *nrepl-port*)
        sid2 (sessions/reset-session! "localhost" *nrepl-port*)]
    (is (string? sid2))
    (is (not= sid1 sid2))))

(deftest session-stale-reclone-test
  (testing "stale session file gets replaced with fresh clone"
    (let [port *nrepl-port*
          ;; Write a fake stale session id
          f (io/file (str (System/getProperty "java.io.tmpdir")
                          "/eca-nrepl-sessions/" port ".session"))]
      (.mkdirs (.getParentFile f))
      (spit f "stale-session-id-that-does-not-exist")
      ;; Evict in-memory cache to force disk read
      (sessions/evict-session! port)
      ;; get-session! should detect stale, reclone
      (let [sid (sessions/get-session! "localhost" port)]
        (is (string? sid))
        (is (not= "stale-session-id-that-does-not-exist" sid))))))

;; ---------------------------------------------------------------------------
;; Discovery cache
;; ---------------------------------------------------------------------------

(deftest discovery-cache-test
  (testing "second call uses cache, not filesystem"
    (invalidate-discovery-cache!)
    (let [p1 (cached-discover-port)
          ;; Delete the port file - cached result should still work
          port-file (io/file *nrepl-dir* ".nrepl-port")
          backup (slurp port-file)]
      (.delete port-file)
      (try
        (let [p2 (cached-discover-port)]
          (is (= p1 p2)))
        (finally
          (spit port-file backup))))))

;; ---------------------------------------------------------------------------
;; Delimiter repair
;; ---------------------------------------------------------------------------

(deftest delimiter-balanced-test
  (is (not (:repaired? (delimiters/repair "(+ 1 2)")))))

(deftest delimiter-missing-close-test
  (let [{:keys [code repaired? note]} (delimiters/repair "(+ 1 2")]
    (is repaired?)
    (is (= "(+ 1 2)" code))
    (is (str/includes? note "1 missing close"))))

(deftest delimiter-missing-multiple-test
  (let [{:keys [code repaired?]} (delimiters/repair "(let [x (+ 1 2]")]
    (is repaired?)
    (is (= "(let [x (+ 1 2)])" code))))

(deftest delimiter-extra-close-test
  (let [{:keys [code repaired? note]} (delimiters/repair "(+ 1 2))")]
    (is repaired?)
    (is (= "(+ 1 2)" code))
    (is (str/includes? note "1 unmatched close"))))

(deftest delimiter-in-string-ignored-test
  (is (not (:repaired? (delimiters/repair "(str \"hello (world)\")")))))

(deftest delimiter-in-comment-ignored-test
  (is (not (:repaired? (delimiters/repair "(+ 1 2) ;; extra ( here")))))

(deftest delimiter-repair-eval-integration-test
  (let [resp (handle-request
              {"id" 40 "method" "tools/call"
               "params" {"name" "nrepl-eval"
                         "arguments" {"code" "(+ 1 2 3"
                                      "port" *nrepl-port*}}})]
    (is (= "6" (get-in resp [:result :content 0 :text])))
    (is (some #(str/includes? (:text %) "delimiter-repair")
              (get-in resp [:result :content])))))
