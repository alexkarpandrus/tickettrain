(ns ttt.markdown
  (:require [clojure.string :as str]))

(defn marker-count
  [text marker]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote marker))
                 (or text ""))))

(defn parse-section
  [text begin-marker end-marker {:keys [code message]}]
  (let [value (or text "")
        begin-count (marker-count value begin-marker)
        end-count (marker-count value end-marker)]
    (cond
      (and (zero? begin-count) (zero? end-count))
      nil

      (not (and (= 1 begin-count) (= 1 end-count)))
      (throw (ex-info message {:code code}))

      :else
      (let [begin-index (str/index-of value begin-marker)
            end-index (str/index-of value end-marker)]
        (when (> begin-index end-index)
          (throw (ex-info message {:code code})))
        {:before (subs value 0 begin-index)
         :content (subs value (+ begin-index (count begin-marker)) end-index)
         :after (subs value (+ end-index (count end-marker)))}))))

(defn escape-label
  [value]
  (str/escape (str value)
              {\\ "\\\\"
               \[ "\\["
               \] "\\]"
               \( "\\("
               \) "\\)"
               \newline " "
               \return " "}))

(defn escape-destination
  [value]
  (str/escape (str value)
              {\space "%20"
               \( "%28"
               \) "%29"
               \< "%3C"
               \> "%3E"
               \\ "%5C"
               \newline "%0A"
               \return "%0D"}))

(defn escaped-at?
  [value index]
  (loop [cursor (dec index)
         slashes 0]
    (if (and (>= cursor 0) (= \\ (.charAt value cursor)))
      (recur (dec cursor) (inc slashes))
      (odd? slashes))))

(defn escaped-label?
  [value]
  (every? (fn [[index character]]
            (or (not (contains? #{\[ \] \( \)} character))
                (escaped-at? value index)))
          (map-indexed vector value)))

(defn link-text?
  [value]
  (when (and (str/starts-with? value "[")
             (str/ends-with? value ")"))
    (when-let [closing-index
               (first (for [index (range 1 (dec (count value)))
                            :when (and (= \] (.charAt value index))
                                       (not (escaped-at? value index))
                                       (= \( (.charAt value (inc index))))]
                        index))]
      (let [label (subs value 1 closing-index)
            destination (subs value (+ closing-index 2) (dec (count value)))]
        (and (escaped-label? label)
             (not-any? #{\( \) \newline \return} destination))))))

(defn prefixed-link?
  [line prefix]
  (and (str/starts-with? line prefix)
       (link-text? (subs line (count prefix)))))

(defn link
  [label destination]
  (str "[" (escape-label label) "](" (escape-destination destination) ")"))

(defn upsert-section
  [existing parsed section]
  (if parsed
    (str (:before parsed) section (:after parsed))
    (str (str/trimr existing)
         (when-not (str/blank? existing) "\n\n")
         section
         "\n")))
