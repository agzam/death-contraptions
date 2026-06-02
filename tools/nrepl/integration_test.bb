#!/usr/bin/env bb
;; Live integration test for the cljs/nbb await path.
;;
;; The await regression (eval-code-await bailing on nbb's spurious
;; "nth not supported" promise-print quirk instead of polling) shipped because
;; the unit tests only assert the kickoff/poll STRING shapes - never a real
;; round-trip. This test starts a REAL nbb nREPL and drives eval-code-await end
;; to end, so that class of breakage cannot pass silently again.
;;
;; Needs nbb. Reuses the pinned local nbb from the browser-repl tool; if it is
;; not installed the test SKIPS (prints + passes) rather than failing, so a
;; checkout without `npm install` is not a red suite.
;;
;; Run: bb itest   (or: bb integration_test.bb)

(ns nrepl-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as process]
            [nrepl-mcp.client :as client]))

(def ^:private nbb-bin
  "Pinned local nbb from the browser-repl tool, else nbb on PATH, else nil."
  (let [local (io/file (System/getProperty "user.dir") ".." "browser-repl"
                       "node_modules" ".bin" "nbb")]
    (cond
      (.exists local) (.getCanonicalPath local)
      (some #(.exists (io/file % "nbb"))
            (str/split (or (System/getenv "PATH") "") #":")) "nbb"
      :else nil)))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- port-up? [port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "localhost" (int port)) 200)
      true)
    (catch Exception _ false)))

(defn- wait-port [port ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (port-up? port)                         true
        (< deadline (System/currentTimeMillis)) false
        :else                                   (do (Thread/sleep 150) (recur))))))

(defn- kill-tree
  "nbb traps signals; snapshot descendants, destroy, then force-kill survivors."
  [proc]
  (try
    (let [^Process p (:proc proc)
          kids (doall (iterator-seq (.iterator (.descendants (.toHandle p)))))]
      (.destroy p)
      (doseq [k kids] (try (.destroy k) (catch Exception _)))
      (Thread/sleep 300)
      (when (.isAlive p) (.destroyForcibly p))
      (doseq [k kids] (when (.isAlive k) (.destroyForcibly k))))
    (catch Exception _)))

(deftest nbb-await-roundtrip
  (if-not nbb-bin
    (println "SKIP nbb-await-roundtrip: nbb not found (run `npm install` in tools/browser-repl)")
    (let [port (free-port)
          ;; run nbb in a THROWAWAY dir: nbb writes .nrepl-port in its CWD, and
          ;; running it in tools/browser-repl clobbered the real session's port
          ;; file + left stale debris. promesa is bundled in nbb, so CWD is free.
          work (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "nbb-itest-" (System/currentTimeMillis)))
                 (.mkdirs))
          proc (process/process [nbb-bin "nrepl-server" ":port" (str port)]
                                {:dir (.getCanonicalPath work) :out :inherit :err :inherit})]
      (try
        (is (wait-port port 25000) "nbb nREPL came up")
        (let [await (fn [code] (client/eval-code-await
                                {:host "localhost" :port port
                                 :code code :timeout-ms 15000}))
              quirk? (fn [r] (str/includes?
                              (str (:value r) " " (:err r) " " (:ex r))
                              "nth not supported"))]
          (testing "sync form returns its value (await wraps it, no quirk leaks)"
            (let [r (await "(+ 1 2)")]
              (is (nil? (:ex r)) "no error")
              (is (= "3" (:value r)))
              (is (not (quirk? r)) "the spurious nbb quirk must not leak out")))

          (testing "async success resolves to the value"
            (let [r (await "(js/Promise.resolve 42)")]
              (is (nil? (:ex r)))
              (is (= "42" (:value r)))
              (is (not (quirk? r)))))

          (testing "promesa chain (the real browser-repl shape) resolves"
            (let [r (await "(-> (p/resolved 20) (p/then inc) (p/then (partial * 2)))")]
              (is (nil? (:ex r)))
              (is (= "42" (:value r)))
              (is (not (quirk? r)))))

          (testing "async failure surfaces the REAL message, not swallowed/quirked"
            (let [r (await "(js/Promise.reject (js/Error. \"boom-test-xyz\"))")]
              (is (some? (:ex r)) "is a genuine error (isError, *e bound)")
              (is (str/includes? (str (:ex r) " " (:err r)) "boom-test-xyz")
                  "the real rejection message is preserved")
              (is (not (quirk? r)) "must be the message, not the nth-quirk"))))
        (finally
          (client/close-all!)
          (kill-tree proc)
          ;; drop the throwaway dir (and the .nrepl-port nbb wrote in it)
          (doseq [f (.listFiles work)] (.delete f))
          (.delete work))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (clojure.test/run-tests 'nrepl-integration-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
