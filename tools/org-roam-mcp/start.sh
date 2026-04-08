#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
exec clojure -J-Xmx512m \
     -J--add-modules -Jjdk.incubator.vector \
     -M -m org-roam-mcp.core \
     "$SCRIPT_DIR/config.edn" 2>"$SCRIPT_DIR/server.log"
