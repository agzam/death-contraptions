#!/usr/bin/env bb
(require '[clojure.test :as t])
(load-file "jira_board_test.bb")
(let [{:keys [fail error]} (t/run-tests 'jira-board-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
