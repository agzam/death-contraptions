#!/usr/bin/env bb
(require '[clojure.test :as t])
(load-file "setup_test.bb")
(let [{:keys [fail error]} (t/run-tests 'setup-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
