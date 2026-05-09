(ns nrepl-mcp.delimiters
  "Pre-flight delimiter repair for Clojure code. Catches mismatched
   parens/brackets/braces before sending to nREPL, preventing the
   paren-edit death loop where LLMs repeatedly fail to fix delimiter
   errors.")

(def ^:private openers #{\( \[ \{})
(def ^:private closers #{\) \] \}})
(def ^:private close-for {\( \) \[ \] \{ \}})


(defn- scan-delimiters
  "Walk code tracking a delimiter stack, respecting strings, comments,
   char literals, and regex. Returns {:stack [unmatched-opens]
   :extras [indices-of-unmatched-closes]}."
  [^String code]
  (let [len (.length code)]
    (loop [i 0, state :normal, stack [], extras [], escape? false]
      (if (< i len)
        (let [c (.charAt code i)
              nc (when (< (inc i) len) (.charAt code (inc i)))]
          (case state
            :normal
            (cond
              (= c \;)
              (recur (inc i) :comment stack extras false)

              (= c \")
              (recur (inc i) :string stack extras false)

              (and (= c \#) (= nc \"))
              (recur (+ i 2) :regex stack extras false)

              (= c \\)
              (recur (+ i 2) :normal stack extras false)

              (openers c)
              (recur (inc i) :normal (conj stack c) extras false)

              (closers c)
              (if (and (seq stack) (= c (close-for (peek stack))))
                (recur (inc i) :normal (pop stack) extras false)
                (recur (inc i) :normal stack (conj extras i) false))

              :else
              (recur (inc i) :normal stack extras false))

            :string
            (cond
              escape?
              (recur (inc i) :string stack extras false)
              (= c \\)
              (recur (inc i) :string stack extras true)
              (= c \")
              (recur (inc i) :normal stack extras false)
              :else
              (recur (inc i) :string stack extras false))

            :regex
            (cond
              escape?
              (recur (inc i) :regex stack extras false)
              (= c \\)
              (recur (inc i) :regex stack extras true)
              (= c \")
              (recur (inc i) :normal stack extras false)
              :else
              (recur (inc i) :regex stack extras false))

            :comment
            (if (= c \newline)
              (recur (inc i) :normal stack extras false)
              (recur (inc i) :comment stack extras false))))

        {:stack stack :extras extras}))))

(defn delimiter-error?
  "Returns true if the code has mismatched delimiters."
  [code]
  (let [{:keys [stack extras]} (scan-delimiters code)]
    (or (seq stack) (seq extras))))

(defn repair
  "Attempt to fix delimiter mismatches. Returns {:code :repaired? :note}.
   Appends missing closing delimiters, removes unmatched closing ones."
  [code]
  (let [{:keys [stack extras]} (scan-delimiters code)]
    (if (and (empty? stack) (empty? extras))
      {:code code :repaired? false}
      (let [;; Remove unmatched closes (reverse order to preserve indices)
            code-without-extras
            (if (empty? extras)
              code
              (let [extra-set (set extras)
                    sb (StringBuilder.)]
                (dotimes [i (.length ^String code)]
                  (when-not (extra-set i)
                    (.append sb (.charAt ^String code i))))
                (.toString sb)))
            ;; Append missing closes for unmatched opens
            closes (apply str (map close-for (reverse stack)))
            repaired (str code-without-extras closes)
            note (str "Repaired delimiters: "
                      (when (seq extras)
                        (str "removed " (count extras) " unmatched close(s)"))
                      (when (and (seq extras) (seq stack)) ", ")
                      (when (seq stack)
                        (str "added " (count stack) " missing close(s): " closes)))]
        {:code repaired
         :repaired? true
         :note note}))))
