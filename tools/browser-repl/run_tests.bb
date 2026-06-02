#!/usr/bin/env bb
(require '[clojure.test :as t])
(load-file "launch_test.bb")
(let [{:keys [fail error]} (t/run-tests 'browser-repl-launch-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
