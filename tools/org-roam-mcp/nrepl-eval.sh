#!/usr/bin/env bash
# Eval a file of Clojure code against the running nREPL.
# Usage: ./nrepl-eval.sh file.clj
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT=$(cat "$SCRIPT_DIR/.nrepl-port" 2>/dev/null)
[ -z "$PORT" ] && echo "No .nrepl-port found" && exit 1
FILE="${1:?Usage: nrepl-eval.sh <file.clj>}"
clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' -M -e "
(require '[nrepl.core :as nrepl])
(with-open [conn (nrepl/connect :port $PORT)]
  (let [client (nrepl/client conn 120000)
        code (slurp \"$FILE\")
        resp (nrepl/message client {:op :eval :code code})]
    (doseq [r resp]
      (when-let [o (:out r)] (print o))
      (when-let [e (:err r)] (binding [*out* *err*] (print e)))
      (when-let [v (:value r)] (println v)))))
"
