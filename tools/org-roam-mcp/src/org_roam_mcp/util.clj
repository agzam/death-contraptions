(ns org-roam-mcp.util
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(def ^:private home (System/getProperty "user.home"))

(defn expand-home
  "Expand ~/... to absolute path."
  [path]
  (if (str/starts-with? path "~/")
    (str home (subs path 1))
    path))

(defn contract-home
  "Replace home prefix with ~/ for display."
  [path]
  (if (str/starts-with? path home)
    (str "~" (subs path (count home)))
    path))

(defn log
  "Log a message to stderr with timestamp."
  [& args]
  (binding [*out* *err*]
    (println (str "[" (Instant/now) "]") (str/join " " args))
    (flush)))

(defn sha256
  "SHA-256 hex digest of a string."
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))
