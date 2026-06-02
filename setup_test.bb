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

(deftest servers-enabled-by-default-test
  (testing "servers stay enabled (no :disabled) when local-config omits them"
    (let [entries (build-server-entries {})]
      (is (not (:disabled (get entries "elisp-eval"))))
      (is (not (:disabled (get entries "k8s")))))))

(deftest local-disabled-flag-disables-test
  (testing "explicit :disabled? true in local-config marks the server disabled"
    (let [entries (build-server-entries {:servers {:k8s {:disabled? true}}})]
      (is (true? (:disabled (get entries "k8s"))))
      (is (str/ends-with? (:command (get entries "k8s")) "/k8s/server.bb")))))
