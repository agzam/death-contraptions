#!/usr/bin/env bb
(require '[clojure.test :as t])
(load-file "find_backlog_test.bb")
(let [{:keys [fail error]} (t/run-tests 'find-backlog-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
