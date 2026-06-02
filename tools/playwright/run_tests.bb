#!/usr/bin/env bb
(require '[clojure.test :as t])
(load-file "server_test.bb")
(let [{:keys [fail error]} (t/run-tests 'playwright-server-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
