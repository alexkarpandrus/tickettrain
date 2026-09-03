(ns ttt.prompt
  (:require [clojure.string :as str]
            [ttt.ui :as ui]))

(defn ask
  [message]
  (print (str message " "))
  (flush)
  (str/trim (or (read-line) "")))

(defn confirm?
  [message]
  (contains? #{"y" "yes"}
             (str/lower-case (ask (str message " [y/N]")))))

(defn choose-index
  [max-index entity-label]
  (loop []
    (let [response (ask (format "Choose a %s [1-%d]:" entity-label max-index))]
      (if-let [parsed (try
                        (Integer/parseInt response)
                        (catch Exception _ nil))]
        (if (<= 1 parsed max-index)
          (dec parsed)
          (do
            (println (ui/warning "Selection out of range."))
            (recur)))
        (do
          (println (ui/warning "Enter a number."))
          (recur))))))
