#!/usr/bin/env bb
(ns setup-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Load setup.bb by absolute path: a relative load would NPE on
;; (-> *file* io/file .getParentFile), and the babashka.file guard in
;; setup.bb keeps -main from running when we load it here.
(def ^:private repo-root (-> *file* io/file .getCanonicalFile .getParent))
(load-file (str repo-root "/setup.bb"))

(deftest playwright-registered-and-default-disabled-test
  (testing "playwright is registered but disabled when local-config omits it"
    (let [entries (build-server-entries {})]
      (is (contains? entries "playwright"))
      (is (true? (:disabled (get entries "playwright"))))
      (is (str/ends-with? (:command (get entries "playwright"))
                          "/playwright/server.bb")))))

(deftest playwright-enabled-when-local-sets-disabled-false-test
  (testing "explicit :disabled? false in local-config enables playwright"
    (let [entries (build-server-entries {:servers {:playwright {:disabled? false}}})]
      (is (not (:disabled (get entries "playwright")))))))

(deftest default-disabled-does-not-leak-test
  (testing "servers without :default-disabled? stay enabled by default"
    (let [entries (build-server-entries {})]
      (is (not (:disabled (get entries "elisp-eval"))))
      (is (not (:disabled (get entries "k8s")))))))
